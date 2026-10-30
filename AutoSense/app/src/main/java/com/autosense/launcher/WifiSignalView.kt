package com.autosense.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Ícono de señal Wi-Fi con 4 niveles (abanico de arcos). Los arcos activos van en blanco y
 * los demás tenues; sin conexión se dibuja tenue y tachado.
 */
class WifiSignalView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    /** 0..4 según la intensidad; -1 = sin conexión Wi-Fi. */
    var level: Int = -1
        set(value) {
            if (field == value) return
            field = value
            contentDescription = if (value < 0) "Sin Wi-Fi" else "Wi-Fi, señal $value de 4"
            invalidate()
        }

    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val slash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF5252.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        val cx = width / 2f
        val bottom = height / 2f + size * 0.42f
        // Abanico de 90° hacia arriba: punto + 3 arcos de grosor creciente
        for (i in 0..3) {
            val paint = if (level > i) on else off
            if (i == 0) {
                canvas.drawCircle(cx, bottom - size * 0.08f, size * 0.09f, paint)
                continue
            }
            val r = size * (0.08f + 0.27f * i)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * 0.11f
            paint.strokeCap = Paint.Cap.ROUND
            oval.set(cx - r, bottom - r, cx + r, bottom + r)
            canvas.drawArc(oval, 225f, 90f, false, paint)
            paint.style = Paint.Style.FILL
        }
        if (level < 0) {
            slash.strokeWidth = size * 0.1f
            canvas.drawLine(cx - size * 0.38f, bottom - size * 0.82f, cx + size * 0.38f, bottom - size * 0.02f, slash)
        }
    }
}
