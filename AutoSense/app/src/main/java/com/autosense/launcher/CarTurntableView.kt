package com.autosense.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * El auto en 360°: cuadros renderizados del modelo 3D (assets/car360) que cambian al deslizar el dedo,
 * con frenado suave al soltar. La tarjeta gráfica de la tablet no soporta 3D en tiempo real,
 * así que se usan imágenes: se ve como 3D y no gasta procesador cuando está quieto.
 * Dibuja el cuadro directamente (sin ImageView) para que cambiar de cuadro solo redibuje esta vista.
 */
@SuppressLint("ViewConstructor")
class CarTurntableView(context: Context) : View(context) {

    private val names = context.assets.list(FOLDER).orEmpty().sorted()
    private val frames = arrayOfNulls<Bitmap>(names.size)
    private val pixelsPerFrame = PIXELS_PER_FRAME_DP * resources.displayMetrics.density
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dest = RectF()

    private var position = 0f      // cuadro actual (con decimales para el arrastre)
    private var shown = -1
    private var velocity = 0f      // cuadros por fotograma al soltar
    private var lastX = 0f
    private var touching = false

    private val inertia = object : Runnable {
        override fun run() {
            if (touching || abs(velocity) < MIN_VELOCITY) return
            position += velocity
            velocity *= FRICTION
            refresh()
            postOnAnimation(this)
        }
    }

    init {
        contentDescription = "Hyundai Accent en 360 grados. Desliza para girarlo"
        // Los cuadros se cargan en segundo plano para no trabar la pantalla de inicio
        Thread {
            names.forEachIndexed { i, name ->
                frames[i] = context.assets.open("$FOLDER/$name").use { BitmapFactory.decodeStream(it) }
                    // Sube el cuadro a la memoria gráfica desde ya: al girar solo se cambia, sin esperas
                    ?.also { it.prepareToDraw() }
                if (i == 0) post { invalidate() }
            }
        }.start()
    }

    // --- Modo conducción: el auto queda fijo en FORWARD_FRAME y se reproducen sus llantas girando ---
    private var wheelFrames: Array<Bitmap?>? = null
    private var wheelPhase = 0f        // cuadro de las llantas (con decimales)
    private var lastWheelNanos = 0L
    private var driving = false

    /** Velocidad en km/h: las llantas giran más rápido al acelerar y se detienen en 0. */
    var speed: Int = 0
        set(value) {
            field = value.coerceAtLeast(0)
            if (driving && field > 0) postInvalidate()
        }

    /** Gira el auto a la vista de conducción y, al llegar, empieza a mover las llantas con la velocidad. */
    fun startDriving() {
        if (wheelFrames == null) {
            val wheelNames = context.assets.list(WHEELS_FOLDER).orEmpty().sorted()
            val loaded = arrayOfNulls<Bitmap>(wheelNames.size)
            wheelFrames = loaded
            Thread {
                wheelNames.forEachIndexed { i, name ->
                    loaded[i] = context.assets.open("$WHEELS_FOLDER/$name").use { BitmapFactory.decodeStream(it) }
                        ?.also { it.prepareToDraw() }
                }
            }.start()
        }
        spinTo(FORWARD_FRAME) { driving = true; invalidate() }
    }

    /** Sale del modo conducción (las llantas se quedan quietas y vuelve el giro normal). */
    fun stopDriving() {
        driving = false
        lastWheelNanos = 0L
        invalidate()
    }

    /** Gira el auto con una animación suave hasta el cuadro [target] (por el camino más corto). */
    fun spinTo(target: Int, durationMs: Long = 1200, onEnd: (() -> Unit)? = null) {
        if (names.isEmpty()) return
        driving = false
        velocity = 0f
        val n = names.size
        var delta = ((target - position) % n + n) % n
        if (delta > n / 2f) delta -= n
        val start = position
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener {
                position = start + delta * (it.animatedValue as Float)
                refresh()
            }
            if (onEnd != null) addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = onEnd()
            })
            start()
        }
    }

    /** Regresa el auto a su vista original (3/4 de frente) si quedó girado. */
    fun resetOrientation() {
        if (currentIndex() != 0) spinTo(0, 700)
    }

    private fun currentIndex(): Int =
        ((position.roundToInt() % names.size) + names.size) % names.size

    // Solo redibuja cuando de verdad cambia de cuadro
    private fun refresh() {
        if (names.isNotEmpty() && currentIndex() != shown) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (names.isEmpty()) return
        val index = currentIndex()
        var frame = frames[index] ?: frames.firstOrNull { it != null } ?: return
        shown = index
        val wheels = wheelFrames
        if (driving && wheels != null && wheels.isNotEmpty()) {
            // Avance de las llantas según la velocidad, independiente de los cuadros por segundo
            val now = System.nanoTime()
            if (speed > 0) {
                val dt = if (lastWheelNanos == 0L) 0f else ((now - lastWheelNanos) / 1e9f).coerceAtMost(0.1f)
                wheelPhase = (wheelPhase + dt * speed * WHEEL_FRAMES_PER_KMH) % wheels.size
                lastWheelNanos = now
                postInvalidateDelayed(FRAME_MS)
            } else {
                lastWheelNanos = 0L
            }
            wheels[wheelPhase.toInt() % wheels.size]?.let { frame = it }
        }
        // Ajustar al espacio disponible conservando la proporción (centrado)
        val scale = min(width / frame.width.toFloat(), height / frame.height.toFloat())
        val w = frame.width * scale
        val h = frame.height * scale
        dest.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        canvas.drawBitmap(frame, null, dest, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Manejando, el auto queda fijo mirando hacia adelante
        if (driving) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                velocity = 0f
                lastX = event.x
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                // Deslizar a la derecha gira el auto hacia la derecha
                val delta = (lastX - event.x) / pixelsPerFrame
                position += delta
                velocity = delta
                lastX = event.x
                refresh()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                postOnAnimation(inertia)
            }
        }
        return true
    }

    companion object {
        private const val FOLDER = "car360"
        /** Auto de 3/4 trasero, como si avanzara por la carretera y se le vieran las llantas (225° + 10 × 10° = 325°). */
        const val FORWARD_FRAME = 10
        /** Cuadros de las llantas girando (una vuelta completa) renderizados desde el mismo ángulo que FORWARD_FRAME. */
        private const val WHEELS_FOLDER = "car_drive"
        /** 80 km/h ≈ 40 cuadros/s ≈ 1.7 vueltas por segundo: se ve rápido sin el efecto de "rueda al revés". */
        private const val WHEEL_FRAMES_PER_KMH = 0.5f
        private const val FRAME_MS = 33L
        private const val PIXELS_PER_FRAME_DP = 14f
        private const val FRICTION = 0.9f
        private const val MIN_VELOCITY = 0.05f

        /** True si la app trae los cuadros del giro 360°; si no, se usa la foto del auto. */
        fun hasFrames(context: Context): Boolean =
            context.assets.list(FOLDER).orEmpty().isNotEmpty()
    }
}
