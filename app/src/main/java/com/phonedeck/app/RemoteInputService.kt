package com.phonedeck.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
 *  - The PC pushes events over [TabletLink]; this service draws a pointer (overlay) and performs them
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
    private var link: TabletLink? = null
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

    // wheel
    private var wheelDx = 0f
    private var wheelDy = 0f
    private var wheelBusy = false

    // clipboard
    private var clipFromPc: String? = null
    private var clipDirty = false
    private var selfClipAt = 0L
    private var lastSentClip = ""
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (System.currentTimeMillis() - selfClipAt > 1500) clipDirty = true
    }

    val isLinked: Boolean get() = link?.connected == true

    // ---------------------------------------------------------------- keep-alive / reconnect
    private var lastBeat = 0L
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private val finder: AutoFinder by lazy {
        AutoFinder(this, { Prefs.of(this).getString("link", "") ?: "" }, { ip ->
            val cur = Prefs.of(this).getString("link", "") ?: ""
            if (cur.isNotEmpty()) {
                val u = LinkUtil.withHost(cur, ip)
                Prefs.of(this).edit().putString("link", u).apply()
                LinkHistory.add(this, u)
                link?.kick()
            }
        }, { })
    }
    private val selfBeat = object : Runnable {
        override fun run() { heartbeat(); ui.postDelayed(this, 2000) }
    }

    /** Called every ~2 s (by [KeepAliveService] and by our own timer): ping, reconnect, find the PC if it moved. */
    fun heartbeat() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBeat < 1500L) return
        lastBeat = now
        val l = link ?: return
        if (!Prefs.of(this).getBoolean("share_on", false)) return
        l.tick()
        if (l.connected) finder.reset() else finder.outage()
    }

    private fun registerNet() {
        if (netCb != null) return
        try {
            val c = getSystemService(ConnectivityManager::class.java)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = c.getNetworkCapabilities(network) ?: return
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        ui.post { finder.networkChanged(); if (link?.connected != true) link?.kick() }
                    }
                }
            }
            c.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
            netCb = cb
        } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------- lifecycle
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        try { cm?.addPrimaryClipChangedListener(clipListener) } catch (e: Exception) { }
        refreshScreen()
        registerNet()
        ui.removeCallbacks(selfBeat)
        ui.postDelayed(selfBeat, 2000)
        applyPrefs()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshScreen()
        if (link?.connected == true) sendHello()
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
        ui.removeCallbacks(selfBeat)
        try { netCb?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } } catch (e: Exception) { }
        netCb = null
        link?.stop()
        link = null
        try { cm?.removePrimaryClipChangedListener(clipListener) } catch (e: Exception) { }
        instance = null
    }

    fun applyPrefs() {
        val p = Prefs.of(this)
        gain = p.getFloat("mouse_gain", 1.6f)
        if (p.getBoolean("share_on", false)) {
            val l = link ?: TabletLink(this, ui, { onMsg(it) }, { onLinkState(it) }).also { link = it }
            l.start()
            if (l.connected) sendHello()
            KeepAlive.start(this)
        } else {
            exitRemote()
            link?.stop()
            KeepAlive.stop(this)
        }
    }

    @Suppress("DEPRECATION")
    private fun refreshScreen() {
        val dm = DisplayMetrics()
        wm?.defaultDisplay?.getRealMetrics(dm)
        if (dm.widthPixels > 0 && dm.heightPixels > 0) { sw = dm.widthPixels; sh = dm.heightPixels }
    }

    private fun sendHello() {
        refreshScreen()
        val p = Prefs.of(this)
        link?.send(JSONObject().put("t", "hello").put("w", sw).put("h", sh))
        link?.send(JSONObject().put("t", "cfg").put("edge", p.getString("pc_edge", "right"))
            .put("on", p.getBoolean("share_on", false)))
    }

    private fun onLinkState(connected: Boolean) {
        if (connected) sendHello() else exitRemote()
    }

    // ---------------------------------------------------------------- messages from the PC
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
            "clip" -> setClipFromPc(o.optString("text"))
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
        cancelDrag()
        hideCursor()
    }

    private fun leave() {
        val f = if (peerEdge == "left" || peerEdge == "right") cy / sh else cx / sw
        link?.send(JSONObject().put("t", "leave").put("f", f.toDouble().coerceIn(0.0, 1.0)))
        exitRemote()
        if (clipDirty) {
            val t = readClipSafe()
            if (t != null) pushClip(t) else {
                try {
                    startActivity(Intent(this, ClipReadActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                } catch (e: Exception) { }
            }
        }
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
            if (out) { leave(); return }
        }
        cx = rx.coerceIn(0f, (sw - 1).toFloat())
        cy = ry.coerceIn(0f, (sh - 1).toFloat())
        moveCursor()
        if (lDown && !dragging && hypot(cx - downX, cy - downY) > 10f * resources.displayMetrics.density) startDrag()
        if (dragging) pump()
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
        lDown = true; dragging = false; dragEnding = false
        downT = System.currentTimeMillis(); downX = cx; downY = cy
    }

    private fun leftUp() {
        if (!lDown) return
        lDown = false
        if (dragging) { dragEnding = true; pump(); return }
        val held = System.currentTimeMillis() - downT
        gestureAt(downX, downY, if (held >= 500) 700L else 50L)
    }

    private fun gestureAt(x: Float, y: Float, dur: Long) {
        val p = Path().apply { moveTo(x, y) }
        try {
            dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, dur)).build(), null, null)
        } catch (e: Exception) { }
    }

    private fun startDrag() {
        dragging = true; dragEnding = false; pumpBusy = false
        dragX = downX; dragY = downY; curStroke = null
    }

    /** Streams the drag as a chain of continued strokes (one in flight at a time). */
    private fun pump() {
        if (!dragging || pumpBusy) return
        val tx = cx
        val ty = cy
        if (!dragEnding && tx == dragX && ty == dragY) return
        val path = Path().apply { moveTo(dragX, dragY); lineTo(tx, ty) }
        val cont = !dragEnding
        val prev = curStroke
        val s = try {
            if (prev == null) GestureDescription.StrokeDescription(path, 0, 40, cont)
            else prev.continueStroke(path, 0, 40, cont)
        } catch (e: Exception) { finishDrag(); return }
        curStroke = s
        dragX = tx; dragY = ty
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
        dragging = false; dragEnding = false; pumpBusy = false; curStroke = null; lDown = false
    }

    private fun cancelDrag() {
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
    inner class PcInput(s: AccessibilityService) : AccessibilityService.InputMethod(s)

    private fun ic(): AccessibilityService.InputMethod.AccessibilityInputConnection? =
        if (Build.VERSION.SDK_INT >= 33) pcIme?.currentInputConnection else null

    private fun sendKey(c: AccessibilityService.InputMethod.AccessibilityInputConnection, code: Int, meta: Int = 0) {
        val t = SystemClock.uptimeMillis()
        c.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        c.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
    }

    private fun icSelected(c: AccessibilityService.InputMethod.AccessibilityInputConnection): String? {
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
                "selall" -> { if (!c.performContextMenuAction(android.R.id.selectAll)) sendKey(c, KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON); return }
                "paste" -> {
                    val pc = clipFromPc
                    if (pc != null && !clipDirty) c.commitText(pc, 1, null)
                    else if (!c.performContextMenuAction(android.R.id.paste)) sendKey(c, KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
                    return
                }
                "copy", "cut" -> {
                    val sel = icSelected(c)
                    selfClipAt = System.currentTimeMillis()
                    val id = if (k == "cut") android.R.id.cut else android.R.id.copy
                    if (!c.performContextMenuAction(id))
                        sendKey(c, if (k == "cut") KeyEvent.KEYCODE_X else KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON)
                    if (sel != null) { clipFromPc = null; pushClip(sel) } else clipDirty = true
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
                val r = rootInActiveWindow ?: return
                val n = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: r.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) ?: return
                n.refresh()
                val sel = selectedText(n)
                selfClipAt = System.currentTimeMillis()
                n.performAction(if (k == "cut") AccessibilityNodeInfo.ACTION_CUT else AccessibilityNodeInfo.ACTION_COPY)
                if (sel != null) { clipFromPc = null; pushClip(sel) } else clipDirty = true
            }
        }
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
    private fun setClipFromPc(text: String) {
        if (text.isEmpty()) return
        clipFromPc = text
        clipDirty = false
        lastSentClip = text
        selfClipAt = System.currentTimeMillis()
        try { cm?.setPrimaryClip(ClipData.newPlainText("PC", text)) } catch (e: Exception) { }
    }

    private fun readClipSafe(): String? = try {
        val c = cm?.primaryClip
        if (c != null && c.itemCount > 0) c.getItemAt(0).coerceToText(this)?.toString() else null
    } catch (e: Exception) { null }

    private fun pushClip(t: String) {
        clipDirty = false
        if (t.isEmpty() || t == lastSentClip) return
        lastSentClip = t
        link?.send(JSONObject().put("t", "clip").put("text", t.take(100_000)))
    }

    /** Called by [ClipReadActivity] (it briefly has focus, so Android lets it read the clipboard). */
    fun onClipRead(text: String?) {
        ui.post { if (text != null) pushClip(text) }
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
