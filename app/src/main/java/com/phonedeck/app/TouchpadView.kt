package com.phonedeck.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * Touchpad: 1 finger = move, tap = click, 2-finger tap = right click, 2-finger drag = scroll,
 * tap then tap again and HOLD (anywhere) = drag, then move.
 */
class TouchpadView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Listener {
        fun onMove(dx: Float, dy: Float)
        fun onScroll(amount: Float)
        fun onClick(button: Int)      // 0 = left, 1 = right
        fun onDrag(down: Boolean)
    }

    var listener: Listener? = null
    var sens = 1.6f
    var scrollFactor = 1.2f
    var natural = true
    var tapGapMs = 200L
    var dragHoldMs = 3000L

    private val den = resources.displayMetrics.density
    private val slop = 12f * den
    private val handler = Handler(Looper.getMainLooper())

    private var maxPtr = 0
    private var downTime = 0L
    private var moved = false
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastTapTime = 0L
    private var dragging = false
    private var armed = false
    private var holdStart = 0L

    private val holdRunnable = Runnable {
        armed = false
        if (!moved && maxPtr == 1) {
            dragging = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener?.onDrag(true)
        }
        invalidate()
    }

    private val barPaint = Paint().apply { color = Color.parseColor("#4DA3FF") }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B93A5")
        textAlign = Paint.Align.CENTER
        textSize = 13f * den
    }

    private fun arm() {
        armed = true
        holdStart = SystemClock.uptimeMillis()
        handler.postDelayed(holdRunnable, dragHoldMs)
        postInvalidateOnAnimation()
    }

    private fun disarm() {
        if (armed) {
            armed = false
            handler.removeCallbacks(holdRunnable)
            invalidate()
        }
    }

    private fun avgX(e: MotionEvent, skip: Int = -1): Float {
        var s = 0f; var n = 0
        for (i in 0 until e.pointerCount) { if (i == skip) continue; s += e.getX(i); n++ }
        return if (n == 0) 0f else s / n
    }

    private fun avgY(e: MotionEvent, skip: Int = -1): Float {
        var s = 0f; var n = 0
        for (i in 0 until e.pointerCount) { if (i == skip) continue; s += e.getY(i); n++ }
        return if (n == 0) 0f else s / n
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                maxPtr = 1
                moved = false
                downTime = e.eventTime
                startX = e.x; startY = e.y
                lastX = e.x; lastY = e.y
                if (lastTapTime != 0L && e.eventTime - lastTapTime < tapGapMs) arm()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPtr = maxOf(maxPtr, e.pointerCount)
                disarm()
                if (dragging) { dragging = false; listener?.onDrag(false); invalidate() }
                lastX = avgX(e); lastY = avgY(e)
            }
            MotionEvent.ACTION_MOVE -> {
                val n = e.pointerCount
                val cx = avgX(e); val cy = avgY(e)
                if (!moved && hypot(e.getX(0) - startX, e.getY(0) - startY) > slop) {
                    moved = true
                    if (!dragging) disarm()
                }
                val dx = (cx - lastX) / den
                val dy = (cy - lastY) / den
                lastX = cx; lastY = cy
                if (n == 1) {
                    val v = hypot(dx, dy)
                    val gain = sens * min(1f + v * 0.12f, 4f)
                    listener?.onMove(dx * gain, dy * gain)
                } else if (n == 2) {
                    val dir = if (natural) 1f else -1f
                    listener?.onScroll(dir * dy * scrollFactor / 40f)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastX = avgX(e, e.actionIndex); lastY = avgY(e, e.actionIndex)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                disarm()
                if (dragging) {
                    dragging = false
                    lastTapTime = 0L
                    listener?.onDrag(false)
                    invalidate()
                    return true
                }
                if (e.actionMasked == MotionEvent.ACTION_UP && !moved && e.eventTime - downTime < 400) {
                    if (maxPtr == 1) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        listener?.onClick(0)
                        lastTapTime = e.eventTime
                    } else if (maxPtr == 2) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        listener?.onClick(1)
                    }
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawText("tap = click   ·   2-finger tap = right click", w / 2f, h / 2f - 18f * den, textPaint)
        canvas.drawText("2 fingers = scroll", w / 2f, h / 2f, textPaint)
        canvas.drawText("tap, then tap + hold = drag", w / 2f, h / 2f + 18f * den, textPaint)
        if (dragging) canvas.drawColor(Color.argb(50, 77, 163, 255))
        if (armed) {
            val f = ((SystemClock.uptimeMillis() - holdStart).toFloat() / dragHoldMs).coerceIn(0f, 1f)
            canvas.drawRect(0f, h - 6f * den, w * f, h, barPaint)
            postInvalidateOnAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(holdRunnable)
        super.onDetachedFromWindow()
    }
}
