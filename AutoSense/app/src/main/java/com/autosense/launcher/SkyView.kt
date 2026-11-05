package com.autosense.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Cómo se ve el cielo según el código de clima de Open-Meteo y si es de día o de noche. */
data class SkyLook(
    val isDay: Boolean,
    /** 0 = despejado, 1 = cubierto. */
    val clouds: Float,
    /** 0 = sin lluvia, 1 = lluvia ligera, 2 = lluvia fuerte. */
    val rain: Int = 0,
    val storm: Boolean = false,
    val fog: Boolean = false,
    val snow: Boolean = false
) {
    val wet get() = rain > 0 || storm

    companion object {
        fun of(code: Int, isDay: Boolean): SkyLook = when (code) {
            0 -> SkyLook(isDay, 0f)
            1 -> SkyLook(isDay, 0.2f)
            2 -> SkyLook(isDay, 0.5f)
            3 -> SkyLook(isDay, 0.95f)
            45, 48 -> SkyLook(isDay, 0.7f, fog = true)
            51, 53, 55, 56, 57, 61, 80 -> SkyLook(isDay, 0.9f, rain = 1)
            63, 65, 66, 67, 81, 82 -> SkyLook(isDay, 1f, rain = 2)
            71, 73, 75, 77, 85, 86 -> SkyLook(isDay, 0.9f, snow = true)
            95, 96, 99 -> SkyLook(isDay, 1f, rain = 2, storm = true)
            else -> SkyLook(isDay, 0.5f)
        }
    }
}

/** Piezas de dibujo compartidas por el fondo y el ícono del clima (nubes, sol, luna, gotas). */
object SkyArt {

    /**
     * Nube volumétrica: muchas bolitas difuminadas con base plana y sombra abajo.
     * Se dibuja una sola vez a media resolución; al ampliarla con filtro queda suave, sin pixelarse.
     */
    fun makeCloud(widthPx: Int, seed: Int, light: Int, shade: Int): Bitmap {
        val w = max(16, widthPx / 2)
        val h = (w * 0.55f).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val rnd = Random(seed)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val base = h * 0.78f
        val puffs = 13
        for (i in 0 until puffs) {
            val t = (i + rnd.nextFloat() * 0.6f) / puffs
            val x = w * (0.14f + 0.72f * t)
            // Más altas y grandes al centro: forma de cúmulo
            val bulge = sin(t * PI).toFloat()
            val r = h * (0.16f + 0.2f * bulge + rnd.nextFloat() * 0.08f)
            val y = base - r * (0.45f + 0.5f * bulge) + rnd.nextFloat() * h * 0.05f
            paint.shader = RadialGradient(x, y, r, intArrayOf(light, light, light and 0x00FFFFFF),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, paint)
        }
        // Sombra en la parte de abajo: da volumen
        paint.shader = LinearGradient(0f, h * 0.3f, 0f, h.toFloat(), intArrayOf(shade and 0x00FFFFFF, shade),
            null, Shader.TileMode.CLAMP)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        return bmp
    }

