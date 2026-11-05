package com.autosense.launcher

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Panel de vidrio con relieve (glassmorphism + neumorphism): vidrio translúcido con brillo arriba,
 * borde de luz, y una sombra suave difuminada debajo que lo despega del fondo.
 * La sombra se pre-dibuja una vez por tamaño (la GPU de la tablet no difumina formas en vivo),
 * y queda dentro de los límites del panel: [inset] es el margen que ocupa.
 */
class GlassPanelDrawable(
    private val radius: Float,
    private val inset: Int,
    /** Vidrio ahumado: deja ver el cielo detrás y mantiene legible el texto blanco sobre nubes claras. */
    private val tint: Int = 0x38102030
) : Drawable() {

    private val panel = RectF()
    private var shadow: Bitmap? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var alphaValue = 255

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val w = bounds.width()
        val h = bounds.height()
        if (w <= inset * 2 || h <= inset * 2) return
        panel.set(bounds.left + inset.toFloat(), bounds.top + inset * 0.6f, bounds.right - inset.toFloat(), bounds.bottom - inset * 1.4f)

        shadow?.recycle()
        shadow = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val local = RectF(panel).apply { offset(-bounds.left.toFloat(), -bounds.top.toFloat()) }
            // Sombra oscura abajo (profundidad)
            p.maskFilter = BlurMaskFilter(inset * 0.9f, BlurMaskFilter.Blur.NORMAL)
            p.color = 0x55000000
            c.drawRoundRect(RectF(local).apply { offset(0f, inset * 0.45f) }, radius, radius, p)
            // Luz tenue arriba (relieve)
            p.color = 0x14FFFFFF
            c.drawRoundRect(RectF(local).apply { offset(0f, -inset * 0.25f) }, radius, radius, p)
        }
        fill.color = tint
        shine.shader = LinearGradient(0f, panel.top, 0f, panel.bottom,
            intArrayOf(0x30FFFFFF, 0x0AFFFFFF, 0x06000000), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        edge.strokeWidth = maxOf(1f, inset / 12f)
        edge.shader = LinearGradient(0f, panel.top, 0f, panel.bottom,
            intArrayOf(0x80FFFFFF.toInt(), 0x22FFFFFF, 0x10FFFFFF), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
    }

    /** Reflejo de luz que cruza el vidrio en diagonal (0..1 = recorrido; fuera de ese rango, apagado). */
    var sheen = -1f
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = android.graphics.Path()

    override fun draw(canvas: Canvas) {
        shadow?.let { canvas.drawBitmap(it, bounds.left.toFloat(), bounds.top.toFloat(), bitmapPaint) }
        canvas.drawRoundRect(panel, radius, radius, fill)
        canvas.drawRoundRect(panel, radius, radius, shine)
        if (sheen in 0f..1f && panel.width() > 0) {
            val band = panel.width() * 0.35f
            val x = panel.left - band + (panel.width() + band * 2) * sheen
            sheenPaint.shader = LinearGradient(x, panel.top, x + band, panel.bottom,
                intArrayOf(0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            clip.reset()
            clip.addRoundRect(panel, radius, radius, android.graphics.Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawRect(panel, sheenPaint)
            canvas.restore()
        }
        canvas.drawRoundRect(panel, radius, radius, edge)
    }

    /** El contenido queda dentro del vidrio, no sobre la sombra. */
    override fun getPadding(padding: Rect): Boolean {
        padding.set(inset, (inset * 0.6f).toInt(), inset, (inset * 1.4f).toInt())
        return true
    }

    override fun setAlpha(alpha: Int) {
        // El vidrio ya es translúcido por diseño: se conserva su transparencia propia
        alphaValue = alpha
    }

    override fun getAlpha() = alphaValue
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
