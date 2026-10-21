package com.phonedeck.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.InputMethod
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Lets the PC's mouse and keyboard control this tablet.
 *  - The PC pushes events over the link owned by [LinkHub] (it keeps running without this service);
 *    this service draws a pointer (overlay) and performs them
 *    with accessibility gestures / actions (no root, no ADB).
 *  - Mouse: move = pointer, left = tap / long-press / drag, wheel = swipe, right = Back, middle = Home,
 *    side buttons = Back / Recents.
 *  - Keyboard: typed text, Backspace/Delete/arrows/Enter, Ctrl+A/C/X/V.
 *  - Clipboard: PC clipboard is copied to the tablet when the pointer crosses over; Ctrl+C on the tablet
 *    and the tablet clipboard on the way back are sent to the PC.
 */
class RemoteInputService : AccessibilityService() {

    companion object {
        @Volatile var instance: RemoteInputService? = null
        fun applyPrefsNow() { instance?.applyPrefs() }
    }

    private val ui = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var cm: ClipboardManager? = null
    private var cursor: CursorView? = null
    private var cursorShown = false

    private var sw = 1080
    private var sh = 1920
    private var cx = 0f
    private var cy = 0f
    private var remote = false
    private var peerEdge = "left"
    private var enterAt = 0L
    private var gain = 1.6f

    // left button / drag
    private var lDown = false
    private var downT = 0L
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var dragEnding = false
    private var pumpBusy = false
    private var dragX = 0f
    private var dragY = 0f
    private var curStroke: GestureDescription.StrokeDescription? = null

    // carrying a file from the tablet to the PC (button held on a file, pointer pushed to the PC edge)
    private var carrying = false
    private var carryChip = false

    // wheel
    private var wheelDx = 0f
    private var wheelDy = 0f
    private var wheelBusy = false