    /** Resplandor del sol (se dibuja como círculo con este pincel, radio = 4.2 × sol). */
    fun sunGlow(cx: Float, cy: Float, r: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(cx, cy, r * 4.2f, intArrayOf(0xF0FFFBE6.toInt(), 0x99FFF0B8.toInt(), 0x33FFE29A, 0x00FFE29A),
            floatArrayOf(0f, 0.24f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    fun sunDisc(cx: Float, cy: Float, r: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(cx - r * 0.2f, cy - r * 0.2f, r * 1.25f,
            intArrayOf(0xFFFFFFFA.toInt(), 0xFFFFF4B0.toInt(), 0xFFFFD25A.toInt(), 0xFFFFB42E.toInt()),
            floatArrayOf(0f, 0.35f, 0.75f, 1f), Shader.TileMode.CLAMP)
    }

    fun moonGlow(cx: Float, cy: Float, r: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(cx, cy, r * 3.5f, intArrayOf(0x55BFD3FF, 0x18A9C1FF, 0x00A9C1FF),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
    }

    fun moonFill(cx: Float, cy: Float, r: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(cx - r * 0.3f, cy - r * 0.2f, r * 1.3f,
            intArrayOf(0xFFFFFBEF.toInt(), 0xFFE6E4DA.toInt(), 0xFFB9BCC8.toInt()),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    }

    /** Luna creciente: un círculo menos otro desplazado. */
    fun moonPath(cx: Float, cy: Float, r: Float): Path {
        val full = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        val bite = Path().apply { addCircle(cx + r * 0.5f, cy - r * 0.3f, r * 0.9f, Path.Direction.CW) }
        full.op(bite, Path.Op.DIFFERENCE)
        return full
    }

    /** Gota de agua sobre el vidrio: borde oscuro arriba, refracción clara abajo y un brillo. */
    fun makeDrop(radiusPx: Int): Bitmap {
        val r = max(3, radiusPx).toFloat()
        val size = (r * 2 + 2).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(cx, cx + r * 0.25f, r, intArrayOf(0x14FFFFFF, 0x26FFFFFF, 0x66FFFFFF),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cx, r, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.16f
        val oval = RectF(cx - r * 0.9f, cx - r * 0.9f, cx + r * 0.9f, cx + r * 0.9f)
        p.color = 0x50000000
        c.drawArc(oval, 200f, 140f, false, p)
        p.color = 0xA0FFFFFF.toInt()
        p.strokeWidth = r * 0.12f
        c.drawArc(oval, 30f, 120f, false, p)
        p.style = Paint.Style.FILL
        p.color = 0xD8FFFFFF.toInt()
        c.drawCircle(cx - r * 0.35f, cx - r * 0.4f, r * 0.17f, p)
        return bmp
    }
}

/**
 * Fondo animado de la pantalla "Reloj y clima": cielo con degradado según la hora y el clima,
 * sol con resplandor, luna y estrellas de noche, nubes que avanzan despacio, lluvia, gotas sobre
 * el vidrio, neblina, nieve y relámpagos. Todo con dibujos ligeros (degradados y nubes
 * pre-dibujadas) a 20–30 cuadros por segundo, y se detiene por completo cuando no se ve.
 */
class SkyView(context: Context) : View(context) {

    private var look = SkyLook(true, 0.2f)
    private var running = false
    private var lastNanos = 0L
    private var time = 0f
    private val density = resources.displayMetrics.density
    private val rnd = Random(7)

    private val skyPaint = Paint()
    private val topShade = Paint()
    private val bottomShade = Paint()
    private var glowPaint: Paint? = null
    private var bodyPaint: Paint? = null
    private var moon: Path? = null
    private var sunX = 0f
    private var sunY = 0f
    private var sunR = 0f
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private class Cloud(val bmp: Int, var x: Float, val y: Float, val w: Float, val h: Float, val speed: Float, val alpha: Int)
    private var cloudBitmaps: List<Bitmap> = emptyList()
    private var clouds: List<Cloud> = emptyList()
    private var fogBanks: List<Cloud> = emptyList()

    private var stars = FloatArray(0)            // x, y, radio, fase por estrella
    private var rain = FloatArray(0)             // x, y, largo, velocidad
    private var rainLines = FloatArray(0)
    private val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private var drops = FloatArray(0)            // x, y, tamaño (0-2), velocidad de deslizamiento
    private var dropSprites: List<Bitmap> = emptyList()
    private var snow = FloatArray(0)             // x, y, radio, velocidad
    private var nextFlash = 0f
    private var flash = 0f

    fun setLook(newLook: SkyLook) {
        if (newLook == look) return
        look = newLook
        rebuild()
        invalidate()
    }

    /** Anima solo mientras la pantalla está visible (el ritmo lo marca la pantalla con [frameMs]). */
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
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val l = look

        // Cielo: azul intenso de día despejado, gris azulado al nublarse, oscuro de noche o con tormenta
        val colors = when {
            l.storm -> intArrayOf(0xFF161D27.toInt(), 0xFF26303D.toInt(), 0xFF3B4653.toInt())
            !l.isDay && l.wet -> intArrayOf(0xFF0A1018.toInt(), 0xFF18212D.toInt(), 0xFF2A3644.toInt())
            !l.isDay -> blend(intArrayOf(0xFF040A1E.toInt(), 0xFF0B1D45.toInt(), 0xFF1E3B6E.toInt()),
                intArrayOf(0xFF0D141E.toInt(), 0xFF1A2431.toInt(), 0xFF2E3A49.toInt()), l.clouds)
            l.wet -> intArrayOf(0xFF33465A.toInt(), 0xFF56697C.toInt(), 0xFF7C8B99.toInt())
            else -> blend(intArrayOf(0xFF1458BF.toInt(), 0xFF3C8DE6.toInt(), 0xFF9BCBF4.toInt()),
                intArrayOf(0xFF52708F.toInt(), 0xFF7C93AA.toInt(), 0xFFAEBDCB.toInt()), l.clouds)
        }
        skyPaint.shader = LinearGradient(0f, 0f, 0f, h, colors, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        // Sombras arriba y abajo para que el texto blanco siempre se lea
        topShade.shader = LinearGradient(0f, 0f, 0f, h * 0.28f, 0x55000000, 0x00000000, Shader.TileMode.CLAMP)
        bottomShade.shader = LinearGradient(0f, h * 0.55f, 0f, h, 0x00000000, 0x77000000, Shader.TileMode.CLAMP)

        // Sol o luna arriba a la derecha (no compiten con el reloj del centro)
        val portrait = h > w
        sunR = min(w, h) * (if (portrait) 0.09f else 0.07f)
        sunX = w * (if (portrait) 0.78f else 0.87f)
        sunY = h * (if (portrait) 0.15f else 0.19f)
        val showBody = !l.wet && !l.snow && !l.fog && l.clouds < 0.9f
        glowPaint = null; bodyPaint = null; moon = null
        if (showBody && l.isDay) {
            glowPaint = SkyArt.sunGlow(sunX, sunY, sunR)
            bodyPaint = SkyArt.sunDisc(sunX, sunY, sunR)
        } else if (showBody) {
            glowPaint = SkyArt.moonGlow(sunX, sunY, sunR * 0.8f)
            bodyPaint = SkyArt.moonFill(sunX, sunY, sunR * 0.8f)
            moon = SkyArt.moonPath(sunX, sunY, sunR * 0.8f)
        }

        // Estrellas (de noche, menos cuanto más nublado)
        val starCount = if (l.isDay || l.wet) 0 else (90 * (1f - l.clouds)).toInt()
        stars = FloatArray(starCount * 4)
        for (i in 0 until starCount) {
            stars[i * 4] = rnd.nextFloat() * w
            stars[i * 4 + 1] = rnd.nextFloat() * h * 0.7f
            stars[i * 4 + 2] = density * (0.5f + rnd.nextFloat() * 1.1f)
            stars[i * 4 + 3] = rnd.nextFloat() * 6.28f
        }

        // Nubes: tres formas pre-dibujadas que se reutilizan en distintos tamaños y capas
        cloudBitmaps.forEach { it.recycle() }
        val (light, shade) = when {
            l.storm -> 0xFF9EA7B2.toInt() to 0xFF2A3038.toInt()
            l.wet -> 0xFFC9D0D8.toInt() to 0xFF4D5866.toInt()
            !l.isDay -> 0xFFAEB9CF.toInt() to 0xFF1E2638.toInt()
            else -> 0xFFFFFFFF.toInt() to 0xFF8A9AB0.toInt()
        }
        // Pocas nubes grandes en vez de muchas encimadas: la GPU de la tablet pinta cada píxel menos veces
        val count = if (l.clouds < 0.05f && !l.fog) 0 else (2 + l.clouds * 3.2f).toInt()
        // Las nubes se dibujan casi al tamaño en que se muestran: suaves pero con forma definida
        val cloudBase = (max(w, h) * 0.9f).toInt()
        cloudBitmaps = if (count == 0 && !l.fog) emptyList() else (0 until 3).map { SkyArt.makeCloud(cloudBase, 11 + it * 7, light, shade) }
        val nightDim = if (l.isDay) 1f else 0.6f
        clouds = (0 until count).map { i ->
            val near = i >= count / 2
            val cw = w * (if (near) 0.5f + rnd.nextFloat() * 0.3f else 0.28f + rnd.nextFloat() * 0.2f) * (if (portrait) 1.25f else 1f)
            Cloud(
                bmp = i % 3,
                x = rnd.nextFloat() * (w + cw) - cw,
                y = h * (if (l.wet || l.clouds > 0.8f) -0.08f + rnd.nextFloat() * 0.4f else 0.02f + rnd.nextFloat() * 0.45f),
                w = cw, h = cw * 0.55f,
                speed = density * (if (near) 9f else 4f) * (if (l.storm) 2.2f else 1f),
                alpha = ((if (near) 245 else 190) * nightDim).toInt()
            )
        }
        fogBanks = if (!l.fog) emptyList() else (0 until 3).map { i ->
            Cloud(i, -w * 0.4f + i * w * 0.3f, h * (0.3f + i * 0.2f), w * 1.8f, h * 0.28f,
                density * (3f + i * 2f), (if (l.isDay) 120 else 70))
        }

        // Lluvia: trazos finos que caen + gotas pegadas al vidrio
        val rainCount = when { l.storm -> 170; l.rain == 2 -> 140; l.rain == 1 -> 70; else -> 0 }
        rain = FloatArray(rainCount * 4)
        rainLines = FloatArray(rainCount * 4)
        for (i in 0 until rainCount) {
            rain[i * 4] = rnd.nextFloat() * w
            rain[i * 4 + 1] = rnd.nextFloat() * h
            rain[i * 4 + 2] = density * (14f + rnd.nextFloat() * 18f)
            rain[i * 4 + 3] = density * (500f + rnd.nextFloat() * 400f)
        }
        rainPaint.strokeWidth = density * 1.1f
        rainPaint.color = if (l.isDay) 0x55FFFFFF else 0x40C8D6FF
        dropSprites.forEach { it.recycle() }
        dropSprites = if (rainCount == 0) emptyList() else listOf(4f, 7f, 11f).map { SkyArt.makeDrop((it * density).toInt()) }
        val dropCount = if (rainCount == 0) 0 else if (l.rain == 2 || l.storm) 26 else 18
        drops = FloatArray(dropCount * 4)
        for (i in 0 until dropCount) spawnDrop(i, w, h, anywhere = true)

        // Nieve
        val flakes = if (l.snow) 110 else 0
        snow = FloatArray(flakes * 4)
        for (i in 0 until flakes) {
            snow[i * 4] = rnd.nextFloat() * w
            snow[i * 4 + 1] = rnd.nextFloat() * h
            snow[i * 4 + 2] = density * (1.2f + rnd.nextFloat() * 2.2f)
            snow[i * 4 + 3] = density * (18f + rnd.nextFloat() * 30f)
        }
        nextFlash = time + 4f + rnd.nextFloat() * 6f
    }

    private fun spawnDrop(i: Int, w: Float, h: Float, anywhere: Boolean) {
        drops[i * 4] = rnd.nextFloat() * w
        drops[i * 4 + 1] = if (anywhere) rnd.nextFloat() * h else -20f * density
        drops[i * 4 + 2] = rnd.nextInt(3).toFloat()
        // Una de cada cuatro gotas resbala despacio por el vidrio
        drops[i * 4 + 3] = if (rnd.nextFloat() < 0.25f) density * (10f + rnd.nextFloat() * 25f) else 0f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceAtMost(0.1f)
        lastNanos = now
        time += dt
        val l = look

        canvas.drawRect(0f, 0f, w, h, skyPaint)

        // Estrellas que titilan suavemente
        for (i in 0 until stars.size / 4) {
            val a = 0.55f + 0.45f * sin(time * 1.3f + stars[i * 4 + 3])
            dot.color = ((a * 220).toInt() shl 24) or 0xFFFFFF
            canvas.drawCircle(stars[i * 4], stars[i * 4 + 1], stars[i * 4 + 2], dot)
        }

        // Sol (con un resplandor que respira muy despacio) o luna
        glowPaint?.let { glow ->
            glow.alpha = (215 + 40 * sin(time * 0.6f)).toInt()
            canvas.drawCircle(sunX, sunY, sunR * 4.2f, glow)
        }
        bodyPaint?.let { body ->
            val m = moon
            if (m != null) canvas.drawPath(m, body) else canvas.drawCircle(sunX, sunY, sunR, body)
        }

        // Nubes que avanzan con un poco de paralaje
        for (c in clouds) {
            c.x += c.speed * dt
            if (c.x > w) c.x = -c.w
            bitmapPaint.alpha = c.alpha
            rect.set(c.x, c.y, c.x + c.w, c.y + c.h)
            canvas.drawBitmap(cloudBitmaps[c.bmp], null, rect, bitmapPaint)
        }
        for (f in fogBanks) {
            f.x += f.speed * dt
            if (f.x > w * 0.2f) f.x = -w * 0.9f
            bitmapPaint.alpha = f.alpha
            rect.set(f.x, f.y, f.x + f.w, f.y + f.h)
            canvas.drawBitmap(cloudBitmaps[f.bmp], null, rect, bitmapPaint)
        }

        // Lluvia inclinada
        val rainCount = rain.size / 4
        if (rainCount > 0) {
            for (i in 0 until rainCount) {
                var y = rain[i * 4 + 1] + rain[i * 4 + 3] * dt
                var x = rain[i * 4] + rain[i * 4 + 3] * dt * 0.18f
                if (y > h) { y = -rain[i * 4 + 2]; x = rnd.nextFloat() * w }
                if (x > w) x -= w
                rain[i * 4] = x; rain[i * 4 + 1] = y
                val len = rain[i * 4 + 2]
                rainLines[i * 4] = x; rainLines[i * 4 + 1] = y
                rainLines[i * 4 + 2] = x + len * 0.18f; rainLines[i * 4 + 3] = y + len
            }
            canvas.drawLines(rainLines, rainPaint)
        }

        // Nieve que cae meciéndose
        for (i in 0 until snow.size / 4) {
            var y = snow[i * 4 + 1] + snow[i * 4 + 3] * dt
            if (y > h) { y = -5f; snow[i * 4] = rnd.nextFloat() * w }
            snow[i * 4 + 1] = y
            dot.color = 0xDDFFFFFF.toInt()
            canvas.drawCircle(snow[i * 4] + sin(time + i) * density * 6f, y, snow[i * 4 + 2], dot)
        }

        // Relámpagos: destello doble cada tantos segundos
        if (l.storm) {
            if (time > nextFlash) {
                flash = 1f
                nextFlash = time + 6f + rnd.nextFloat() * 9f
            }
            if (flash > 0f) {
                val pulse = if (flash > 0.7f || flash in 0.35f..0.5f) flash else flash * 0.3f
                canvas.drawColor(((pulse * 120).toInt() shl 24) or 0xE6EEFF)
                flash = (flash - dt * 2.2f).coerceAtLeast(0f)
            }
        }

        canvas.drawRect(0f, 0f, w, h * 0.28f, topShade)
        canvas.drawRect(0f, h * 0.55f, w, h, bottomShade)

        // Gotas sobre el vidrio (encima de todo, como si estuvieran en la pantalla)
        for (i in 0 until drops.size / 4) {
            val speed = drops[i * 4 + 3]
            if (speed > 0f) {
                drops[i * 4 + 1] += speed * dt
                if (drops[i * 4 + 1] > h) spawnDrop(i, w, h, anywhere = false)
            }
            val sprite = dropSprites[drops[i * 4 + 2].toInt()]
            canvas.drawBitmap(sprite, drops[i * 4], drops[i * 4 + 1], bitmapPaint.apply { alpha = 255 })
        }

    }

    /**
     * Cada cuánto conviene redibujar: más fluido con lluvia, nieve o tormenta; más tranquilo (y
     * ahorrador) con cielo calmado. Quien muestra el cielo marca el ritmo de TODAS las animaciones
     * con este valor, para que la pantalla se dibuje una sola vez por cuadro.
     */
    val frameMs: Long get() = if (rain.isNotEmpty() || snow.isNotEmpty() || look.storm) FRAME_WET_MS else FRAME_CALM_MS

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    companion object {
        /** ~25 cuadros por segundo con lluvia, nieve o tormenta; ~15 con cielo calmado (nubes lentas). */
        private const val FRAME_WET_MS = 40L
        private const val FRAME_CALM_MS = 66L
    }

    private fun blend(a: IntArray, b: IntArray, t: Float) = IntArray(a.size) { i -> mix(a[i], b[i], t) }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = ((a shr s and 0xFF) * (1 - t) + (b shr s and 0xFF) * t).toInt() shl s
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }
}
