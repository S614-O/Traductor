package com.example.traductor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

// clase: 1 = globo con texto, 2 = texto suelto sobre el dibujo
class Globo(val rect: RectF, val puntuacion: Float, val clase: Int)

class DetectorGlobos(context: Context) {

    private val entorno = OrtEnvironment.getEnvironment()
    private val sesion: OrtSession

    init {
        val modelo = context.assets.open("detector.onnx").use { it.readBytes() }
        sesion = entorno.createSession(modelo, OrtSession.SessionOptions())
        Log.d("Detector", "Entradas: ${sesion.inputNames} Salidas: ${sesion.outputNames}")
    }

    fun detectar(bitmap: Bitmap, umbral: Float = 0.3f, incluirGlobos: Boolean = false): List<Globo> {
        val lado = 640
        val plano = lado * lado

        // La imagen se estira a 640x640, en RGB y con valores de 0 a 1
        val reducido = Bitmap.createScaledBitmap(bitmap, lado, lado, true)
        val pixeles = IntArray(plano)
        reducido.getPixels(pixeles, 0, lado, 0, 0, lado, lado)
        if (reducido !== bitmap) reducido.recycle()

        val datos = ByteBuffer.allocateDirect(3 * plano * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until plano) {
            val p = pixeles[i]
            datos.put(i, ((p shr 16) and 0xFF) / 255f)          // R
            datos.put(plano + i, ((p shr 8) and 0xFF) / 255f)   // G
            datos.put(2 * plano + i, (p and 0xFF) / 255f)       // B
        }

        // Tamaño original de la página: [ancho, alto]
        val tamanos = ByteBuffer.allocateDirect(2 * 8)
            .order(ByteOrder.nativeOrder()).asLongBuffer()
        tamanos.put(0, bitmap.width.toLong())
        tamanos.put(1, bitmap.height.toLong())

        val tensorImagen = OnnxTensor.createTensor(
            entorno, datos, longArrayOf(1, 3, lado.toLong(), lado.toLong())
        )
        val tensorTamanos = OnnxTensor.createTensor(entorno, tamanos, longArrayOf(1, 2))

        val resultado = ArrayList<Globo>()
        try {
            sesion.run(
                mapOf("images" to tensorImagen, "orig_target_sizes" to tensorTamanos)
            ).use { salida ->
                val etiquetas = aLista((salida.get("labels").get().value as Array<*>)[0])
                val cajas = (salida.get("boxes").get().value as Array<*>)[0] as Array<*>
                val puntuaciones = aLista((salida.get("scores").get().value as Array<*>)[0])

                for (i in puntuaciones.indices) {
                    if (puntuaciones[i] < umbral) continue
                    val clase = etiquetas[i].toInt()
                    if (clase == 0 && !incluirGlobos) continue // contorno de globo vacío
                    val c = aLista(cajas[i])
                    resultado.add(Globo(RectF(c[0], c[1], c[2], c[3]), puntuaciones[i], clase))
                }
            }
        } finally {
            tensorImagen.close()
            tensorTamanos.close()
        }
        return resultado
    }

    // Convierte cualquier arreglo numérico de ONNX en una lista de Float
    private fun aLista(v: Any?): List<Float> = when (v) {
        is FloatArray -> v.toList()
        is LongArray -> v.map { it.toFloat() }
        is IntArray -> v.map { it.toFloat() }
        is DoubleArray -> v.map { it.toFloat() }
        else -> emptyList()
    }

    fun cerrar() {
        sesion.close()
    }
}