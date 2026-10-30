package com.autosense.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.Log
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Letra de la canción estilo karaoke: la línea que suena va resaltada al centro y las demás
 * se desvanecen arriba y abajo, avanzando con la posición de la canción.
 * Las letras sincronizadas vienen de LRCLIB (lrclib.net, gratis y sin cuenta). Si la canción
 * no tiene letra sincronizada, la vista se oculta y la portada ocupa ese espacio.
 */
class LyricsView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    /** Posición actual de la canción en milisegundos (null = no hay canción). */
    var positionProvider: (() -> Long?)? = null
    /** Si está sonando: mientras esté en pausa no se redibuja. */
    var playing = false
        set(value) {
            field = value
            if (value) invalidate()
        }

    /** False cuando no hay espacio (pantalla dividida angosta): la letra no se muestra. */
    var fits = true
        set(value) {
            field = value
            visibility = if (value && lines.isNotEmpty()) VISIBLE else GONE
        }

    private data class Line(val timeMs: Long, val text: String)

    private var lines: List<Line> = emptyList()
    private var layouts: List<StaticLayout> = emptyList()
    private var trackKey: String? = null
    private var scroll = 0f            // desplazamiento actual (animado hacia la línea que suena)
    private val main = Handler(Looper.getMainLooper())

    private val density = resources.displayMetrics.scaledDensity
    private val activePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 15 * density
        typeface = Typeface.DEFAULT_BOLD
    }
    private val idlePaint = TextPaint(activePaint).apply {
        color = 0xFFB0C4DE.toInt()
        typeface = Typeface.DEFAULT
    }

    /** Busca la letra de la canción; si es la misma que ya se muestra, no hace nada. */
    fun setTrack(artist: String?, title: String?, album: String?, durationMs: Long) {
        val key = "$artist|$title"
        if (key == trackKey) return
        trackKey = key
        lines = emptyList()
        layouts = emptyList()
        visibility = GONE
        if (artist.isNullOrBlank() || title.isNullOrBlank()) return
        cache[key]?.let { show(key, it); return }
        Thread {
            val found = try { fetch(artist, title, album, durationMs) } catch (e: Exception) {
                Log.w(TAG, "No se pudo obtener la letra: ${e.message}"); null
            }
            Log.i(TAG, "Letra de \"$title\": " + (found?.let { "${it.size} líneas" } ?: "no disponible"))
            if (found != null) cache[key] = found
            main.post { if (trackKey == key && found != null) show(key, found) }
        }.start()
    }

    private fun show(key: String, parsed: List<Line>) {
        if (trackKey != key || parsed.isEmpty()) return
        lines = parsed
        layouts = emptyList()
        scroll = 0f
        visibility = if (fits) VISIBLE else GONE
        invalidate()
    }

    // Cada línea se acomoda en varios renglones si no cabe a lo ancho; se prepara en negrita
    // (la que suena) y normal (las demás) una sola vez, para no rehacerlas en cada cuadro
    private var idleLayouts: List<StaticLayout> = emptyList()

    private fun buildLayouts() {
        val w = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        fun build(text: String, paint: TextPaint) = StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.05f)
            .build()
        layouts = lines.map { build(it.text, activePaint) }
        idleLayouts = lines.map { build(it.text, idlePaint) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layouts = emptyList()
    }

    override fun onDraw(canvas: Canvas) {
        if (lines.isEmpty()) return
        if (layouts.size != lines.size) buildLayouts()
        val position = positionProvider?.invoke() ?: return
        // Línea actual: la última que ya empezó (un poco antes, para que se lea a tiempo)
        val current = lines.indexOfLast { it.timeMs <= position + LEAD_MS }.coerceAtLeast(0)
        val gap = 10 * density

        // Centro de la línea actual = lo que hay antes + la mitad de ella misma
        var target = 0f
        for (i in 0 until current) target += layouts[i].height + gap
        target += layouts[current].height / 2f
        val settled = Math.abs(target - scroll) < 0.5f
        scroll = if (settled) target else scroll + (target - scroll) * 0.2f

        var y = height * 0.42f - scroll
        layouts.forEachIndexed { i, layout ->
            val h = layout.height
            if (y + h > 0 && y < height) {
                val distance = Math.abs(i - current)
                val paint = if (i == current) activePaint else idlePaint
                // Las líneas se desvanecen al alejarse de la que suena y cerca de los bordes
                // (una línea que no cabe completa en la orilla queda invisible, no cortada)
                val edge = minOf(y, height - (y + h)) / (height * 0.18f)
                paint.alpha = ((if (distance == 0) 255 else maxOf(60, 170 - distance * 40)) * edge.coerceIn(0f, 1f)).toInt()
                val drawn = if (i == current) layout else idleLayouts[i]
                canvas.save()
                canvas.translate(paddingLeft.toFloat(), y)
                drawn.draw(canvas)
                canvas.restore()
            }
            y += h + gap
        }

        // Animación del desplazamiento fluida; mientras suena, se revisa la línea 10 veces por segundo
        if (!settled) postInvalidateOnAnimation()
        else if (playing) postInvalidateDelayed(100)
    }

    companion object {
        private const val TAG = "HyundaiLyrics"
        private const val LEAD_MS = 250L
        private val cache = HashMap<String, List<Line>>()
        private val LRC_LINE = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")

        // LRCLIB: primero la búsqueda exacta (artista, título, álbum y duración); si no, por nombre y
        // luego por texto libre (con el título sin "- En Vivo", "(Remastered)", etc.).
        // Se prefiere la letra sincronizada; si solo hay letra normal, se reparte a lo largo de la canción.
        private fun fetch(artist: String, title: String, album: String?, durationMs: Long): List<Line>? {
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
            fun JSONObject.text(key: String) = optString(key).takeIf { it.isNotBlank() && it != "null" }
            val cleanTitle = title.replace(Regex("""\s*[(\[].*?[)\]]"""), "").substringBefore(" - ").trim().ifEmpty { title }
            var plain: String? = null

            val exact = "https://lrclib.net/api/get?artist_name=${enc(artist)}&track_name=${enc(title)}" +
                    (if (!album.isNullOrBlank()) "&album_name=${enc(album)}" else "") +
                    (if (durationMs > 0) "&duration=${durationMs / 1000}" else "")
            get(exact)?.let { JSONObject(it) }?.let { found ->
                found.text("syncedLyrics")?.let { return parse(it) }
                plain = found.text("plainLyrics")
            }
            val searches = listOf(
                "https://lrclib.net/api/search?artist_name=${enc(artist)}&track_name=${enc(cleanTitle)}",
                "https://lrclib.net/api/search?q=${enc("$cleanTitle $artist")}",
                "https://lrclib.net/api/search?track_name=${enc(cleanTitle)}"
            )
            for (url in searches) {
                val results = JSONArray(get(url) ?: continue)
                for (i in 0 until results.length()) {
                    val r = results.getJSONObject(i)
                    // En la búsqueda solo por título, se exige que el artista coincida
                    if (url.contains("?track_name=") && !r.optString("artistName").contains(artist.substringBefore(","), true)) continue
                    r.text("syncedLyrics")?.let { return parse(it) }
                    if (plain == null) plain = r.text("plainLyrics")
                }
            }
            return plain?.let { spread(it, durationMs) }
        }

        // Letra sin tiempos: cada línea se reparte de forma pareja entre el inicio y el final de la canción
        private fun spread(text: String, durationMs: Long): List<Line>? {
            val rows = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (rows.isEmpty() || durationMs <= 0) return null
            val start = durationMs * 0.06
            val step = (durationMs * 0.94 - start) / rows.size
            return rows.mapIndexed { i, row -> Line((start + i * step).toLong(), row) }
        }

        private fun get(url: String): String? {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", "Hyundai car launcher (Android)")
            return try {
                if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
            } finally {
                conn.disconnect()
            }
        }

        // Formato LRC: "[01:23.45] texto"; una línea puede traer varias marcas de tiempo
        private fun parse(lrc: String): List<Line> = lrc.lines().flatMap { raw ->
            val stamps = LRC_LINE.findAll(raw).toList()
            if (stamps.isEmpty()) return@flatMap emptyList<Line>()
            val text = raw.substring(stamps.last().range.last + 1).trim().ifEmpty { "♪" }
            stamps.map { m ->
                Line((m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toDouble() * 1000).toLong(), text)
            }
        }.sortedBy { it.timeMs }
    }
}
