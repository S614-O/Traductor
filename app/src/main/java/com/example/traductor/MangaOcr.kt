package com.example.traductor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL

object MangaOcr {

    private const val LADO = 224
    private const val INICIO = 2L   // [CLS]
    private const val FIN = 3L      // [SEP]
    private const val MAX_TOKENS = 64

    private var entorno: OrtEnvironment? = null
    private var codificador: OrtSession? = null
    private var decodificador: OrtSession? = null
    private var vocab: List<String> = emptyList()

    private fun carpeta(context: Context) =
        File(context.filesDir, "manga-ocr").apply { mkdirs() }

    fun disponible(context: Context): Boolean {
        val c = carpeta(context)
        return File(c, "encoder.onnx").length() > 1_000_000 &&
                File(c, "decoder.onnx").length() > 1_000_000 &&
                File(c, "vocab.txt").length() > 10_000
    }

    // Archivos del modelo y de dónde se bajan.
// Para las versiones ligeras, cambia encoder_model.onnx y decoder_model.onnx
// por encoder_model_int8.onnx y decoder_model_int8.onnx
    private val ARCHIVOS = listOf(
        "vocab.txt" to
                "https://huggingface.co/kha-white/manga-ocr-base/resolve/main/vocab.txt?download=true",
        "decoder.onnx" to
                "https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/main/onnx/decoder_model.onnx?download=true",
        "encoder.onnx" to
                "https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/main/onnx/encoder_model.onnx?download=true"
    )

