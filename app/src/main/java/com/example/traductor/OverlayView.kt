package com.example.traductor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View

class OverlayView(context: Context) : View(context) {

    private var bloques: List<Pair<Rect, String>> = emptyList()
    private var manga = false
    var factor = 1f

    private val fondo = Paint().apply { style = Paint.Style.FILL }
    private val pincel = TextPaint().apply { isAntiAlias = true }

    // Pares (color del globo, color de la letra) para cada tema
    private val temas = listOf(
        Color.WHITE to Color.BLACK,
        Color.rgb(25, 25, 25) to Color.WHITE,
        Color.rgb(255, 243, 176) to Color.rgb(40, 30, 0),
        Color.rgb(20, 40, 90) to Color.WHITE
    )

    fun mostrar(nuevos: List<Pair<Rect, String>>, manga: Boolean = false) {
        bloques = nuevos
        this.manga = manga
        invalidate()
    }

    fun agregar(nuevos: List<Pair<Rect, String>>, manga: Boolean = false) {
        bloques = bloques + nuevos
        this.manga = manga
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val tema = EstiloTraduccion.tema
        val (colorFondo, colorTexto) = when {
            tema in 1..4 -> temas[tema - 1]
            manga -> temas[0]   // auto + manga: globo blanco
            else -> temas[1]    // auto + modo normal: fondo oscuro
        }
        fondo.color = colorFondo
        fondo.alpha = (EstiloTraduccion.opacidad * 255).toInt().coerceIn(0, 255)
        pincel.color = colorTexto

        val estiloLetra = if (EstiloTraduccion.negrita) Typeface.BOLD else Typeface.NORMAL
        val base = when (EstiloTraduccion.fuente) {
            "serif" -> Typeface.SERIF
            "mono" -> Typeface.MONOSPACE
            else -> Typeface.SANS_SERIF
        }
        pincel.typeface = Typeface.create(base, estiloLetra)

        val alineacion =
            if (manga) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL
        val maximo = (60f * EstiloTraduccion.escalaTexto * factor).coerceAtLeast(16f * factor)

        for ((rect, texto) in bloques) {
            if (manga) {
                val radio = minOf(rect.width(), rect.height()) * 0.35f
                canvas.drawRoundRect(RectF(rect), radio, radio, fondo)
            } else {
                canvas.drawRect(rect, fondo)
            }

            // En manga el texto va un poco hacia adentro para no tocar el borde del globo
            val area = Rect(rect)
            if (manga) area.inset((rect.width() * 0.1f).toInt(), (rect.height() * 0.1f).toInt())

            val ancho = area.width().coerceAtLeast(1)
            var tamano = (area.height() * 0.8f).coerceIn(16f * factor, maximo)
            var layout: StaticLayout
            do {
                pincel.textSize = tamano
                layout = StaticLayout.Builder.obtain(texto, 0, texto.length, pincel, ancho)
                    .setAlignment(alineacion).build()
                tamano -= 2f
            } while (layout.height > area.height() && tamano > 12f * factor)

            val desfaseY =
                if (manga) ((area.height() - layout.height) / 2f).coerceAtLeast(0f) else 0f
            canvas.save()
            canvas.translate(area.left.toFloat(), area.top + desfaseY)
            layout.draw(canvas)
            canvas.restore()
        }
    }
}