    // clipboard
    private var pendingSelPaste = 0L      // PC selection waiting for a tap on a text box (0 = none)
    private var clipFromPc: String? = null
    private var clipDirty = false
    private var selfClipAt = 0L
    private var lastSentClip = ""
    private var syncedTs = 0L          // timestamp of the clipboard content the PC already has (or that came from the PC)
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (System.currentTimeMillis() - selfClipAt > 1500) {
            clipDirty = true
            // already on the PC before you even get there; also when copying by touch while the pointer is on the PC
            if (remote || (LinkHub.connected && Prefs.of(this).getBoolean("clip_always", true)))
                ui.postDelayed({ pushTabletClipNow() }, 350)
        }
    }

    private fun clipTs(): Long = try { cm?.primaryClipDescription?.timestamp ?: 0L } catch (e: Exception) { 0L }

    val isLinked: Boolean get() = LinkHub.connected

    // ---------------------------------------------------------------- keep-alive / reconnect
    // The link itself (connect, reconnect, hotspot changes, heartbeat) lives in [LinkHub]; nothing to do here.
    /** Kept for old callers: the heartbeat is [LinkHub]'s job now. */
    fun heartbeat() { LinkHub.heartbeat() }

    // ---------------------------------------------------------------- lifecycle
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        try { cm?.addPrimaryClipChangedListener(clipListener) } catch (e: Exception) { }
        syncedTs = clipTs()                  // what is on the clipboard now is not "new": do not send it to the PC
        refreshScreen()
        applyPrefs()
        LinkHub.onInputServiceChanged()      // the PC may now show a pointer here
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshScreen()
        if (LinkHub.connected) sendHello()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        exitRemote()
        hideDragChip()
        try { cm?.removePrimaryClipChangedListener(clipListener) } catch (e: Exception) { }
        instance = null
        LinkHub.onInputServiceChanged()      // the PC must stop moving its pointer over here
    }

    fun applyPrefs() {
        val p = Prefs.of(this)
        gain = p.getFloat("mouse_gain", 1.6f)
        if (KeepAlive.wanted(this)) {
            KeepAlive.start(this)
            LinkHub.ensure(this)
            if (LinkHub.connected) sendHello()
        } else {
            exitRemote()
            LinkHub.stop()
            KeepAlive.stop(this)
        }
    }

    /** Real screen size in pixels (the PC needs it to place the pointer). */
    fun screenSize(): Pair<Int, Int> { refreshScreen(); return Pair(sw, sh) }

    @Suppress("DEPRECATION")
    private fun refreshScreen() {
        val dm = DisplayMetrics()
        wm?.defaultDisplay?.getRealMetrics(dm)
        if (dm.widthPixels > 0 && dm.heightPixels > 0) { sw = dm.widthPixels; sh = dm.heightPixels }
    }

    private fun sendHello() { refreshScreen(); LinkHub.sendHello() }

    /** [LinkHub] reports the link going up / down (it already sent the hello when it came up). */
    fun onLinkState(connected: Boolean) {
        if (!connected) exitRemote()
    }

    // ---------------------------------------------------------------- messages from the PC
    /** Pointer / keyboard / clipboard messages forwarded by [LinkHub] (file messages never come here). */
    fun onHubMessage(o: JSONObject) { onMsg(o) }

    private fun onMsg(o: JSONObject) {
        when (o.optString("t")) {
            "enter" -> enter(o.optString("edge", "left"), o.optDouble("f", 0.5).toFloat())
            "exit" -> exitRemote()
            "m" -> if (remote) handleMove(o.optDouble("dx").toFloat(), o.optDouble("dy").toFloat())
            "b" -> if (remote) handleButton(o.optString("b"), o.optBoolean("d"))
            "w" -> if (remote) handleWheel(o.optDouble("dx").toFloat(), o.optDouble("dy").toFloat())
            "text" -> if (remote) typeText(o.optString("s"))
            "key" -> if (remote) pressKey(o.optString("k"), o.optBoolean("shift"))
            "combo" -> if (remote) combo(o.optString("k"))
            "clip" -> {
                setClipFromPc(o.optString("text"))
                // text that was selected on the PC: paste it into the next text box that is tapped here
                pendingSelPaste = if (o.optBoolean("sel") && o.optString("text").isNotEmpty() &&
                    Prefs.of(this).getBoolean("autopaste_sel", true)) System.currentTimeMillis() else 0L
            }
            "pong" -> {}
        }
    }

    private fun enter(edge: String, f: Float) {
        refreshScreen()
        val fr = f.coerceIn(0f, 1f)
        peerEdge = edge
        when (edge) {
            "left" -> { cx = 0f; cy = fr * (sh - 1) }
            "right" -> { cx = (sw - 1).toFloat(); cy = fr * (sh - 1) }
            "top" -> { cy = 0f; cx = fr * (sw - 1) }
            else -> { cy = (sh - 1).toFloat(); cx = fr * (sw - 1) }
        }
        remote = true
        enterAt = System.currentTimeMillis()
        showCursor()
    }

    private fun exitRemote() {
        remote = false
        pendingSelPaste = 0L
        carrying = false
        FileSender.clear()
        cancelDrag()
        hideCursor()
        hideDragChip()
        FileReceiver.onDragEnd(this)         // files carried over but not released: forget them
    }

    private fun leave() {
        val f = if (peerEdge == "left" || peerEdge == "right") cy / sh else cx / sw
        // read the clipboard while the pointer overlay is still on screen (Android 14+ only lets an app that shows a window
        // start the helper screen that reads it)
        val fresh = clipDirty || clipTs() > syncedTs
        pushTabletClipNow()
        val leaveMsg = JSONObject().put("t", "leave").put("f", f.toDouble().coerceIn(0.0, 1.0))
        if (fresh && readClipSafe() == null) {
            // text has to come through the helper screen: the pointer stays here for a moment so the PC gets the text first
            // (a copy made earlier was already sent at copy time and does not wait)
            ui.postDelayed({ LinkHub.send(leaveMsg) }, 450)
        } else LinkHub.send(leaveMsg)
        exitRemote()
    }

    // ---------------------------------------------------------------- pointer
    private fun handleMove(dx: Float, dy: Float) {
        val rx = cx + dx * gain
        val ry = cy + dy * gain
        if (System.currentTimeMillis() - enterAt > 300) {
            val out = when (peerEdge) {
                "left" -> rx < -2f
                "right" -> rx > sw + 1f
                "top" -> ry < -2f
                else -> ry > sh + 1f
            }
            if (out) {
                if (carrying) {
                    // files under the pointer: hand them to the PC (wait a moment if they are still being looked up)
                    if (FileSender.capturing) { clampMove(rx, ry); return }
                    val c = FileSender.cand
                    carrying = false
                    FileSender.clear()
                    if (c != null) FileSender.begin(this, c)
                }
                leave(); return
            }
        }
        clampMove(rx, ry)
        if (flingMode && dragging && trail.size < 400) trail.add(floatArrayOf(cx, cy, (System.currentTimeMillis() - downT).toFloat()))
        if (lDown && !dragging && !carrying && hypot(cx - downX, cy - downY) > 10f * resources.displayMetrics.density) {
            if ((FileSender.capturing || FileSender.cand != null) && headingToPc()) { carrying = true; carryChip = false } else startDrag()
        }
        if (carrying && !FileSender.capturing) {
            if (FileSender.cand == null) { carrying = false; hideDragChip(); startDrag() }     // nothing to carry: an ordinary drag
            else if (!carryChip) { carryChip = true; showDragChip(carryLabel()) }
        }
        if (dragging) pump()
    }

    private fun clampMove(rx: Float, ry: Float) {
        cx = rx.coerceIn(0f, (sw - 1).toFloat())
        cy = ry.coerceIn(0f, (sh - 1).toFloat())
        moveCursor()
    }

    /** Is the pointer moving (since the button went down) mostly towards the edge that leads back to the PC? */
    private fun headingToPc(): Boolean {
        val dx = cx - downX
        val dy = cy - downY
        val len = hypot(dx, dy)
        if (len < 1f) return false
        val toward = when (peerEdge) {
            "left" -> -dx
            "right" -> dx
            "top" -> -dy
            else -> dy
        }
        return toward / len > 0.35f
    }

    private fun carryLabel(): String {
        val c = FileSender.cand ?: return "\uD83D\uDCC4"
        return if (c.top.size == 1) "\uD83D\uDCC4 " + c.top[0] else "\uD83D\uDCC4 " + c.top.size + " items"
    }

    private fun handleButton(b: String, down: Boolean) {
        when (b) {
            "l" -> if (down) leftDown() else leftUp()
            "r" -> if (down) performGlobalAction(GLOBAL_ACTION_BACK)
            "m" -> if (down) performGlobalAction(GLOBAL_ACTION_HOME)
            "x1" -> if (down) performGlobalAction(GLOBAL_ACTION_BACK)
            "x2" -> if (down) performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    private fun leftDown() {
        lDown = true; dragging = false; dragEnding = false; carrying = false
        downT = System.currentTimeMillis(); downX = cx; downY = cy
        if (FileSender.enabled(this)) FileSender.capture(this, cx.toInt(), cy.toInt()) else FileSender.clear()
    }

    private fun leftUp() {
        if (!lDown) return
        lDown = false
        if (carrying) {                                   // let go on the tablet: the files stay where they are
            carrying = false
            FileSender.clear()
            hideDragChip()
            return
        }
        if (dragging && flingMode) { flingNow(); return }
        if (dragging) { dragEnding = true; pump(); return }
        val held = System.currentTimeMillis() - downT
        gestureAt(downX, downY, if (held >= 500) 700L else 50L)
        if (pendingSelPaste != 0L && held < 500) {
            val stamp = pendingSelPaste
            ui.postDelayed({
                // a text box got focus from that tap: put the selected PC text in it (once)
                if (pendingSelPaste == stamp && System.currentTimeMillis() - stamp < 120_000 && (ic() != null || editNode() != null)) {
                    pendingSelPaste = 0L
                    combo("paste")
                }
            }, 500)
        }
    }

    private fun gestureAt(x: Float, y: Float, dur: Long) {
        val p = Path().apply { moveTo(x, y) }
        try {
            dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, dur)).build(), null, null)
        } catch (e: Exception) { }
    }

    private var holdDrag = false

    // quick swipe (button down, move at once, release): recorded while the button is held and replayed as ONE real swipe on release,
    // so home screen pages, the app drawer and lists slide and fling smoothly like with a finger
    private var flingMode = false
    private val trail = ArrayList<FloatArray>()      // x, y, time

    private fun startDrag() {
        dragging = true; dragEnding = false; pumpBusy = false
        dragX = downX; dragY = downY; curStroke = null
        // held still for a moment before moving = long-press, then drag: selects text (and moves selection handles)
        holdDrag = System.currentTimeMillis() - downT >= 400L
        flingMode = !holdDrag
        trail.clear()
        if (flingMode) trail.add(floatArrayOf(downX, downY, 0f))
    }

    /** Replays the recorded movement as a single swipe with the same speed (released = the pages slide). */
    private fun flingNow() {
        val pts = ArrayList(trail)
        trail.clear()
        flingMode = false; dragging = false; dragEnding = false; lDown = false; curStroke = null
        if (pts.size < 2) return
        val first = pts.first()
        val last = pts.last()
        if (hypot(last[0] - first[0], last[1] - first[1]) < 6f * resources.displayMetrics.density) return
        val path = Path().apply {
            moveTo(first[0], first[1])
            val step = maxOf(1, pts.size / 12)
            var i = step
            while (i < pts.size - 1) { lineTo(pts[i][0], pts[i][1]); i += step }
            lineTo(last[0], last[1])
        }
        val dur = (last[2] - pts[1][2] + 16f).toLong().coerceIn(60L, 600L)   // from the first move, not from the press
        try {
            dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build(), null, null)
        } catch (e: Exception) { }
    }

    /** Streams the drag as a chain of continued strokes (one in flight at a time). */
    private fun pump() {
        if (flingMode) return
        if (!dragging || pumpBusy) return
        val tx = cx
        val ty = cy
        if (!dragEnding && tx == dragX && ty == dragY) return
        val cont = !dragEnding
        val prev = curStroke
        val hold = prev == null && holdDrag && cont
        val path = Path().apply { moveTo(dragX, dragY); if (!hold) lineTo(tx, ty) }
        val s = try {
            if (hold) GestureDescription.StrokeDescription(path, 0, 520, true)      // the long-press part
            else if (prev == null) GestureDescription.StrokeDescription(path, 0, 40, cont)
            else prev.continueStroke(path, 0, 40, cont)
        } catch (e: Exception) { finishDrag(); return }
        curStroke = s
        if (hold) holdDrag = false else { dragX = tx; dragY = ty }
        pumpBusy = true
        val ok = try {
            dispatchGesture(GestureDescription.Builder().addStroke(s).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        pumpBusy = false
                        if (dragEnding) finishDrag() else pump()
                    }
                    override fun onCancelled(g: GestureDescription?) {
                        pumpBusy = false
                        if (dragEnding) finishDrag() else { curStroke = null; pump() }
                    }
                }, ui)
        } catch (e: Exception) { false }
        if (!ok) { pumpBusy = false; finishDrag() }
    }

    private fun finishDrag() {
        flingMode = false
        dragging = false; dragEnding = false; pumpBusy = false; curStroke = null; lDown = false
    }

    private fun cancelDrag() {
        if (flingMode) { flingNow(); return }
        if (dragging && !pumpBusy) { dragEnding = true; pump() } else if (dragging) dragEnding = true
        lDown = false
    }

    private fun handleWheel(dx: Float, dy: Float) {
        wheelDx += dx; wheelDy += dy
        flushWheel()
    }

    private fun flushWheel() {
        if (wheelBusy) return
        val ax = wheelDx
        val ay = wheelDy
        if (abs(ax) < 0.2f && abs(ay) < 0.2f) return
        wheelDx = 0f; wheelDy = 0f
        val ddy = (ay * sh * 0.16f).coerceIn(-sh * 0.6f, sh * 0.6f)    // wheel up -> finger moves down
        val ddx = (-ax * sw * 0.16f).coerceIn(-sw * 0.6f, sw * 0.6f)   // wheel right -> finger moves left
        val sx = (cx - ddx / 2).coerceIn(2f, sw - 3f)
        val sy = (cy - ddy / 2).coerceIn(2f, sh - 3f)
        val ex = (sx + ddx).coerceIn(2f, sw - 3f)
        val ey = (sy + ddy).coerceIn(2f, sh - 3f)
        val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        wheelBusy = true
        val ok = try {
            dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 150)).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { wheelBusy = false; flushWheel() }
                    override fun onCancelled(g: GestureDescription?) { wheelBusy = false }
                }, ui)
        } catch (e: Exception) { false }
        if (!ok) wheelBusy = false
    }

    // ---------------------------------------------------------------- real input connection (Android 13+)
    // The accessibility service can act as an invisible keyboard: it gets the focused field's input connection,
    // so typing works in every app (Claude, ChatGPT, Compose / WebView fields), not only classic text boxes.
    private var pcIme: PcInput? = null

    @RequiresApi(33)
    override fun onCreateInputMethod(): InputMethod {
        val m = PcInput(this)
        pcIme = m
        return m
    }

    @RequiresApi(33)
    inner class PcInput(s: AccessibilityService) : InputMethod(s)

    private fun ic(): InputMethod.AccessibilityInputConnection? =
        if (Build.VERSION.SDK_INT >= 33) pcIme?.currentInputConnection else null

    private val CTRL = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON

    private fun sendKey(c: InputMethod.AccessibilityInputConnection, code: Int, meta: Int = 0) {
        val t = SystemClock.uptimeMillis()
        c.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        c.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
    }

    private fun icSelected(c: InputMethod.AccessibilityInputConnection): String? {
        return try {
            val st = c.getSurroundingText(20000, 20000, 0) ?: return null
            val a = minOf(st.selectionStart, st.selectionEnd)
            val b = maxOf(st.selectionStart, st.selectionEnd)
            if (a < 0 || a == b || b > st.text.length) null else st.text.subSequence(a, b).toString()
        } catch (e: Exception) { null }
    }

    // ---------------------------------------------------------------- keyboard
    private fun editNode(): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (n == null) {
            try {
                for (w in windows) {
                    n = w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (n != null) break
                }
            } catch (e: Exception) { }
        }
        if (n == null) return null
        val canSet = n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
        if (!n.isEditable && !canSet) return null
        n.refresh()
        return n
    }

    private fun nodeText(n: AccessibilityNodeInfo): String =
        if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) "" else (n.text?.toString() ?: "")

    private fun applyText(n: AccessibilityNodeInfo, text: String, caret: Int) {
        val a = Bundle()
        a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a)
        setSel(n, caret, caret)
    }

    private fun setSel(n: AccessibilityNodeInfo, s: Int, e: Int) {
        val b = Bundle()
        b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, s)
        b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, e)
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, b)
    }

    /** Returns (text, selStart, selEnd) with a sane selection. */
    private fun state(n: AccessibilityNodeInfo): Triple<String, Int, Int> {
        val t = nodeText(n)
        var s = n.textSelectionStart
        var e = n.textSelectionEnd
        if (s < 0 || e < 0) { s = t.length; e = t.length }
        if (s > e) { val x = s; s = e; e = x }
        return Triple(t, s.coerceIn(0, t.length), e.coerceIn(0, t.length))
    }

    private fun typeText(s: String) {
        if (s.isEmpty()) return
        val c = ic()
        if (c != null) { c.commitText(s, 1, null); return }
        val n = editNode() ?: return
        val (t, a, b) = state(n)
        applyText(n, t.substring(0, a) + s + t.substring(b), a + s.length)
    }

    private fun pressKey(k: String, shift: Boolean = false) {
        when (k) {
            "Escape" -> { performGlobalAction(GLOBAL_ACTION_BACK); return }
            "PageUp" -> { handleWheel(0f, 3f); return }
            "PageDown" -> { handleWheel(0f, -3f); return }
        }
        val c = ic()
        if (c != null) {
            if (k == "Enter" && shift) { c.commitText("\n", 1, null); return }
            val code = when (k) {
                "Backspace" -> KeyEvent.KEYCODE_DEL
                "Delete" -> KeyEvent.KEYCODE_FORWARD_DEL
                "Left" -> KeyEvent.KEYCODE_DPAD_LEFT
                "Right" -> KeyEvent.KEYCODE_DPAD_RIGHT
                "Up" -> KeyEvent.KEYCODE_DPAD_UP
                "Down" -> KeyEvent.KEYCODE_DPAD_DOWN
                "Home" -> KeyEvent.KEYCODE_MOVE_HOME
                "End" -> KeyEvent.KEYCODE_MOVE_END
                "Enter" -> KeyEvent.KEYCODE_ENTER
                "Tab" -> KeyEvent.KEYCODE_TAB
                else -> 0
            }
            if (code != 0) { sendKey(c, code, if (shift) KeyEvent.META_SHIFT_ON else 0); return }
        }
        val n = editNode() ?: return
        val (t, a, b) = state(n)
        when (k) {
            "Backspace" -> when {
                a != b -> applyText(n, t.substring(0, a) + t.substring(b), a)
                a > 0 -> applyText(n, t.substring(0, a - 1) + t.substring(a), a - 1)
            }
            "Delete" -> when {
                a != b -> applyText(n, t.substring(0, a) + t.substring(b), a)
                a < t.length -> applyText(n, t.substring(0, a) + t.substring(a + 1), a)
            }
            "Left" -> { val p = if (a != b) a else (a - 1).coerceAtLeast(0); setSel(n, p, p) }
            "Right" -> { val p = if (a != b) b else (b + 1).coerceAtMost(t.length); setSel(n, p, p) }
            "Home" -> setSel(n, 0, 0)
            "End" -> setSel(n, t.length, t.length)
            "Enter" -> {
                if (Build.VERSION.SDK_INT >= 30 &&
                    n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return
                applyText(n, t.substring(0, a) + "\n" + t.substring(b), a + 1)
            }
        }
    }

    private fun combo(k: String) {
        val c = ic()
        if (c != null) {
            when (k) {
                "selall" -> { sendKey(c, KeyEvent.KEYCODE_A, CTRL); return }
                "paste" -> {
                    val pc = clipFromPc
                    if (pc != null && !clipDirty) c.commitText(pc, 1, null)
                    else sendKey(c, KeyEvent.KEYCODE_V, CTRL)
                    return
                }
                "copy", "cut" -> {
                    val sel = icSelected(c)
                    selfClipAt = System.currentTimeMillis()
                    sendKey(c, if (k == "cut") KeyEvent.KEYCODE_X else KeyEvent.KEYCODE_C, CTRL)
                    clipFromPc = null
                    if (sel != null) pushClip(sel) else { clipDirty = true; ui.postDelayed({ pushTabletClipNow() }, 350) }
                    return
                }
            }
        }
        when (k) {
            "selall" -> { val n = editNode() ?: return; setSel(n, 0, nodeText(n).length) }
            "paste" -> {
                val n = editNode() ?: return
                val pc = clipFromPc
                if (pc != null && !clipDirty) {
                    val (t, a, b) = state(n)
                    applyText(n, t.substring(0, a) + pc + t.substring(b), a + pc.length)
                } else n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
            "copy", "cut" -> {
                if (k == "copy" && pressToolbarCopy()) return
                val r = rootInActiveWindow ?: return
                val n = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: r.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) ?: return
                n.refresh()
                val sel = selectedText(n)
                selfClipAt = System.currentTimeMillis()
                n.performAction(if (k == "cut") AccessibilityNodeInfo.ACTION_CUT else AccessibilityNodeInfo.ACTION_COPY)
                clipFromPc = null
                if (sel != null) pushClip(sel) else { clipDirty = true; ui.postDelayed({ pushTabletClipNow() }, 350) }
            }
        }
    }

    /** Click a visible button / menu item by its label (selection toolbar of the app in front). */
    private fun clickLabel(label: String): Boolean {
        try {
            for (w in windows) {
                val root = w.root ?: continue
                if (root.packageName?.toString() == packageName) continue      // not PhoneDeck's own "Send to PC" file button
                for (n in root.findAccessibilityNodeInfosByText(label)) {
                    var t: AccessibilityNodeInfo? = n
                    while (t != null && !t.isClickable) t = t.parent
                    if (t != null && t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                }
            }
        } catch (e: Exception) { }
        return false
    }

    /** Selected text handed over by the "Send to PC" toolbar item ([ProcessTextActivity]): straight to the PC clipboard. */
    fun sendSelection(t: String) {
        ui.post {
            lastSentClip = ""
            pushClip(t)
        }
    }

    /** Text selected by long-press + drag (web pages, chats, articles): tap the "Copy" button of the floating selection toolbar. */
    private fun pressToolbarCopy(): Boolean {
        if (clickLabel("Send to PC")) return true      // visible in the selection toolbar: the text goes to the PC without touching the clipboard
        return try {
            for (w in windows) {
                val root = w.root ?: continue
                for (label in listOf("Copy", "COPY", "Kopieren", "Copiar", "Copier", "Copia", "\u590D\u5236", "\u0915\u0949\u092A\u0940")) {
                    for (n in root.findAccessibilityNodeInfosByText(label)) {
                        var t: AccessibilityNodeInfo? = n
                        while (t != null && !t.isClickable) t = t.parent
                        if (t != null && t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            selfClipAt = System.currentTimeMillis() - 1600     // a real copy: let the clipboard listener see it
                            clipFromPc = null
                            clipDirty = true
                            ui.postDelayed({ pushTabletClipNow() }, 350)
                            return true
                        }
                    }
                }
            }
            false
        } catch (e: Exception) { false }
    }

    private fun selectedText(n: AccessibilityNodeInfo): String? {
        val t = n.text?.toString() ?: return null
        var s = n.textSelectionStart
        var e = n.textSelectionEnd
        if (s < 0 || e < 0 || s == e) return null
        if (s > e) { val x = s; s = e; e = x }
        if (e > t.length) return null
        return t.substring(s, e)
    }

    // ---------------------------------------------------------------- clipboard
    /**
     * A PC screenshot is now the tablet's clipboard picture. Forget the old PC text (so Ctrl+V pastes the picture, not that text)
     * and, when [autoPaste] is on and a text box is focused (ChatGPT's message box, a search field...), paste it right away.
     */
    fun onSnipClip(autoPaste: Boolean) {
        syncedTs = maxOf(syncedTs, clipTs())
        clipFromPc = null
        clipDirty = false
        selfClipAt = System.currentTimeMillis()
        if (!autoPaste || !remote) return
        ui.postDelayed({
            if (ic() != null || editNode() != null) combo("paste")
        }, 400)
    }

    private fun setClipFromPc(text: String) {
        if (text.isEmpty()) return
        clipFromPc = text
        clipDirty = false
        lastSentClip = text
        lastSentAt = System.currentTimeMillis()
        selfClipAt = System.currentTimeMillis()
        try { cm?.setPrimaryClip(ClipData.newPlainText("PC", text)) } catch (e: Exception) { }
        syncedTs = maxOf(syncedTs, clipTs())
    }

    private fun readClipSafe(): String? = try {
        val c = cm?.primaryClip
        if (c != null && c.itemCount > 0 && isTextClip(c.description)) c.getItemAt(0).coerceToText(this)?.toString() else null
    } catch (e: Exception) { null }

    private fun isTextClip(d: android.content.ClipDescription?): Boolean =
        d == null || d.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN) ||
            d.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_HTML)

    private var lastSentAt = 0L
    private fun pushClip(t: String) {
        clipDirty = false
        if (t.isEmpty()) return
        val now = System.currentTimeMillis()
        if (t == lastSentClip && now - lastSentAt < 1500) return      // the same copy reported twice; copying the same text again later still goes through
        lastSentClip = t
        lastSentAt = now
        if (LinkHub.send(JSONObject().put("t", "clip").put("text", t.take(100_000))))
            ui.post { try { android.widget.Toast.makeText(this, "Copied to the PC clipboard: Ctrl+V there", android.widget.Toast.LENGTH_SHORT).show() } catch (e: Exception) { } }
    }

    /** Get the tablet's clipboard text to the PC now (Android only lets a focused app read it, so a blink-length helper screen does it). */
    private fun pushTabletClipNow() {
        // something new on the tablet clipboard? (the flag can be missed, the clipboard's own timestamp cannot)
        if (!clipDirty && clipTs() <= syncedTs) return
        val d = try { cm?.primaryClipDescription } catch (e: Exception) { null }
        if (d != null && !isTextClip(d)) { clipDirty = false; syncedTs = maxOf(syncedTs, clipTs()); return }   // a picture / file, not text
        val t = readClipSafe()
        if (t != null) { syncedTs = maxOf(syncedTs, clipTs()); pushClip(t); return }
        try {
            startActivity(Intent(this, ClipReadActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (e: Exception) { }
    }

    /** Called by [ClipReadActivity] (it briefly has focus, so Android lets it read the clipboard). */
    private var clipRetry = 0
    fun onClipRead(text: String?) {
        ui.post {
            if (text != null) { clipRetry = 0; syncedTs = maxOf(syncedTs, clipTs()); pushClip(text) }
            else if (clipRetry < 2 && (clipDirty || clipTs() > syncedTs)) {
                clipRetry++
                ui.postDelayed({ pushTabletClipNow() }, 500)         // the helper screen never got focus: try again
            } else { clipRetry = 0 }
        }
    }

    // ---------------------------------------------------------------- pointer overlay
    private fun showCursor() {
        if (cursorShown) return
        val d = resources.displayMetrics.density
        val v = cursor ?: CursorView(this, d).also { cursor = it }
        val lp = WindowManager.LayoutParams(
            (26 * d).toInt(), (30 * d).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = cx.toInt(); lp.y = cy.toInt()
        try { wm?.addView(v, lp); cursorShown = true } catch (e: Exception) { }
    }

    private fun moveCursor() {
        val v = cursor ?: return
        if (!cursorShown) return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        lp.x = cx.toInt(); lp.y = cy.toInt()
        try { wm?.updateViewLayout(v, lp) } catch (e: Exception) { }
        moveDragChip()
    }

    // ---------------------------------------------------------------- the chip that follows the pointer while files are carried
    private var chip: ChipView? = null
    private var chipShown = false

    /** [text] = what to show ("report.pdf", "3 files"), null = hide it. Safe to call from the main thread at any time. */
    fun showDragChip(text: String?) {
        if (text == null) { hideDragChip(); return }
        if (!remote) return
        val d = resources.displayMetrics.density
        val v = chip ?: ChipView(this, d).also { chip = it }
        v.text = text
        v.requestLayout()
        v.invalidate()
        if (chipShown) { moveDragChip(); return }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = cx.toInt() + (14 * d).toInt(); lp.y = cy.toInt() + (22 * d).toInt()
        try { wm?.addView(v, lp); chipShown = true } catch (e: Exception) { }
    }

    private fun moveDragChip() {
        val v = chip ?: return
        if (!chipShown) return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val d = resources.displayMetrics.density
        lp.x = (cx + 14 * d).toInt(); lp.y = (cy + 22 * d).toInt()
        try { wm?.updateViewLayout(v, lp) } catch (e: Exception) { }
    }

    private fun hideDragChip() {
        if (!chipShown) return
        try { wm?.removeView(chip) } catch (e: Exception) { }
        chipShown = false
    }

    private class ChipView(ctx: android.content.Context, private val d: Float) : View(ctx) {
        var text: String = ""
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 30, 90, 200) }
        private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13f * d }
        override fun onMeasure(w: Int, h: Int) {
            val tw = fg.measureText(text).toInt()
            setMeasuredDimension(tw + (22 * d).toInt(), (30 * d).toInt())
        }
        override fun onDraw(c: Canvas) {
            c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 8 * d, 8 * d, bg)
            c.drawText(text, 11 * d, height / 2f + fg.textSize / 3f, fg)
        }
    }

    private fun hideCursor() {
        if (!cursorShown) return
        try { wm?.removeView(cursor) } catch (e: Exception) { }
        cursorShown = false
    }

    private class CursorView(ctx: android.content.Context, private val d: Float) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.6f * d; strokeJoin = Paint.Join.ROUND
        }
        private val arrow = Path()
        override fun onDraw(c: Canvas) {
            arrow.reset()
            val o = 1.5f * d
            arrow.moveTo(o, o)
            arrow.lineTo(o, o + 19 * d)
            arrow.lineTo(o + 4.6f * d, o + 14.6f * d)
            arrow.lineTo(o + 8.2f * d, o + 22 * d)
            arrow.lineTo(o + 11f * d, o + 20.7f * d)
            arrow.lineTo(o + 7.6f * d, o + 13.4f * d)
            arrow.lineTo(o + 13.5f * d, o + 13.4f * d)
            arrow.close()
            c.drawPath(arrow, fill)
            c.drawPath(arrow, edge)
        }
    }
}