    // ¿Hay una conexión sin límite de datos (normalmente Wi-Fi)?
    fun conWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val red = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(red) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    // Descarga los archivos que falten.
// Llamada bloqueante: ejecútala en un hilo secundario
    @Synchronized
    fun descargar(context: Context, progreso: (String) -> Unit) {
        val dir = carpeta(context)
        for ((nombre, direccion) in ARCHIVOS) {
            val destino = File(dir, nombre)
            if (destino.exists() && destino.length() > 0) continue

            val temporal = File(dir, "$nombre.part")
            val conexion = URL(direccion).openConnection() as HttpURLConnection
            conexion.connectTimeout = 30_000
            conexion.readTimeout = 60_000
            try {
                if (conexion.responseCode !in 200..299) {
                    throw Exception("Error ${conexion.responseCode} al bajar $nombre")
                }
                val total = conexion.contentLengthLong
                var escrito = 0L
                var ultimaMarca = -1L
                conexion.inputStream.use { entrada ->
                    temporal.outputStream().use { salida ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val leidos = entrada.read(buffer)
                            if (leidos < 0) break
                            salida.write(buffer, 0, leidos)
                            escrito += leidos
                            val marca = if (total > 0) escrito * 100 / total else escrito / 5_000_000
                            if (marca != ultimaMarca) {
                                ultimaMarca = marca
                                progreso(
                                    if (total > 0) "Descargando $nombre: $marca %"
                                    else "Descargando $nombre: ${escrito / 1_000_000} MB"
                                )
                            }
                        }
                    }
                }
                if (total > 0 && escrito != total) throw Exception("Descarga incompleta de $nombre")
                if (!temporal.renameTo(destino)) throw Exception("No se pudo guardar $nombre")
            } finally {
                conexion.disconnect()
                if (temporal.exists()) temporal.delete()
            }
        }
        cerrar() // fuerza a recargar con los archivos nuevos
    }

    private fun nombreDe(context: Context, uri: Uri): String? =
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    // Copia a la memoria de la app los archivos elegidos (encoder, decoder y vocab.txt)
// Copia a la memoria de la app los archivos elegidos (encoder, decoder y vocab.txt)
    @Synchronized
    fun importar(context: Context, uris: List<Uri>): Int {
        cerrar() // libera los archivos antes de reemplazarlos
        var copiados = 0
        for (uri in uris) {
            val nombre = nombreDe(context, uri)?.lowercase() ?: continue
            val destino = when {
                nombre.contains("encoder") -> "encoder.onnx"
                nombre.contains("decoder") -> "decoder.onnx"
                nombre.contains("vocab") -> "vocab.txt"
                else -> continue
            }
            val temporal = File(carpeta(context), "$destino.part")
            context.contentResolver.openInputStream(uri)?.use { entrada ->
                temporal.outputStream().use { salida -> entrada.copyTo(salida) }
            }
            File(carpeta(context), destino).delete()
            if (temporal.renameTo(File(carpeta(context), destino))) copiados++
        }
        return copiados
    }

    @Synchronized
    fun cerrar() {
        codificador?.close()
        decodificador?.close()
        codificador = null
        decodificador = null
    }

    @Synchronized
    private fun cargar(context: Context) {
        if (codificador != null && decodificador != null) return
        val c = carpeta(context)
        val env = OrtEnvironment.getEnvironment()
        fun opciones() = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }

        codificador = env.createSession(File(c, "encoder.onnx").absolutePath, opciones())
        decodificador = env.createSession(File(c, "decoder.onnx").absolutePath, opciones())
        vocab = File(c, "vocab.txt").readLines()
        entorno = env
        Log.d("MangaOcr", "Encoder: ${codificador!!.inputNames} -> ${codificador!!.outputNames}")
        Log.d("MangaOcr", "Decoder: ${decodificador!!.inputNames} -> ${decodificador!!.outputNames}")
    }

    // Lee el texto de un recorte (un globo o una columna de texto).
    // Llamada bloqueante: ejecútala en un hilo secundario
    @Synchronized
    fun leer(context: Context, recorte: Bitmap): String {
        cargar(context)
        val env = entorno!!
        val enc = codificador!!
        val dec = decodificador!!

        val nombreIds = dec.inputNames.firstOrNull { it.contains("input_ids") }
            ?: throw Exception("El decodificador no tiene la entrada input_ids: ${dec.inputNames}")
        val nombreOcultos = dec.inputNames.firstOrNull { it.contains("encoder_hidden_states") }
            ?: throw Exception("El decodificador no tiene encoder_hidden_states: ${dec.inputNames}")

        // 1) Imagen en gris, 224x224, con valores de -1 a 1
        val pequeno = Bitmap.createScaledBitmap(recorte, LADO, LADO, true)
        val pix = IntArray(LADO * LADO)
        pequeno.getPixels(pix, 0, LADO, 0, 0, LADO, LADO)
        if (pequeno !== recorte) pequeno.recycle()

        val plano = LADO * LADO
        val datos = ByteBuffer.allocateDirect(3 * plano * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until plano) {
            val p = pix[i]
            val gris = 0.299f * ((p shr 16) and 0xFF) +
                    0.587f * ((p shr 8) and 0xFF) +
                    0.114f * (p and 0xFF)
            val v = gris / 255f * 2f - 1f
            datos.put(i, v)
            datos.put(plano + i, v)
            datos.put(2 * plano + i, v)
        }
        val tensorImagen = OnnxTensor.createTensor(
            env, datos, longArrayOf(1, 3, LADO.toLong(), LADO.toLong())
        )

        // 2) Codificador una vez, y el decodificador carácter por carácter
        val ids = ArrayList<Long>()
        ids.add(INICIO)
        try {
            enc.run(mapOf(enc.inputNames.first() to tensorImagen)).use { salidaEnc ->
                val ocultos = salidaEnc.get(0) as OnnxTensor
                for (paso in 0 until MAX_TOKENS) {
                    val buffer = ByteBuffer.allocateDirect(ids.size * 8)
                        .order(ByteOrder.nativeOrder()).asLongBuffer()
                    for (i in ids.indices) buffer.put(i, ids[i])
                    val tensorIds =
                        OnnxTensor.createTensor(env, buffer, longArrayOf(1, ids.size.toLong()))

                    val siguiente = try {
                        dec.run(mapOf(nombreIds to tensorIds, nombreOcultos to ocultos))
                            .use { salidaDec ->
                                val logits = salidaDec.get(0) as OnnxTensor
                                val forma = logits.info.shape
                                val n = forma[1].toInt()
                                val v = forma[2].toInt()
                                val fb = logits.floatBuffer
                                val base = (n - 1) * v
                                val vetados = prohibidos(ids)
                                var mejor = -1
                                var mejorValor = Float.NEGATIVE_INFINITY
                                for (j in 0 until v) {
                                    if (j in vetados) continue
                                    val x = fb.get(base + j)
                                    if (x > mejorValor) {
                                        mejorValor = x
                                        mejor = j
                                    }
                                }
                                mejor.toLong()
                            }
                    } finally {
                        tensorIds.close()
                    }
                    if (siguiente == FIN || siguiente < 0) break
                    ids.add(siguiente)
                }
            }
        } finally {
            tensorImagen.close()
        }
        return decodificar(ids)
    }

    // Tokens que no se pueden elegir: especiales y los que repetirían un grupo de 3
    private fun prohibidos(ids: List<Long>): Set<Int> {
        val ban = hashSetOf(0, 1, 2, 4) // [PAD], [UNK], [CLS], [MASK]
        val n = 3
        if (ids.size >= n) {
            val prefijo = ids.subList(ids.size - (n - 1), ids.size)
            for (i in 0..ids.size - n) {
                if (ids.subList(i, i + n - 1) == prefijo) ban.add(ids[i + n - 1].toInt())
            }
        }
        return ban
    }

    private fun decodificar(ids: List<Long>): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id in 0..4) continue // especiales
            val t = vocab.getOrNull(id.toInt()) ?: continue
            sb.append(t.removePrefix("##"))
        }
        return sb.toString().replace(Regex("\\s+"), "").replace("…", "...")
    }
}