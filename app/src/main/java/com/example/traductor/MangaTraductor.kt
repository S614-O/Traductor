package com.example.traductor

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object MangaTraductor {

    // Los nombres de modelo cambian seguido: confirma el modelo vigente en la documentación de Gemini
    private const val MODELO = "gemini-3.7-flash"

    private const val PROMPT = """
Eres un traductor profesional de manga. La imagen es una página de manga o solo un fragmento (por ejemplo, un único globo), normalmente en japonés, ingles,chino o coreano.
El texto japonés puede estar en vertical: se lee de arriba hacia abajo y las columnas van de derecha a izquierda.
El orden de lectura de los globos es de derecha a izquierda y de arriba hacia abajo.

Encuentra cada globo de diálogo y cada cuadro de narración. Para cada uno devuelve:
- box_2d: [ymin, xmin, ymax, xmax] normalizado de 0 a 1000, cubriendo TODO el globo o cuadro (no solo las letras).
- original: el texto original completo, leído en el orden correcto.
- traduccion: traducción al español natural, fiel al tono del personaje y lo bastante corta para caber en el globo. Usa el contexto de toda la página.
- tipo: "dialogo", "narracion" o "sfx" (efectos de sonido dibujados).

Devuelve SOLO un arreglo JSON, sin texto adicional. Si no hay texto, devuelve [].
"""

    // Si Gemini está saturado, espera y vuelve a intentar (hasta 4 intentos)
    // Llamada bloqueante: ejecútala en un hilo secundario
    fun traducir(bitmap: Bitmap, apiKey: String): List<Pair<Rect, String>> {
        val esperas = longArrayOf(3000, 6000, 12000)
        for (espera in esperas) {
            try {
                return traducirUnaVez(bitmap, apiKey)
            } catch (e: ErrorServidor) {
                Thread.sleep(espera)
            }
        }
        return traducirUnaVez(bitmap, apiKey)
    }

    private fun traducirUnaVez(bitmap: Bitmap, apiKey: String): List<Pair<Rect, String>> {
        val salida = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, salida)
        val base64 = Base64.encodeToString(salida.toByteArray(), Base64.NO_WRAP)

        val cuerpo = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("inline_data", JSONObject()
                    .put("mime_type", "image/png")
                    .put("data", base64)))
                .put(JSONObject().put("text", PROMPT)))))
            put("generationConfig", JSONObject()
                .put("response_mime_type", "application/json")
                .put("temperature", 0.2))
        }

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODELO:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 90000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        conn.outputStream.use { it.write(cuerpo.toString().toByteArray()) }

        val codigo = conn.responseCode
        val respuesta = (if (codigo in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""

        if (codigo in listOf(429, 500, 503, 504)) {
            Log.e("Manga", "Error $codigo: $respuesta")
            throw ErrorServidor("Gemini está saturado o con límite de uso (error $codigo). Reintenta en unos minutos.")
        }
        if (codigo !in 200..299) throw Exception("Error $codigo: $respuesta")

        var texto = JSONObject(respuesta)
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
            .getString("text").trim()
        texto = texto.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        Log.d("Manga", "Gemini respondió: ${texto.take(2000)}")

        val globos = JSONArray(texto)
        val resultado = mutableListOf<Pair<Rect, String>>()
        for (i in 0 until globos.length()) {
            val o = globos.getJSONObject(i)
            if (o.optString("tipo") == "sfx") continue // no tapamos el dibujo con efectos de sonido
            val traduccion = o.optString("traduccion")
            if (traduccion.isBlank()) continue
            val b = o.getJSONArray("box_2d")
            val rect = Rect(
                (b.getDouble(1) / 1000 * bitmap.width).toInt(),
                (b.getDouble(0) / 1000 * bitmap.height).toInt(),
                (b.getDouble(3) / 1000 * bitmap.width).toInt(),
                (b.getDouble(2) / 1000 * bitmap.height).toInt()
            )
            rect.inset(-8, -8)
            resultado.add(rect to traduccion)
        }

        Log.d("Manga", "Globos a dibujar: ${resultado.size}")
        return resultado
    }
}

class ErrorServidor(mensaje: String) : Exception(mensaje)