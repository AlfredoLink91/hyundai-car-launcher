package com.autosense.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Ícono de batería dibujado a mano: verde, ámbar o rojo según la carga; azul al cargar. */
class BatteryView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var level = 100
    private var charging = false

    private val density = resources.displayMetrics.density
    private val body = RectF()
    private val fill = RectF()
    private val cap = RectF()

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0xCCFFFFFF.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }

    fun update(level: Int, charging: Boolean) {
        this.level = level.coerceIn(0, 100)
        this.charging = charging
        invalidate()
    }

    /** Color según la carga, para usarlo también en el texto del porcentaje. */
    fun levelColor(): Int = when {
        charging -> 0xFF4DA3FF.toInt()
        level <= 20 -> 0xFFFF5252.toInt()
        level <= 50 -> 0xFFFFC93C.toInt()
        else -> 0xFF4CD964.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = outlinePaint.strokeWidth
        val capWidth = 3 * density
        body.set(stroke, stroke, width - capWidth - stroke, height - stroke)
        val radius = 3 * density
        canvas.drawRoundRect(body, radius, radius, outlinePaint)

        cap.set(body.right + density, height * 0.32f, width.toFloat(), height * 0.68f)
        canvas.drawRoundRect(cap, density, density, capPaint)

        val inset = 2.5f * density
        val maxWidth = body.width() - inset * 2
        fill.set(body.left + inset, body.top + inset, body.left + inset + maxWidth * level / 100f, body.bottom - inset)
        fillPaint.color = levelColor()
        canvas.drawRoundRect(fill, density, density, fillPaint)
    }
}
