package com.autosense.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Velocímetro de tablero: arco de 0 a 200 km/h con rayitas de marcación y números,
 * aguja que gira suave hasta la velocidad y el valor digital abajo.
 * Se dibuja a mano para que no pese en la tablet.
 */
class SpeedGaugeView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    /** Velocidad en km/h. -1 significa sin señal GPS y muestra "--". */
    var speed: Int = -1
        set(value) {
            field = value
            contentDescription = if (value < 0) "Velocidad sin señal GPS" else "Velocidad $value kilómetros por hora"
            invalidate()
        }

    // Valor que muestra la aguja; se acerca a [speed] poco a poco para que gire suave
    private var needleSpeed = 0f

    private val arcBounds = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0x33FFFFFF
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFF4DA3FF.toInt()
    }

    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        color = 0xE6FFFFFF.toInt()
    }

    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        color = 0x80B0C4DE.toInt()
    }

    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0C4DE.toInt()
        textAlign = Paint.Align.CENTER
    }

    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFF4D4D.toInt()
    }

    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD9DEE5.toInt() }
    private val hubCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A2333.toInt() }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0C4DE.toInt()
        textAlign = Paint.Align.CENTER
    }

    // Textos y grosor proporcionales al tamaño, para que se lea bien a cualquier escala
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = minOf(w, h).toFloat()
        trackPaint.strokeWidth = size * 0.035f
        progressPaint.strokeWidth = size * 0.035f
        majorTickPaint.strokeWidth = size * 0.014f
        minorTickPaint.strokeWidth = size * 0.008f
        numberPaint.textSize = size * 0.065f
        needlePaint.strokeWidth = size * 0.022f
        valuePaint.textSize = size * 0.17f
        unitPaint.textSize = size * 0.06f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val radius = size / 2f - trackPaint.strokeWidth
        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Arco de fondo y avance azul hasta la aguja
        canvas.drawArc(arcBounds, START_ANGLE, SWEEP_ANGLE, false, trackPaint)
        if (speed >= 0 && needleSpeed > 0.5f) {
            canvas.drawArc(arcBounds, START_ANGLE, SWEEP_ANGLE * needleSpeed / MAX_SPEED, false, progressPaint)
        }

        // Rayitas: largas con número cada 20 km/h, cortas cada 10
        for (kmh in 0..MAX_SPEED step 10) {
            val a = Math.toRadians((START_ANGLE + SWEEP_ANGLE * kmh / MAX_SPEED).toDouble())
            val major = kmh % 20 == 0
            val outer = radius - trackPaint.strokeWidth * 1.1f
            val inner = outer - size * (if (major) 0.075f else 0.04f)
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            canvas.drawLine(cx + ca * inner, cy + sa * inner, cx + ca * outer, cy + sa * outer,
                if (major) majorTickPaint else minorTickPaint)
            if (major) {
                val r = inner - size * 0.06f
                canvas.drawText(kmh.toString(), cx + ca * r, cy + sa * r + numberPaint.textSize * 0.35f, numberPaint)
            }
        }

        // Valor digital abajo, entre los extremos del arco
        val text = if (speed < 0) "--" else speed.toString()
        canvas.drawText(text, cx, cy + radius * 0.62f, valuePaint)
        canvas.drawText("km/h", cx, cy + radius * 0.62f + unitPaint.textSize * 1.3f, unitPaint)

        // Aguja
        val angle = Math.toRadians((START_ANGLE + SWEEP_ANGLE * needleSpeed / MAX_SPEED).toDouble())
        val tip = radius - trackPaint.strokeWidth * 2.2f
        val tail = size * 0.06f
        canvas.drawLine(
            cx - cos(angle).toFloat() * tail, cy - sin(angle).toFloat() * tail,
            cx + cos(angle).toFloat() * tip, cy + sin(angle).toFloat() * tip,
            needlePaint
        )
        canvas.drawCircle(cx, cy, size * 0.045f, hubPaint)
        canvas.drawCircle(cx, cy, size * 0.02f, hubCenterPaint)

        // Animación de la aguja: solo redibuja mientras le falta llegar
        val target = speed.coerceIn(0, MAX_SPEED).toFloat()
        val diff = target - needleSpeed
        if (abs(diff) > 0.3f) {
            needleSpeed += diff * 0.18f
            postInvalidateOnAnimation()
        } else {
            needleSpeed = target
        }
    }

    companion object {
        // Arco de 270 grados, empezando abajo a la izquierda
        private const val START_ANGLE = 135f
        private const val SWEEP_ANGLE = 270f
        private const val MAX_SPEED = 200
    }
}
