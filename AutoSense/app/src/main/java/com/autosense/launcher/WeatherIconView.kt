package com.autosense.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Ícono del clima con el mismo estilo realista del fondo: sol con resplandor y rayos que giran
 * despacio, luna que brilla, nubes que flotan, lluvia que cae, rayo que destella, nieve y neblina.
 * Se anima al mismo ritmo que el cielo (la pantalla lo redibuja junto con él) y solo mientras se ve.
 */
class WeatherIconView(context: Context) : View(context) {

    private var look = SkyLook(true, 0f)
    private var cloudLight: Bitmap? = null
    private var cloudDark: Bitmap? = null
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val bolt = Path()

    // Sol o luna (pinceles preparados por tamaño, no en cada cuadro)
    private var bodyX = 0f
    private var bodyY = 0f
    private var bodyR = 0f
    private var glowPaint: Paint? = null
    private var bodyPaint: Paint? = null
    private var moon: Path? = null
    private var alone = false

    private var running = false
    private var time = 0f
    private var lastNanos = 0L

    fun setLook(newLook: SkyLook) {
        if (newLook == look) return
        look = newLook
        rebuild()
        invalidate()
    }

    fun setRunning(on: Boolean) {
        running = on
        lastNanos = 0L
        if (on) invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun rebuild() {
        val s = min(width, height)
        if (s <= 0) return
        cloudLight?.recycle(); cloudDark?.recycle()
        val (light, shade) = when {
            look.storm -> 0xFFA9B2BD.toInt() to 0xFF39414C.toInt()
            look.wet -> 0xFFE3E8EE.toInt() to 0xFF6B7888.toInt()
            !look.isDay -> 0xFFD3DBEA.toInt() to 0xFF56627A.toInt()
            else -> 0xFFFFFFFF.toInt() to 0xFF9AA9BE.toInt()
        }
        // Al doble del tamaño (la nube se dibuja a media resolución): queda nítida
        cloudLight = SkyArt.makeCloud(s * 2, 5, light, shade)
        cloudDark = SkyArt.makeCloud(s * 2, 9, mixDark(light), shade)

        val l = look
        val sunny = !l.wet && !l.snow && !l.fog && l.clouds < 0.9f
        glowPaint = null; bodyPaint = null; moon = null
        if (sunny) {
            alone = l.clouds < 0.1f
            bodyX = if (alone) s * 0.5f else s * 0.36f
            bodyY = bodyX
            bodyR = if (alone) s * 0.22f else s * 0.19f
            if (l.isDay) {
                glowPaint = SkyArt.sunGlow(bodyX, bodyY, bodyR * 0.5f)
                bodyPaint = SkyArt.sunDisc(bodyX, bodyY, bodyR)
            } else {
                glowPaint = SkyArt.moonGlow(bodyX, bodyY, bodyR * 0.6f)
                bodyPaint = SkyArt.moonFill(bodyX, bodyY, bodyR)
                moon = SkyArt.moonPath(bodyX, bodyY, bodyR)
            }
        }
    }

    private fun mixDark(c: Int) = (c and 0xFF000000.toInt()) or ((c and 0xFEFEFE) shr 1) + 0x303030

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        if (s <= 0) return
        val now = System.nanoTime()
        if (lastNanos != 0L) time += ((now - lastNanos) / 1e9f).coerceAtMost(0.1f)
        lastNanos = now

        canvas.save()
        canvas.translate((width - s) / 2, (height - s) / 2)
        val l = look
        val hasRain = l.wet

        // Sol o luna: el resplandor "respira"; el sol solo tiene rayos que giran muy despacio
        glowPaint?.let { glow ->
            val pulse = 1f + 0.07f * sin(time * 1.6f)
            canvas.save()
            canvas.scale(pulse, pulse, bodyX, bodyY)
            canvas.drawCircle(bodyX, bodyY, bodyR * 2.1f, glow)
            canvas.restore()
        }
        if (l.isDay && glowPaint != null && alone) {
            line.strokeWidth = s * 0.028f
            line.color = 0xB3FFD866.toInt()
            for (i in 0 until 8) {
                val a = time * 0.35f + i * (Math.PI / 4).toFloat()
                val r1 = bodyR * 1.3f
                val r2 = bodyR * (1.6f + 0.08f * sin(time * 2f + i))
                canvas.drawLine(bodyX + cos(a) * r1, bodyY + sin(a) * r1, bodyX + cos(a) * r2, bodyY + sin(a) * r2, line)
            }
        }
        bodyPaint?.let { body ->
            val m = moon
            if (m != null) canvas.drawPath(m, body) else canvas.drawCircle(bodyX, bodyY, bodyR, body)
        }

        // Nubes que flotan suavemente (la de atrás a otro ritmo: da profundidad)
        val cloudy = l.clouds >= 0.1f || hasRain || l.snow || l.fog
        if (cloudy) {
            if (l.clouds > 0.8f || hasRain) {
                cloudDark?.let { draw(canvas, it, s * 0.02f + sin(time * 0.5f) * s * 0.025f, s * 0.08f, s * 0.78f) }
            }
            cloudLight?.let {
                draw(canvas, it, s * 0.14f + sin(time * 0.7f + 1f) * s * 0.03f,
                    (if (hasRain || l.snow) s * 0.18f else s * 0.3f) + sin(time * 1.1f) * s * 0.012f, s * 0.84f)
            }
        }

        // Lluvia que cae en bucle bajo la nube
        if (hasRain) {
            line.strokeWidth = s * 0.032f
            line.color = 0xE07FC4FF.toInt()
            val count = if (l.rain == 2 || l.storm) 5 else 3
            val fall = (time * (if (l.rain == 2 || l.storm) 1.6f else 1.1f)) % 1f
            for (i in 0 until count) {
                val phase = (fall + i * 0.37f) % 1f
                val x = s * (0.3f + i * 0.4f / (count - 1).coerceAtLeast(1)) - phase * s * 0.05f
                val y = s * (0.68f + phase * 0.22f)
                line.alpha = (230 * (1f - phase)).toInt()
                canvas.drawLine(x, y, x - s * 0.04f, y + s * 0.12f, line)
            }
        }
        // Rayo que destella de vez en cuando
        if (l.storm) {
            val t = time % 3.2f
            fill.color = 0xFFFFD34D.toInt()
            fill.alpha = if (t < 0.12f || t in 0.25f..0.34f) 255 else 90
            bolt.reset()
            bolt.moveTo(s * 0.56f, s * 0.55f); bolt.lineTo(s * 0.42f, s * 0.78f); bolt.lineTo(s * 0.52f, s * 0.78f)
            bolt.lineTo(s * 0.44f, s * 0.97f); bolt.lineTo(s * 0.66f, s * 0.7f); bolt.lineTo(s * 0.55f, s * 0.7f); bolt.close()
            canvas.drawPath(bolt, fill)
        }
        // Nieve que cae
        if (l.snow) {
            fill.color = 0xF0FFFFFF.toInt()
            for (i in 0 until 5) {
                val phase = (time * 0.5f + i * 0.21f) % 1f
                fill.alpha = (240 * (1f - phase)).toInt()
                canvas.drawCircle(s * (0.28f + i * 0.11f) + sin(time * 2f + i) * s * 0.02f, s * (0.7f + phase * 0.25f), s * 0.03f, fill)
            }
        }
        // Neblina que se desliza de lado a lado
        if (l.fog) {
            line.strokeWidth = s * 0.045f
            line.color = 0xC8E6EBF0.toInt()
            for (i in 0 until 3) {
                val dx = sin(time * 0.8f + i * 1.3f) * s * 0.05f
                canvas.drawLine(s * (0.18f + i * 0.05f) + dx, s * (0.72f + i * 0.1f), s * (0.82f - i * 0.04f) + dx, s * (0.72f + i * 0.1f), line)
            }
        }
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    private fun draw(canvas: Canvas, bmp: Bitmap, x: Float, y: Float, w: Float) {
        rect.set(x, y, x + w, y + w * bmp.height / bmp.width)
        canvas.drawBitmap(bmp, null, rect, bmpPaint)
    }
}
