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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

/**
 * Touchpad: 1 finger = move, tap = click, 2-finger tap = right click, 2-finger drag = scroll,
 * 3-finger tap = Win+Tab, 4-finger tap = Win+S (search),
 * tap then tap again and HOLD (anywhere) = drag, then move.
 *
 * Xiaomi Smart Pen mode (penMode = true): the pen touching the pad holds the left button (like click-and-hold on a mouse),
 * so moving the pen draws / drags. Movement is 1:1 (no acceleration), uses every batched pen sample and a One Euro
 * smoothing filter. Finger touches are ignored while the pen is near (palm rejection).
 */
class TouchpadView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Listener {
        fun onMove(dx: Float, dy: Float)
        fun onScroll(amount: Float)
        fun onClick(button: Int)      // 0 = left, 1 = right
        fun onDrag(down: Boolean)
        fun onGesture(fingers: Int) {}                  // 3 or 4 finger tap
        fun onPen(down: Boolean, right: Boolean) {}     // pen touches / leaves the pad (right = barrel button held)
    }

    /** One Euro filter: removes hand jitter at slow speed but adds almost no lag when the pen moves fast. */
    private class OneEuro {
        var minCut = 8.0
        var beta = 0.03
        private var x = 0.0
        private var dx = 0.0
        private var t0 = 0.0
        private var primed = false

        private fun alpha(cut: Double, dt: Double): Double = 1.0 / (1.0 + 1.0 / (2.0 * Math.PI * cut) / dt)

        fun filter(v: Double, t: Double): Double {
            if (!primed) { primed = true; x = v; dx = 0.0; t0 = t; return v }
            val dt = maxOf(0.001, t - t0)
            t0 = t
            dx += alpha(1.0, dt) * ((v - x) / dt - dx)
            x += alpha(minCut + beta * abs(dx), dt) * (v - x)
            return x
        }
    }

    var listener: Listener? = null
    var sens = 1.6f
    var scrollFactor = 1.2f
    var natural = true
    var tapGapMs = 200L
    var dragHoldMs = 3000L
    var gestures = true
    var penSpeed = 1.5f
    var penSmooth = 0.5f
    var penHover = true
    var penMode = false
        set(v) {
            if (!v && penDown) penEnd()
            field = v
            invalidate()
        }

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
    private val psx = FloatArray(MAX_IDS)     // where each finger went down (to tell a tap from a swipe)
    private val psy = FloatArray(MAX_IDS)

    // pen state
    private var penDown = false
    private var penPid = -1
    private var penRight = false
    private var penHovering = false
    private var penHoverAt = 0L
    private var penIgnoreUntil = 0L
    private var rejectFingers = false
    private var fx: OneEuro? = null
    private var fy: OneEuro? = null
    private var penLx = 0.0
    private var penLy = 0.0
    private var penRx = 0.0
    private var penRy = 0.0

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
    private val penPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DA3FF")
        textAlign = Paint.Align.LEFT
        textSize = 12f * den
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

    private fun rememberStart(e: MotionEvent, index: Int) {
        val id = e.getPointerId(index)
        if (id in 0 until MAX_IDS) { psx[id] = e.getX(index); psy[id] = e.getY(index) }
    }

    // ------------------------------------------------------------------ pen
    private fun stylusIndex(e: MotionEvent): Int {
        for (i in 0 until e.pointerCount) if (e.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS) return i
        return -1
    }

    private fun penNear(): Boolean {
        val now = SystemClock.uptimeMillis()
        return (penHovering && now - penHoverAt < 2000L) || now < penIgnoreUntil
    }

    /** One pen sample (pixels). Smoothed, then sent to the PC as a relative move scaled by the pen speed. */
    private fun penFeed(xPx: Float, yPx: Float, tMs: Long) {
        val x = (xPx / den).toDouble()
        val y = (yPx / den).toDouble()
        val t = tMs / 1000.0
        val a = fx ?: OneEuro().also { fx = it; penLx = x; penLy = y }
        val b = fy ?: OneEuro().also { fy = it }
        val mc = 40.0 * 0.04.pow(penSmooth.toDouble().coerceIn(0.0, 1.0))   // 0 -> 40 Hz (raw), 1 -> 1.6 Hz (very smooth)
        a.minCut = mc
        b.minCut = mc
        val sx = a.filter(x, t)
        val sy = b.filter(y, t)
        penRx = x
        penRy = y
        listener?.onMove(((sx - penLx) * penSpeed).toFloat(), ((sy - penLy) * penSpeed).toFloat())
        penLx = sx
        penLy = sy
    }

    private fun penBegin(e: MotionEvent, si: Int) {
        parent?.requestDisallowInterceptTouchEvent(true)
        handler.removeCallbacks(holdRunnable)
        armed = false
        if (dragging) { dragging = false; listener?.onDrag(false) }
        maxPtr = 0
        moved = true
        rejectFingers = true
        penPid = e.getPointerId(si)
        penRight = (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0   // barrel button = right button
        if (fx == null) penFeed(e.getX(si), e.getY(si), e.eventTime)
        penDown = true
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        listener?.onPen(true, penRight)
        invalidate()
    }

    private fun penEnd() {
        if (!penDown) return
        if (fx != null) {   // deliver the last part of the movement that smoothing was still holding back
            listener?.onMove(((penRx - penLx) * penSpeed).toFloat(), ((penRy - penLy) * penSpeed).toFloat())
        }
        fx = null
        fy = null
        penDown = false
        penIgnoreUntil = SystemClock.uptimeMillis() + 350L
        listener?.onPen(false, penRight)
        invalidate()
    }

    private fun penTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val si = stylusIndex(e)
                if (!penDown && si >= 0 && e.actionIndex == si) penBegin(e, si)
            }
            MotionEvent.ACTION_MOVE -> {
                if (penDown) {
                    val i = e.findPointerIndex(penPid)
                    if (i >= 0) {
                        for (h in 0 until e.historySize) {
                            penFeed(e.getHistoricalX(i, h), e.getHistoricalY(i, h), e.getHistoricalEventTime(h))
                        }
                        penFeed(e.getX(i), e.getY(i), e.eventTime)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (penDown && e.getPointerId(e.actionIndex) == penPid) penEnd()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (penDown) penEnd()
                rejectFingers = false
            }
        }
    }

    /** Pen hovering above the pad: the cursor follows it (so lifted strokes land where you expect when writing). */
    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (!penMode || e.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return super.onHoverEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                penHovering = true
                penHoverAt = SystemClock.uptimeMillis()
                if (!penDown) {
                    if (penHover) {
                        for (h in 0 until e.historySize) {
                            penFeed(e.getHistoricalX(h), e.getHistoricalY(h), e.getHistoricalEventTime(h))
                        }
                        penFeed(e.x, e.y, e.eventTime)
                    } else {
                        fx = null
                        fy = null
                    }
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                penHovering = false
                penIgnoreUntil = SystemClock.uptimeMillis() + 350L
                if (!penDown) { fx = null; fy = null }
            }
        }
        return true
    }

    // ------------------------------------------------------------------ fingers
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (penMode) {
            if (penDown || stylusIndex(e) >= 0) { penTouch(e); return true }
            if (e.actionMasked == MotionEvent.ACTION_DOWN && penNear()) rejectFingers = true   // palm next to the pen
            if (rejectFingers) {
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) rejectFingers = false
                return true
            }
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                maxPtr = 1
                moved = false
                downTime = e.eventTime
                startX = e.x; startY = e.y
                lastX = e.x; lastY = e.y
                rememberStart(e, 0)
                if (lastTapTime != 0L && e.eventTime - lastTapTime < tapGapMs) arm()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPtr = maxOf(maxPtr, e.pointerCount)
                rememberStart(e, e.actionIndex)
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
                if (!moved && n > 1) {   // any finger travelling far = a swipe, not a tap
                    for (i in 0 until n) {
                        val id = e.getPointerId(i)
                        if (id in 0 until MAX_IDS && hypot(e.getX(i) - psx[id], e.getY(i) - psy[id]) > slop) { moved = true; break }
                    }
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
                val limit = if (maxPtr >= 3) 500L else 400L
                if (e.actionMasked == MotionEvent.ACTION_UP && !moved && e.eventTime - downTime < limit) {
                    if (maxPtr == 1) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        listener?.onClick(0)
                        lastTapTime = e.eventTime
                    } else if (maxPtr == 2) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        listener?.onClick(1)
                    } else if (maxPtr == 3 && gestures) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        listener?.onGesture(3)          // Win+Tab
                    } else if (maxPtr >= 4 && gestures) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        listener?.onGesture(4)          // Win+S (search)
                    }
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val lines = arrayOf(
            "tap = click   ·   2-finger tap = right click",
            "2 fingers = scroll",
            "3-finger tap = Win+Tab   ·   4-finger tap = Win+S",
            "tap, then tap + hold = drag"
        )
        var y = h / 2f - (lines.size - 1) * 9f * den
        for (l in lines) {
            canvas.drawText(l, w / 2f, y, textPaint)
            y += 18f * den
        }
        if (penMode) canvas.drawText("✒ Pen mode: pen on the pad = hold left button", 12f * den, 20f * den, penPaint)
        if (dragging || penDown) canvas.drawColor(Color.argb(50, 77, 163, 255))
        if (armed) {
            val f = ((SystemClock.uptimeMillis() - holdStart).toFloat() / dragHoldMs).coerceIn(0f, 1f)
            canvas.drawRect(0f, h - 6f * den, w * f, h, barPaint)
            postInvalidateOnAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(holdRunnable)
        if (penDown) penEnd()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MAX_IDS = 20
    }
}
