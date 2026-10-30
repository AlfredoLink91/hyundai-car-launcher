package com.autosense.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.SeekBar

/**
 * Deslizador vertical (abajo = mínimo, arriba = máximo) para brillo y volumen en el modo conducción.
 * Es un SeekBar girado, así que el tablero lo usa igual que los deslizadores horizontales.
 */
class VerticalSeekBar(context: Context, attrs: AttributeSet?) : SeekBar(context, attrs) {

    private var listener: OnSeekBarChangeListener? = null

    override fun setOnSeekBarChangeListener(l: OnSeekBarChangeListener?) {
        listener = l
        super.setOnSeekBarChangeListener(l)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(heightMeasureSpec, widthMeasureSpec)
        setMeasuredDimension(measuredHeight, measuredWidth)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(h, w, oldh, oldw)
    }

    @Synchronized
    override fun setProgress(progress: Int) {
        super.setProgress(progress)
        // Recalcula la posición de la perilla al cambiar el valor desde código
        onSizeChanged(width, height, 0, 0)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.rotate(-90f)
        canvas.translate(-height.toFloat(), 0f)
        super.onDraw(canvas)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    listener?.onStartTrackingTouch(this)
                }
                val usable = (height - paddingTop - paddingBottom).coerceAtLeast(1)
                val fraction = 1f - ((event.y - paddingTop) / usable).coerceIn(0f, 1f)
                val value = (fraction * max).toInt()
                if (value != progress) {
                    progress = value
                    listener?.onProgressChanged(this, value, true)
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) listener?.onStopTrackingTouch(this)
            }
            MotionEvent.ACTION_CANCEL -> listener?.onStopTrackingTouch(this)
        }
        return true
    }
}
