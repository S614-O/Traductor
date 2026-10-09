package com.example.traductor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View

class SeleccionView(
    context: Context,
    private val onZona: (Rect) -> Unit,
    private val onCancelar: () -> Unit
) : View(context) {

    private var inicioX = 0f
    private var inicioY = 0f
    private var finX = 0f
    private var finY = 0f
    private var dibujando = false

    private val oscuro = Paint().apply { color = Color.argb(120, 0, 0, 0) }
    private val borde = Paint().apply {
        color = Color.rgb(30, 144, 255)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val texto = Paint().apply {
        color = Color.WHITE
        textSize = 48f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (!dibujando) {
            canvas.drawRect(0f, 0f, w, h, oscuro)
            canvas.drawText("Arrastra para elegir la zona", w / 2f, h / 2f, texto)
            return
        }
        val izq = minOf(inicioX, finX)
        val der = maxOf(inicioX, finX)
        val arr = minOf(inicioY, finY)
        val aba = maxOf(inicioY, finY)
        // Oscurece todo menos la zona elegida
        canvas.drawRect(0f, 0f, w, arr, oscuro)
        canvas.drawRect(0f, aba, w, h, oscuro)
        canvas.drawRect(0f, arr, izq, aba, oscuro)
        canvas.drawRect(der, arr, w, aba, oscuro)
        canvas.drawRect(izq, arr, der, aba, borde)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                inicioX = event.x
                inicioY = event.y
                finX = event.x
                finY = event.y
                dibujando = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                finX = event.x
                finY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                finX = event.x
                finY = event.y
                val zona = Rect(
                    minOf(inicioX, finX).toInt(),
                    minOf(inicioY, finY).toInt(),
                    maxOf(inicioX, finX).toInt(),
                    maxOf(inicioY, finY).toInt()
                )
                // Un toque o una zona muy pequeña cancela la selección
                if (zona.width() < 40 || zona.height() < 40) onCancelar() else onZona(zona)
            }
        }
        return true
    }
}