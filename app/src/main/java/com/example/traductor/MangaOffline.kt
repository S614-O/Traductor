package com.example.traductor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer

object MangaOffline {

    private var detector: DetectorGlobos? = null

    // El modelo se carga una sola vez y se reutiliza
    @Synchronized
    private fun obtenerDetector(context: Context): DetectorGlobos =
        detector ?: DetectorGlobos(context.applicationContext).also { detector = it }

    // Llamada bloqueante: ejecútala en un hilo secundario
    fun traducir(
        context: Context,
        bitmap: Bitmap,
        recognizer: TextRecognizer,
        translator: Translator,
        idioma: String,
        esZona: Boolean = false
    ): List<Pair<Rect, String>> {
        val detectados = obtenerDetector(context).detectar(bitmap, incluirGlobos = true)
        val globos = detectados.filter { it.clase == 0 }.map { it.rect }
        var textos = detectados.filter { it.clase != 0 }.map { it.rect }

        // En una zona marcada a mano, si no se detecta nada, se usa toda la zona
        if (textos.isEmpty() && esZona) {
            textos = listOf(RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()))
        }

        val resultado = mutableListOf<Pair<Rect, String>>()
        for (t in textos) {
            val recorte = recortar(bitmap, t) ?: continue
            val original = try {
                Tasks.await(recognizer.process(InputImage.fromBitmap(recorte, 0))).text
            } finally {
                recorte.recycle()
            }
            // Ignora efectos como "!" o "..." que no son texto traducible
            if (original.count { it.isLetter() } < 2) continue

            val limpio = limpiarTexto(original, idioma)
            val traduccion = Tasks.await(translator.translate(limpio)).trim()
            if (traduccion.isBlank()) continue

            Log.d("Manga", "$original -> $traduccion")
            resultado.add(areaDeDibujo(t, globos, bitmap) to traduccion)
        }
        return resultado
    }

    // Recorta la caja con un pequeño margen y la agranda para que el OCR lea mejor
    private fun recortar(bitmap: Bitmap, r: RectF): Bitmap? {
        val margen = 6
        val izq = (r.left.toInt() - margen).coerceIn(0, bitmap.width - 1)
        val arr = (r.top.toInt() - margen).coerceIn(0, bitmap.height - 1)
        val der = (r.right.toInt() + margen).coerceIn(izq + 1, bitmap.width)
        val aba = (r.bottom.toInt() + margen).coerceIn(arr + 1, bitmap.height)
        val w = der - izq
        val h = aba - arr
        if (w < 8 || h < 8) return null

        val recorte = Bitmap.createBitmap(bitmap, izq, arr, w, h)
        val factor = if (maxOf(w, h) < 500) 2f else 1.5f
        val ampliado = Bitmap.createScaledBitmap(
            recorte, (w * factor).toInt(), (h * factor).toInt(), true
        )
        if (ampliado !== recorte) recorte.recycle()
        return ampliado
    }

    // Une las líneas y arregla el texto antes de traducirlo
    private fun limpiarTexto(t: String, idioma: String): String {
        var s = t.replace("-\n", "").replace("\n", " ").replace(Regex("\\s+"), " ").trim()

        // Los cómics en inglés suelen ir en MAYÚSCULAS; en minúsculas el traductor rinde mejor
        val letras = s.filter { it.isLetter() }
        if (idioma == "en" && letras.length >= 4 &&
            letras.count { it.isUpperCase() } >= letras.length * 0.8
        ) {
            s = s.lowercase()
            s = Regex("(^|[.!?]\\s+)([a-z])").replace(s) {
                it.groupValues[1] + it.groupValues[2].uppercase()
            }
            s = s.replace(Regex("\\bi\\b"), "I")
            s = s.replace(Regex("\\bi'(m|ll|ve|d)\\b"), "I'$1")
        }
        return s
    }

    // Si el texto está dentro de un globo detectado, se dibuja sobre el globo completo
    private fun areaDeDibujo(texto: RectF, globos: List<RectF>, bitmap: Bitmap): Rect {
        val areaTexto = texto.width() * texto.height()
        val globo = globos
            .filter {
                it.contains(texto.centerX(), texto.centerY()) &&
                        it.width() * it.height() <= areaTexto * 6
            }
            .minByOrNull { it.width() * it.height() }

        val base = if (globo != null) RectF(globo).apply { union(texto) } else RectF(texto)
        val relleno = if (globo != null) 0f else 8f
        return Rect(
            (base.left - relleno).toInt().coerceAtLeast(0),
            (base.top - relleno).toInt().coerceAtLeast(0),
            (base.right + relleno).toInt().coerceAtMost(bitmap.width),
            (base.bottom + relleno).toInt().coerceAtMost(bitmap.height)
        )
    }
}