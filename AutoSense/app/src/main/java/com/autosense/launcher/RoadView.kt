package com.autosense.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Carretera bajo el auto (modo conducción), alineada con la dirección hacia donde mira el auto.
 * Se dibuja "en el suelo" en metros (carril recto hacia adelante) y una matriz de perspectiva,
 * calculada con la misma cámara que renderizó los cuadros del auto, la proyecta en pantalla.
 * Las líneas del centro avanzan con la velocidad del GPS: quietas a 0 km/h y más rápidas al acelerar.
 * Solo se redibuja mientras hay movimiento, para no gastar procesador.
 */
class RoadView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    /** Velocidad en km/h; -1 o 0 = detenido. */
    var speed: Int = 0
        set(value) {
            field = value.coerceAtLeast(0)
            if (field > 0) postInvalidate()
        }

    private var travelled = 0f         // metros recorridos (mueve las líneas del centro)
    private var lastFrameNanos = 0L
    private val matrix = Matrix()
    private val dst = FloatArray(8)
    private val rect = RectF()

    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Asfalto: gris azulado cerca del auto y se desvanece a lo lejos
        shader = LinearGradient(0f, 0f, 0f, LENGTH,
            intArrayOf(0x00000000, 0xCC1B2431.toInt(), 0x99202B3A.toInt(), 0x00000000),
            FADE_STOPS, Shader.TileMode.CLAMP)
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, 0f, LENGTH,
            intArrayOf(0x00D9E6F5, 0x99D9E6F5.toInt(), 0x66D9E6F5, 0x00D9E6F5), FADE_STOPS, Shader.TileMode.CLAMP)
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, 0f, LENGTH,
            intArrayOf(0x00F2C94C, 0xE6F2C94C.toInt(), 0xAAF2C94C.toInt(), 0x00F2C94C), FADE_STOPS, Shader.TileMode.CLAMP)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f, intArrayOf(0x99000000.toInt(), 0x00000000), null, Shader.TileMode.CLAMP)
    }

    // Ubica el cuadro del auto igual que CarTurntableView (centrado, conservando proporción)
    // y arma la perspectiva suelo → pantalla para ese tamaño
    private fun updateMatrix(): Boolean {
        val car = (parent as? android.view.ViewGroup)?.findViewById<View>(R.id.car) ?: return false
        if (car.width == 0) return false
        val s = minOf(car.width / FRAME_W, car.height / FRAME_H)
        val ox = car.left - left + (car.width - FRAME_W * s) / 2
        val oy = car.top - top + (car.height - FRAME_H * s) / 2
        for (i in 0 until 4) {
            dst[i * 2] = ox + FRAME_PTS[i * 2] * s
            dst[i * 2 + 1] = oy + FRAME_PTS[i * 2 + 1] * s
        }
        return matrix.setPolyToPoly(GROUND_PTS, 0, dst, 0, 4)
    }

    // Bordes difuminados: la carretera se desvanece a los lados y abajo en vez de cortarse en seco
    private val edgeFadeX = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }
    private val edgeFadeY = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        edgeFadeX.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(0x00000000, 0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000),
            floatArrayOf(0f, 0.15f, 0.85f, 1f), Shader.TileMode.CLAMP)
        edgeFadeY.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!updateMatrix()) return
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.save()
        canvas.concat(matrix)

        // Dos carriles: el auto va en el derecho, con la línea amarilla a su izquierda
        rect.set(ROAD_LEFT, 0f, ROAD_RIGHT, LENGTH)
        canvas.drawRect(rect, roadPaint)
        rect.set(ROAD_LEFT, 0f, ROAD_LEFT + 0.15f, LENGTH)
        canvas.drawRect(rect, edgePaint)
        rect.set(ROAD_RIGHT - 0.15f, 0f, ROAD_RIGHT, LENGTH)
        canvas.drawRect(rect, edgePaint)

        // Línea del centro: 3 m pintados cada 9 m, avanzan hacia atrás del auto al moverse
        var y = -(travelled % DASH_GAP)
        while (y < LENGTH) {
            rect.set(CENTER_LINE - 0.08f, maxOf(y, 0f), CENTER_LINE + 0.08f, minOf(y + DASH_LEN, LENGTH))
            if (rect.bottom > rect.top) canvas.drawRect(rect, dashPaint)
            y += DASH_GAP
        }

        // Sombra del auto en el suelo: un poco más alargada cuanto más rápido
        val stretch = 1f + (speed.coerceAtMost(120) / 120f) * 0.2f
        canvas.save()
        canvas.translate(0f, CAR_CENTER_Y)
        canvas.scale(1.15f, 2.9f * stretch)
        canvas.drawCircle(0f, 0f, 1f, shadowPaint)
        canvas.restore()
        canvas.restore()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), edgeFadeX)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), edgeFadeY)
        canvas.restoreToCount(layer)

        // Avance: proporcional a la velocidad, independiente de los cuadros por segundo
        val now = System.nanoTime()
        if (speed > 0) {
            val dt = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / 1e9f).coerceAtMost(0.1f)
            travelled = (travelled + dt * speed / 3.6f) % 1_000f
            lastFrameNanos = now
            // ~30 cuadros por segundo: fluido a la vista y la mitad de trabajo para la tablet
            postInvalidateDelayed(FRAME_MS)
        } else {
            lastFrameNanos = 0L
        }
    }

    companion object {
        private const val FRAME_MS = 33L
        /** Medidas en metros sobre el suelo (0 = centro del auto): dos carriles de 3.6 m, 60 m hacia adelante. */
        private const val ROAD_RIGHT = 1.8f
        private const val CENTER_LINE = -1.8f
        private const val ROAD_LEFT = -5.4f
        private const val LENGTH = 60f
        /** Se desvanece detrás del auto (0–4 m, hasta el eje trasero) y a lo lejos. */
        private val FADE_STOPS = floatArrayOf(0f, 4f / LENGTH, 0.45f, 1f)
        /** Esquinas del rectángulo de referencia que se proyectó con la cámara del render. */
        private const val HALF_WIDTH = 3.2f
        private const val DASH_LEN = 3f
        private const val DASH_GAP = 9f
        /** Centro del auto: el inicio del carril está 4 m detrás del eje trasero (distancia entre ejes 2.57 m). */
        private const val CAR_CENTER_Y = 4f + 1.28f

        /** Tamaño de los cuadros del auto (assets/car360 y car_drive). */
        private const val FRAME_W = 720f
        private const val FRAME_H = 328f
        private val GROUND_PTS = floatArrayOf(-HALF_WIDTH, 0f, HALF_WIDTH, 0f, HALF_WIDTH, LENGTH, -HALF_WIDTH, LENGTH)
        /** Las mismas esquinas proyectadas por la cámara del render con el auto a 325° (CarTurntableView.FORWARD_FRAME). */
        private val FRAME_PTS = floatArrayOf(-818f, 403.5f, 195.4f, 1019.2f, 1313.4f, -81.5f, 1113.8f, -86.3f)
    }
}
