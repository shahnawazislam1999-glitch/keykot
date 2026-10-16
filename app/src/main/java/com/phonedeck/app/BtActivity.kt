package com.phonedeck.app

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts

/** Bluetooth mode: the tablet becomes a real keyboard + mouse for the PC. No network, no server. */
class BtActivity : ComponentActivity(), TouchpadView.Listener {

    private class K(val label: String, val usage: Int = 0, val mod: Int = 0, val weight: Float = 1f, val small: Boolean = false)

    private lateinit var hid: HidManager
    private lateinit var status: TextView
    private lateinit var pad: TouchpadView
    private lateinit var kbBox: LinearLayout
    private var statusText = "Starting…"
    private val handler = Handler(Looper.getMainLooper())

    // keyboard state
    private var latchedMods = 0
    private val held = LinkedHashSet<Int>()
    private val modViews = ArrayList<Pair<View, Int>>()

    // key captions that change with Shift / Caps Lock
    private class SV(val tv: TextView, val normal: String, val shifted: String, val letter: Boolean)
    private val shiftViews = ArrayList<SV>()
    private var capsView: TextView? = null
    private var capsOn = false
    private val shiftedLabel = mapOf(
        "`" to "~", "1" to "!", "2" to "@", "3" to "#", "4" to "$", "5" to "%", "6" to "^", "7" to "&",
        "8" to "*", "9" to "(", "0" to ")", "-" to "_", "=" to "+", "[" to "{", "]" to "}", "\\" to "|",
        ";" to ":", "'" to "\"", "," to "<", "." to ">", "/" to "?"
    )

    // mouse state
    private var btnL = false
    private var btnM = false
    private var btnR = false
    private var padDrag = false
    private var pdx = 0f
    private var pdy = 0f
    private var pwheel = 0f
    private var scheduled = false
    private val flushRunnable = Runnable { flushMouse() }

    private var lastBack = 0L

    // pen buttons (Xiaomi Smart Pen mode)
    private var penL = false
    private var penR = false

    // type layout (alphabet-only keyboard for two-hand typing)
    private lateinit var mainArea: View
    private lateinit var typeLayer: View
    private lateinit var typeStage: FrameLayout
    private var typeMode = false
    private var typeSym = false
    private var typeAdjust = false
    private var tMarks = true
    private var tShift = 0            // 0 off, 1 next key only, 2 caps lock
    private var tShiftDown = false
    private var tShiftIgnore = false
    private var tUsed = false
    private var tShiftT0 = 0L
    private var tLastTap = 0L
    private var tTmp = 0
    private val typeBlocks = ArrayList<TBlock>()
    private val typeGeo = HashMap<String, Geo>()
    private val typeLetters = ArrayList<Pair<TextView, String>>()
    private val typeShiftViews = ArrayList<TextView>()

    // word suggestions
    private lateinit var words: WordStore
    private val word = StringBuilder()
    private val sugViews = ArrayList<TextView>()
    private var sugRow: LinearLayout? = null

    private fun suggestOn() = Prefs.of(this).getBoolean("sugg", true)

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> startBluetooth() }
    private val enableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            val a = getSystemService(BluetoothManager::class.java)?.adapter
            if (a?.isEnabled == true) hid.start() else setStatus("Bluetooth is off. Turn it on and reopen this screen.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.of(this).edit().putString("mode", "bt").apply()
        words = WordStore(this)
        typeMode = Prefs.of(this).getBoolean("typemode", false)
        hid = HidManager(this) { text -> runOnUiThread { setStatus(text) } }
        buildUi()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBack < 2000) finish()
                else {
                    lastBack = now
                    Toast.makeText(this@BtActivity, "Press back again to disconnect", Toast.LENGTH_SHORT).show()
                }
            }
        })
        requestBluetooth()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildUi()
    }

    // ------------------------------------------------------------------ UI
    private fun buildUi() {
        setContentView(R.layout.activity_bt)
        setupFullscreen(findViewById(R.id.root))

        status = findViewById(R.id.status)
        status.text = statusText

        pad = findViewById(R.id.pad)
        pad.listener = this
        applyPadSettings()

        kbBox = findViewById(R.id.keyboard)
        buildKeyboard()

        findViewById<View>(R.id.visible).setOnClickListener { makeVisible() }
        findViewById<View>(R.id.devices).setOnClickListener { showDevices() }
        findViewById<View>(R.id.settings).setOnClickListener { showSettings() }
        bindMouseButton(findViewById(R.id.btnL), 0)
        bindMouseButton(findViewById(R.id.btnM), 1)
        bindMouseButton(findViewById(R.id.btnR), 2)
        applyMouseButtons()

        mainArea = findViewById(R.id.mainArea)
        typeLayer = findViewById(R.id.typeLayer)
        typeStage = findViewById(R.id.typeStage)
        findViewById<View>(R.id.penBtn).setOnClickListener {
            val p = Prefs.of(this)
            val on = !p.getBoolean("pen", false)
            p.edit().putBoolean("pen", on).apply()
            applyPadSettings()
            refreshTopButtons()
            Toast.makeText(this, if (on) "Pen mode ON: pen on the touchpad = hold left button" else "Pen mode off", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.typeBtn).setOnClickListener {
            typeMode = !typeMode
            Prefs.of(this).edit().putBoolean("typemode", typeMode).apply()
            releaseAll()
            applyMode()
        }
        buildTypeUi()
        applyMode()
    }

    /** Show or hide the L / M / R row under the touchpad (Settings gear). */
    private fun applyMouseButtons() {
        findViewById<View>(R.id.mouseBtns).visibility =
            if (Prefs.of(this).getBoolean("mousebtns", true)) View.VISIBLE else View.GONE
    }

    private fun setStatus(t: String) {
        statusText = t
        if (::status.isInitialized) status.text = t
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun applyPadSettings() {
        val p = Prefs.of(this)
        pad.sens = p.getFloat("sens", 1.6f)
        pad.scrollFactor = p.getFloat("scroll", 1.2f)
        pad.tapGapMs = p.getFloat("tapGap", 200f).toLong()
        pad.dragHoldMs = p.getFloat("dragHold", 3000f).toLong()
        pad.natural = p.getBoolean("natural", true)
        pad.gestures = p.getBoolean("gest", true)
        pad.penMode = p.getBoolean("pen", false)
        pad.penSpeed = p.getFloat("penSpeed", 1.5f)
        pad.penSmooth = p.getFloat("penSmooth", 0.5f)
        pad.penHover = p.getBoolean("penHover", true)
        tMarks = p.getBoolean("tmarks", true)
    }

    // ------------------------------------------------------------------ keyboard
    private fun letter(c: Char) = K(c.toString(), 0x04 + (c - 'A'))

    private fun buildKeyboard() {
        kbBox.removeAllViews()
        modViews.clear()
        shiftViews.clear()
        capsView = null
        sugViews.clear()

        val r0 = listOf(K("Esc", 0x29, small = true)) +
            (0..11).map { K("F${it + 1}", 0x3A + it, small = true) } +
            listOf(K("Del", 0x4C, small = true))
        val r1 = listOf(K("`", 0x35)) +
            "1234567890".mapIndexed { i, c -> K(c.toString(), 0x1E + i) } +
            listOf(K("-", 0x2D), K("=", 0x2E), K("⌫", 0x2A, weight = 1.8f), K("Home", 0x4A, small = true))
        val r2 = listOf(K("Tab", 0x2B, weight = 1.5f, small = true)) +
            "QWERTYUIOP".map { letter(it) } +
            listOf(K("[", 0x2F), K("]", 0x30), K("\\", 0x31, weight = 1.5f), K("PgUp", 0x4B, small = true))
        val r3 = listOf(K("Caps", 0x39, weight = 1.8f, small = true)) +
            "ASDFGHJKL".map { letter(it) } +
            listOf(K(";", 0x33), K("'", 0x34), K("Enter", 0x28, weight = 2.2f, small = true), K("PgDn", 0x4E, small = true))
        val r4 = listOf(K("Shift", mod = 0x02, weight = 2.4f, small = true)) +
            "ZXCVBNM".map { letter(it) } +
            listOf(K(",", 0x36), K(".", 0x37), K("/", 0x38), K("Shift", mod = 0x20, weight = 1.8f, small = true), K("End", 0x4D, small = true))
        val r5 = listOf(
            K("Ctrl", mod = 0x01, weight = 1.3f, small = true), K("Win", mod = 0x08, weight = 1.1f, small = true),
            K("Alt", mod = 0x04, weight = 1.1f, small = true), K("", 0x2C, weight = 5.5f),
            K("Alt", mod = 0x40, weight = 1.1f, small = true), K("Ctrl", mod = 0x10, weight = 1.3f, small = true),
            K("◀", 0x50), K("▼", 0x51), K("▲", 0x52), K("▶", 0x4F), K("Ins", 0x49, small = true)
        )

        kbBox.addView(buildSuggestionRow())

        for (row in listOf(r0, r1, r2, r3, r4, r5)) {
            val ll = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            for (k in row) ll.addView(keyView(k))
            kbBox.addView(ll)
        }
        refreshMods()
        refreshSuggestions()
    }

    private fun keyView(k: K): TextView {
        val tv = TextView(this)
        tv.text = k.label
        tv.gravity = Gravity.CENTER
        tv.maxLines = 1
        tv.setTextColor(getColor(R.color.txt))
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (k.small) 11f else 15f)
        tv.setBackgroundResource(R.drawable.bg_key)
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, k.weight)
        lp.setMargins(dp(2), dp(2), dp(2), dp(2))
        tv.layoutParams = lp
        if (k.mod != 0) modViews.add(Pair(tv, k.mod))
        if (k.usage == 0x39) capsView = tv
        if (k.mod == 0) {
            if (k.usage in 0x04..0x1D) shiftViews.add(SV(tv, k.label.lowercase(), k.label.uppercase(), true))
            else shiftedLabel[k.label]?.let { shiftViews.add(SV(tv, k.label, it, false)) }
        }

        tv.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (k.mod != 0) toggleMod(k.mod) else keyDown(k.usage)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    if (k.mod == 0) keyUp(k.usage)
                }
            }
            true
        }
        return tv
    }

    private fun typeShiftMask(): Int = if (tShift > 0 || tShiftDown || tTmp > 0) 0x02 else 0

    private fun pushKeyboard() = hid.sendKeyboard(latchedMods or typeShiftMask(), held.toList())

    private fun toggleMod(bit: Int) {
        latchedMods = latchedMods xor bit
        refreshMods()
        pushKeyboard()
    }

    private fun refreshMods() {
        for ((v, bit) in modViews) v.isSelected = (latchedMods and bit) != 0
        // Letters show small or CAPITAL, symbols show their shifted form, so you can see what will be typed
        val shift = (latchedMods and 0x22) != 0
        for (e in shiftViews) {
            val up = if (e.letter) shift != capsOn else shift
            e.tv.text = if (up) e.shifted else e.normal
        }
        capsView?.isSelected = capsOn
    }

    private fun keyDown(usage: Int) {
        if (usage == 0x39) { capsOn = !capsOn; refreshMods() }
        trackKey(usage)
        held.add(usage)
        pushKeyboard()
    }

    private fun keyUp(usage: Int) {
        held.remove(usage)
        if (latchedMods != 0 && held.isEmpty()) {     // modifiers are one-shot: tap Ctrl, then C
            latchedMods = 0
            refreshMods()
        }
        pushKeyboard()
    }

    private fun releaseAll() {
        held.clear()
        latchedMods = 0
        if (::kbBox.isInitialized) refreshMods()
        hid.sendKeyboard(0, emptyList())
        btnL = false; btnM = false; btnR = false; padDrag = false; penL = false; penR = false
        tShift = 0; tShiftDown = false; tShiftIgnore = false; tUsed = false; tTmp = 0
        if (::typeStage.isInitialized) refreshTypeLabels()
        resetWord()
        hid.sendMouse(0, 0, 0, 0)
    }

    // ------------------------------------------------------------------ word suggestions
    private fun buildSuggestionRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.8f)
        }
        sugRow = row
        row.visibility = if (suggestOn()) View.VISIBLE else View.GONE
        repeat(4) {
            val tv = TextView(this)
            tv.gravity = Gravity.CENTER
            tv.maxLines = 1
            tv.ellipsize = android.text.TextUtils.TruncateAt.END
            tv.setTextColor(getColor(R.color.txt))
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
            tv.layoutParams = lp
            tv.setOnClickListener { (tv.tag as? String)?.let { applySuggestion(it) } }
            tv.setOnLongClickListener {
                (tv.tag as? String)?.let {
                    words.forget(it)
                    refreshSuggestions()
                    Toast.makeText(this, "Removed \"$it\"", Toast.LENGTH_SHORT).show()
                }
                true
            }
            sugViews.add(tv)
            row.addView(tv)
        }
        return row
    }

    private fun refreshSuggestions() {
        val list = if (word.isEmpty()) emptyList() else words.suggest(word.toString(), sugViews.size)
        for ((i, tv) in sugViews.withIndex()) {
            val w = list.getOrNull(i)
            tv.tag = w
            tv.text = w ?: ""
            if (w != null) tv.setBackgroundResource(R.drawable.bg_key) else tv.background = null
        }
    }

    private fun usageChar(u: Int): Char? = when (u) {
        in 0x04..0x1D -> 'a' + (u - 0x04)
        in 0x1E..0x26 -> '1' + (u - 0x1E)
        0x27 -> '0'
        else -> null
    }

    /** Follows what you type so the bar can suggest words. Ctrl/Alt/Win shortcuts and navigation keys just reset it. */
    private fun trackKey(usage: Int) {
        if (!suggestOn()) { word.setLength(0); return }   // off: nothing is tracked or saved
        if ((latchedMods and 0x5D) != 0) { resetWord(); return }
        val c = usageChar(usage)
        when {
            c != null -> word.append(c)
            usage == 0x34 && word.isNotEmpty() && (latchedMods and 0x22) == 0 -> word.append('\'')
            usage == 0x39 -> {}   // Caps Lock keeps the current word
            usage == 0x2A -> if (word.isNotEmpty()) word.setLength(word.length - 1)
            usage == 0x28 || usage in 0x2B..0x38 -> finishWord()   // enter, tab, space, punctuation
            else -> word.setLength(0)
        }
        refreshSuggestions()
    }

    private fun finishWord() {
        if (word.isNotEmpty()) words.learn(word.toString())
        word.setLength(0)
    }

    private fun resetWord() {
        word.setLength(0)
        if (sugViews.isNotEmpty()) refreshSuggestions()
    }

    private fun typeChar(ch: Char) {
        val u = when (ch) {
            in 'a'..'z' -> 0x04 + (ch - 'a')
            in '1'..'9' -> 0x1E + (ch - '1')
            '0' -> 0x27
            ' ' -> 0x2C
            '\'' -> 0x34
            else -> return
        }
        hid.sendKeyboard(0, listOf(u))
        hid.sendKeyboard(0, emptyList())
    }

    /** Tap on a suggestion: types the rest of the word plus a space. */
    private fun applySuggestion(w: String) {
        val typed = word.toString()
        if (!w.startsWith(typed)) return
        for (ch in w.substring(typed.length) + " ") typeChar(ch)
        words.learn(w)
        word.setLength(0)
        refreshSuggestions()
        pushKeyboard()
    }

    // ------------------------------------------------------------------ mouse
    private fun curButtons(): Int =
        (if (padDrag || btnL || penL) 1 else 0) or (if (btnR || penR) 2 else 0) or (if (btnM) 4 else 0)

    private fun bindMouseButton(b: View, which: Int) {
        b.setOnTouchListener { v, ev ->
            val down = when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> false
                else -> null
            }
            if (down != null) {
                v.isPressed = down
                if (down) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                when (which) { 0 -> btnL = down; 1 -> btnM = down; else -> btnR = down }
                flushMouse()
                hid.sendMouse(curButtons(), 0, 0, 0)
            }
            true
        }
    }

    override fun onMove(dx: Float, dy: Float) { pdx += dx; pdy += dy; scheduleFlush() }

    override fun onScroll(amount: Float) { pwheel += amount; scheduleFlush() }

    override fun onClick(button: Int) {
        resetWord()
        flushMouse()
        val bit = if (button == 1) 2 else 1
        hid.sendMouse(curButtons() or bit, 0, 0, 0)
        hid.sendMouse(curButtons(), 0, 0, 0)
    }

    override fun onGesture(fingers: Int) {
        if (fingers == 3) winCombo(0x2B) else winCombo(0x16)   // 3 fingers = Win+Tab, 4 fingers = Win+S
    }

    override fun onPen(down: Boolean, right: Boolean) {
        resetWord()
        flushMouse()
        if (right) penR = down else penL = down
        hid.sendMouse(curButtons(), 0, 0, 0)
    }

    /** Win + key shortcut. The Win key goes down first so Windows sees a proper combination. */
    private fun winCombo(usage: Int) {
        resetWord()
        flushMouse()
        hid.sendKeyboard(0x08, emptyList())
        handler.postDelayed({ hid.sendKeyboard(0x08, listOf(usage)) }, 25)
        handler.postDelayed({ hid.sendKeyboard(0x08, emptyList()) }, 80)
        handler.postDelayed({ pushKeyboard() }, 110)
    }

    override fun onDrag(down: Boolean) {
        resetWord()
        flushMouse()
        padDrag = down
        hid.sendMouse(curButtons(), 0, 0, 0)
    }

    private fun scheduleFlush() {
        if (!scheduled) { scheduled = true; handler.postDelayed(flushRunnable, 8) }
    }

    private fun flushMouse() {
        scheduled = false
        handler.removeCallbacks(flushRunnable)
        var ix = pdx.toInt(); var iy = pdy.toInt(); var w = pwheel.toInt()
        pdx -= ix; pdy -= iy; pwheel -= w
        while (ix != 0 || iy != 0 || w != 0) {
            val sx = ix.coerceIn(-127, 127)
            val sy = iy.coerceIn(-127, 127)
            val sw = w.coerceIn(-127, 127)
            hid.sendMouse(curButtons(), sx, sy, sw)
            ix -= sx; iy -= sy; w -= sw
        }
    }

    // ------------------------------------------------------------------ Type layout
    // Alphabet-only keyboard for two-hand typing: F / J markers, optional split into a left-hand and a right-hand
    // block, and every block can be moved and resized from all four sides (Adjust).
    private class TK(
        val label: String, val usage: Int = 0, val weight: Float = 1f,
        val kind: Int = 0, val sh: Boolean = false, val fn: Boolean = false, val gap: Boolean = false
    )

    private class Geo(var x: Float, var y: Float, var w: Float, var h: Float)

    private class TBlock(val key: String, val frame: FrameLayout, val overlay: View, val handles: List<View>) {
        val keys = ArrayList<TypeKey>()
    }

    /** A key that can draw the small marker on top of the F and J keys. */
    private inner class TypeKey(ctx: Context) : TextView(ctx) {
        var marker = false
        var fn = false
        private val mp = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!marker || !tMarks) return
            mp.color = if (isPressed || isSelected) Color.WHITE else getColor(R.color.acc)
            val w = width * 0.34f
            val h = maxOf(dp(3).toFloat(), height * 0.05f)
            val l = (width - w) / 2f
            val t = height * 0.08f
            canvas.drawRoundRect(l, t, l + w, t + h, h / 2f, h / 2f, mp)
        }
    }

    private val KIND_SHIFT = 1
    private val KIND_LAYER = 2
    private val KIND_GLOBE = 3
    private val KIND_ENTER = 4
    private val MIN_W = 0.14f
    private val MIN_H = 0.22f

    private fun clampF(v: Float, lo: Float, hi: Float): Float = if (hi < lo) lo else v.coerceIn(lo, hi)

    private fun tLet(s: String): List<TK> = s.map { TK(it.toString(), 0x04 + (it - 'a')) }
    private fun tDig(s: String): List<TK> = s.map { TK(it.toString(), if (it == '0') 0x27 else 0x1E + (it - '1')) }
    private fun tGapKey(w: Float) = TK("", weight = w, gap = true)
    private fun tShiftKey() = TK("⇧", weight = 1.5f, kind = KIND_SHIFT, fn = true)
    private fun tBksp() = TK("⌫", 0x2A, 1.5f, fn = true)
    private fun tEnter(w: Float) = TK("⏎", 0x28, w, kind = KIND_ENTER, fn = true)
    private fun tSpace(w: Float) = TK("", 0x2C, w)
    private fun tGlobe() = TK("🌐", kind = KIND_GLOBE, fn = true)
    private fun tLayer(label: String) = TK(label, weight = 1.5f, kind = KIND_LAYER, fn = true)
    private fun tSym(usage: Int, label: String, sh: Boolean = false) = TK(label, usage, sh = sh)
    private fun tNav(usage: Int, label: String, w: Float = 1f) = TK(label, usage, w, fn = true)

    /** part: 0 = one block, 1 = left-hand block, 2 = right-hand block. */
    private fun typeLayout(sym: Boolean, part: Int): List<List<TK>> {
        val comma = TK(",", 0x36)
        val period = TK(".", 0x37)
        if (!sym) {
            return when (part) {
                1 -> listOf(
                    tDig("12345"), tLet("qwert"), tLet("asdfg"),
                    listOf(tShiftKey()) + tLet("zxcvb"),
                    listOf(tLayer("?123"), tGlobe(), tSpace(4f))
                )
                2 -> listOf(
                    tDig("67890"), tLet("yuiop"),
                    tLet("hjkl") + tEnter(1.5f),
                    tLet("nm") + comma + period + tBksp(),
                    listOf(tSpace(4f))
                )
                else -> listOf(
                    tDig("1234567890"), tLet("qwertyuiop"),
                    listOf(tGapKey(0.5f)) + tLet("asdfghjkl") + tGapKey(0.5f),
                    listOf(tShiftKey()) + tLet("zxcvbnm") + tBksp(),
                    listOf(tLayer("?123"), comma, tGlobe(), tSpace(4f), period, tEnter(1.5f))
                )
            }
        }
        val r0 = "!@#$%^&*()".mapIndexed { i, c -> tSym(if (i == 9) 0x27 else 0x1E + i, c.toString(), true) }
        val r1 = listOf(
            tSym(0x35, "`"), tSym(0x35, "~", true), tSym(0x2D, "-"), tSym(0x2D, "_", true), tSym(0x2E, "="),
            tSym(0x2E, "+", true), tSym(0x2F, "["), tSym(0x30, "]"), tSym(0x2F, "{", true), tSym(0x30, "}", true)
        )
        val r2 = listOf(
            tSym(0x33, ";"), tSym(0x33, ":", true), tSym(0x34, "'"), tSym(0x34, "\"", true), tSym(0x38, "/"),
            tSym(0x38, "?", true), tSym(0x31, "\\"), tSym(0x31, "|", true), tSym(0x36, "<", true), tSym(0x37, ">", true)
        )
        val navL = listOf(tNav(0x2B, "Tab", 1.5f), tNav(0x50, "◀"), tNav(0x52, "▲"), tNav(0x51, "▼"), tNav(0x4F, "▶"))
        val navR = listOf(tNav(0x4A, "Home"), tNav(0x4D, "End"), tNav(0x4C, "Del"), tBksp())
        return when (part) {
            1 -> listOf(r0.take(5), r1.take(5), r2.take(5), navL, listOf(tLayer("ABC"), tGlobe(), tSpace(4f)))
            2 -> listOf(r0.drop(5), r1.drop(5), r2.drop(5), navR, listOf(tSpace(2.5f), tEnter(1.5f)))
            else -> listOf(r0, r1, r2, navL + navR, listOf(tLayer("ABC"), comma, tGlobe(), tSpace(4f), period, tEnter(1.5f)))
        }
    }

    private fun geoDefault(key: String): Geo = when (key) {
        "tL" -> Geo(0.02f, 0.06f, 0.46f, 0.88f)
        "tR" -> Geo(0.52f, 0.06f, 0.46f, 0.88f)
        else -> Geo(0.03f, 0.04f, 0.94f, 0.92f)
    }

    private fun loadGeo(key: String): Geo {
        val d = geoDefault(key)
        val p = Prefs.of(this)
        val w = clampF(p.getFloat(key + "_w", d.w), MIN_W, 1f)
        val h = clampF(p.getFloat(key + "_h", d.h), MIN_H, 1f)
        val x = clampF(p.getFloat(key + "_x", d.x), 0f, 1f - w)
        val y = clampF(p.getFloat(key + "_y", d.y), 0f, 1f - h)
        return Geo(x, y, w, h)
    }

    private fun saveGeo(key: String, g: Geo) {
        Prefs.of(this).edit()
            .putFloat(key + "_x", g.x).putFloat(key + "_y", g.y)
            .putFloat(key + "_w", g.w).putFloat(key + "_h", g.h).apply()
    }

    private fun buildTypeUi() {
        typeBlocks.clear()
        typeGeo.clear()
        typeStage.addOnLayoutChangeListener { _, l, t, r, b, oL, oT, oR, oB ->
            if (r - l != oR - oL || b - t != oB - oT) placeTypeBlocks()
        }
        findViewById<View>(R.id.tAdj).setOnClickListener {
            typeAdjust = !typeAdjust
            applyTypeAdjust()
            refreshTypeButtons()
            if (typeAdjust) Toast.makeText(this, "Drag a block to move it, pull edges or corners to resize", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.tSplit).setOnClickListener {
            val p = Prefs.of(this)
            p.edit().putBoolean("tsplit", !p.getBoolean("tsplit", false)).apply()
            releaseAll()
            buildTypeBlocks()
            refreshTypeButtons()
        }
        findViewById<View>(R.id.tMarks).setOnClickListener {
            val p = Prefs.of(this)
            p.edit().putBoolean("tmarks", !p.getBoolean("tmarks", true)).apply()
            applyPadSettings()
            typeBlocks.forEach { b -> b.keys.forEach { k -> k.invalidate() } }
            refreshTypeButtons()
        }
        findViewById<View>(R.id.tReset).setOnClickListener {
            val e = Prefs.of(this).edit()
            for (k in listOf("tMain", "tL", "tR")) { e.remove(k + "_x"); e.remove(k + "_y"); e.remove(k + "_w"); e.remove(k + "_h") }
            e.apply()
            typeGeo.clear()
            placeTypeBlocks()
            Toast.makeText(this, "Typing layout reset", Toast.LENGTH_SHORT).show()
        }
        buildTypeBlocks()
        refreshTypeButtons()
    }

    private fun refreshTypeButtons() {
        val p = Prefs.of(this)
        fun mark(id: Int, on: Boolean) =
            findViewById<View>(id).setBackgroundResource(if (on) R.drawable.bg_btn_pri else R.drawable.bg_btn)
        mark(R.id.tAdj, typeAdjust)
        mark(R.id.tSplit, p.getBoolean("tsplit", false))
        mark(R.id.tMarks, p.getBoolean("tmarks", true))
    }

    private fun refreshTopButtons() {
        val p = Prefs.of(this)
        findViewById<View>(R.id.penBtn).setBackgroundResource(if (p.getBoolean("pen", false)) R.drawable.bg_btn_pri else R.drawable.bg_btn)
        findViewById<View>(R.id.typeBtn).setBackgroundResource(if (typeMode) R.drawable.bg_btn_pri else R.drawable.bg_btn)
    }

    private fun applyMode() {
        mainArea.visibility = if (typeMode) View.GONE else View.VISIBLE
        typeLayer.visibility = if (typeMode) View.VISIBLE else View.GONE
        if (typeMode) typeStage.post { placeTypeBlocks() }
        refreshTopButtons()
    }

    private fun buildTypeBlocks() {
        typeStage.removeAllViews()
        typeBlocks.clear()
        typeLetters.clear()
        typeShiftViews.clear()
        if (Prefs.of(this).getBoolean("tsplit", false)) {
            addTypeBlock("tL", typeLayout(typeSym, 1))
            addTypeBlock("tR", typeLayout(typeSym, 2))
        } else {
            addTypeBlock("tMain", typeLayout(typeSym, 0))
        }
        placeTypeBlocks()
        refreshTypeLabels()
        applyTypeAdjust()
    }

    private fun addTypeBlock(key: String, rows: List<List<TK>>) {
        val gapDp = Prefs.of(this).getFloat("tgap", 3f)
        val margin = (resources.displayMetrics.density * gapDp / 2f).toInt()
        val frame = FrameLayout(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val keyViews = ArrayList<TypeKey>()
        for (row in rows) {
            val ll = LinearLayout(this)
            ll.orientation = LinearLayout.HORIZONTAL
            ll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            for (k in row) {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, k.weight)
                if (k.gap) {
                    val sp = View(this)
                    sp.layoutParams = lp
                    ll.addView(sp)
                } else {
                    lp.setMargins(margin, margin, margin, margin)
                    val kv = typeKeyView(k)
                    kv.layoutParams = lp
                    keyViews.add(kv)
                    ll.addView(kv)
                }
            }
            col.addView(ll)
        }
        frame.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Adjust mode: a tinted layer you can drag (move) plus handles on every edge and corner (resize)
        val overlay = View(this)
        overlay.setBackgroundColor(0x334DA3FF)
        overlay.visibility = View.GONE
        frame.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val handles = ArrayList<View>()
        val edge = dp(26)
        val long = dp(120)
        val corner = dp(44)
        val specs = listOf(
            Triple("n", FrameLayout.LayoutParams(long, edge, Gravity.TOP or Gravity.CENTER_HORIZONTAL), 0),
            Triple("s", FrameLayout.LayoutParams(long, edge, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL), 0),
            Triple("w", FrameLayout.LayoutParams(edge, long, Gravity.START or Gravity.CENTER_VERTICAL), 0),
            Triple("e", FrameLayout.LayoutParams(edge, long, Gravity.END or Gravity.CENTER_VERTICAL), 0),
            Triple("nw", FrameLayout.LayoutParams(corner, corner, Gravity.TOP or Gravity.START), 1),
            Triple("ne", FrameLayout.LayoutParams(corner, corner, Gravity.TOP or Gravity.END), 1),
            Triple("sw", FrameLayout.LayoutParams(corner, corner, Gravity.BOTTOM or Gravity.START), 1),
            Triple("se", FrameLayout.LayoutParams(corner, corner, Gravity.BOTTOM or Gravity.END), 1)
        )
        for ((mode, lp, round) in specs) {
            val h = View(this)
            val bg = GradientDrawable()
            bg.setColor(getColor(R.color.acc))
            bg.cornerRadius = if (round == 1) dp(22).toFloat() else dp(8).toFloat()
            h.background = bg
            h.visibility = View.GONE
            h.setOnTouchListener(adjustListener(key, mode))
            frame.addView(h, lp)
            handles.add(h)
        }
        overlay.setOnTouchListener(adjustListener(key, "move"))

        val block = TBlock(key, frame, overlay, handles)
        block.keys.addAll(keyViews)
        typeBlocks.add(block)
        typeStage.addView(frame, FrameLayout.LayoutParams(dp(100), dp(100)))
    }

    private fun typeKeyView(k: TK): TypeKey {
        val tv = TypeKey(this)
        tv.text = k.label
        tv.gravity = Gravity.CENTER
        tv.maxLines = 1
        tv.fn = k.fn
        tv.marker = (k.usage == 0x09 || k.usage == 0x0D) && k.kind == 0 && k.label.length == 1   // F and J
        tv.setTextColor(if (k.kind == KIND_ENTER) Color.WHITE else getColor(R.color.txt))
        tv.setBackgroundResource(if (k.kind == KIND_ENTER) R.drawable.bg_btn_pri else R.drawable.bg_key)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (k.fn) 13f else 20f)
        if (k.kind == 0 && k.label.length == 1 && k.usage in 0x04..0x1D) typeLetters.add(Pair(tv, k.label))
        if (k.kind == KIND_SHIFT) typeShiftViews.add(tv)
        tv.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(
                        if (tv.marker && tMarks) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.KEYBOARD_TAP
                    )
                    typeKeyDown(k)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    typeKeyUp(k)
                }
            }
            true
        }
        return tv
    }

    private fun refreshTypeLabels() {
        val up = tShift > 0 || tShiftDown
        for ((tv, lower) in typeLetters) tv.text = if (up) lower.uppercase() else lower
        for (tv in typeShiftViews) tv.isSelected = up
    }

    private fun placeTypeBlocks() {
        val w = typeStage.width
        val h = typeStage.height
        if (w <= 0 || h <= 0) return
        for (b in typeBlocks) {
            val g = typeGeo.getOrPut(b.key) { loadGeo(b.key) }
            val lp = b.frame.layoutParams as FrameLayout.LayoutParams
            lp.width = (g.w * w).toInt()
            lp.height = (g.h * h).toInt()
            lp.leftMargin = (g.x * w).toInt()
            lp.topMargin = (g.y * h).toInt()
            lp.gravity = Gravity.TOP or Gravity.START
            b.frame.layoutParams = lp
            val rowH = lp.height / 5f
            val lo = 11f * resources.displayMetrics.density
            val hi = 34f * resources.displayMetrics.density
            for (k in b.keys) {
                val px = if (k.fn) rowH * 0.26f else rowH * 0.40f
                k.setTextSize(TypedValue.COMPLEX_UNIT_PX, clampF(px, lo, hi))
            }
        }
    }

    private fun applyTypeAdjust() {
        val vis = if (typeAdjust) View.VISIBLE else View.GONE
        for (b in typeBlocks) {
            b.overlay.visibility = vis
            for (h in b.handles) h.visibility = vis
        }
    }

    private fun adjustListener(key: String, mode: String): View.OnTouchListener {
        var sx = 0f
        var sy = 0f
        var g0 = Geo(0f, 0f, 0.5f, 0.5f)
        return View.OnTouchListener { v, ev ->
            val sw = maxOf(1f, typeStage.width.toFloat())
            val sh = maxOf(1f, typeStage.height.toFloat())
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = ev.rawX
                    sy = ev.rawY
                    val g = typeGeo.getOrPut(key) { loadGeo(key) }
                    g0 = Geo(g.x, g.y, g.w, g.h)
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - sx) / sw
                    val dy = (ev.rawY - sy) / sh
                    var x = g0.x
                    var y = g0.y
                    var w = g0.w
                    var h = g0.h
                    if (mode == "move") {
                        x = clampF(x + dx, 0f, 1f - w)
                        y = clampF(y + dy, 0f, 1f - h)
                    } else {
                        if (mode.contains('w')) { val nx = clampF(x + dx, 0f, x + w - MIN_W); w += x - nx; x = nx }
                        if (mode.contains('e')) w = clampF(w + dx, MIN_W, 1f - x)
                        if (mode.contains('n')) { val ny = clampF(y + dy, 0f, y + h - MIN_H); h += y - ny; y = ny }
                        if (mode.contains('s')) h = clampF(h + dy, MIN_H, 1f - y)
                    }
                    typeGeo[key] = Geo(x, y, w, h)
                    placeTypeBlocks()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    typeGeo[key]?.let { saveGeo(key, it) }
                }
            }
            true
        }
    }

    // Shift: tap = next key only, double-tap = Caps Lock, hold = capitals while held (works with two thumbs)
    private fun typeShiftDown() {
        val now = SystemClock.elapsedRealtime()
        tShiftIgnore = false
        if (tShift == 2) { tShift = 0; tShiftIgnore = true }
        else if (tShift == 1 && now - tLastTap < 350) { tShift = 2; tLastTap = 0; tShiftIgnore = true }
        else if (tShift == 1) { tShift = 0; tShiftIgnore = true }
        else { tShiftDown = true; tUsed = false; tShiftT0 = now }
        pushKeyboard()
        refreshTypeLabels()
    }

    private fun typeShiftUp() {
        if (tShiftIgnore) { tShiftIgnore = false; return }
        tShiftDown = false
        val now = SystemClock.elapsedRealtime()
        if (!tUsed && tShift == 0 && now - tShiftT0 < 400) { tShift = 1; tLastTap = now }
        pushKeyboard()
        refreshTypeLabels()
    }

    private fun typeKeyDown(k: TK) {
        when (k.kind) {
            KIND_LAYER -> { typeSym = !typeSym; handler.post { buildTypeBlocks() } }
            KIND_GLOBE -> winCombo(0x2C)           // Win+Space switches the input language on Windows
            KIND_SHIFT -> typeShiftDown()
            else -> {
                if (k.sh) tTmp++                    // symbols like ! @ # need Shift
                tUsed = true
                held.add(k.usage)
                pushKeyboard()
            }
        }
    }

    private fun typeKeyUp(k: TK) {
        when (k.kind) {
            KIND_LAYER, KIND_GLOBE -> {}
            KIND_SHIFT -> typeShiftUp()
            else -> {
                held.remove(k.usage)
                if (k.sh && tTmp > 0) tTmp--
                if (tShift == 1 && !tShiftDown) { tShift = 0; refreshTypeLabels() }   // "next key only" ends here
                pushKeyboard()
            }
        }
    }

    // ------------------------------------------------------------------ Bluetooth setup
    private fun requestBluetooth() {
        if (Build.VERSION.SDK_INT >= 31) {
            val need = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (need.isNotEmpty()) { permLauncher.launch(need.toTypedArray()); return }
        }
        startBluetooth()
    }

    private fun startBluetooth() {
        val a = getSystemService(BluetoothManager::class.java)?.adapter
        if (a == null) { setStatus("This device has no Bluetooth."); return }
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            setStatus("Bluetooth permission denied. Allow Nearby devices for PhoneDeck in Settings > Apps.")
            return
        }
        if (!a.isEnabled) {
            try { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            catch (e: Exception) { setStatus("Bluetooth is off. Turn it on and reopen this screen.") }
            return
        }
        hid.start()
    }

    private fun makeVisible() {
        try {
            startActivity(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Could not make visible: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun showDevices() {
        val list = hid.bonded()
        val b = AlertDialog.Builder(this)
        b.setTitle("Connect to a paired PC")
        if (list.isEmpty()) {
            b.setMessage(
                "No paired devices yet.\n\nTap Make visible here, then on the PC open Settings > Bluetooth & devices > " +
                    "Add device > Bluetooth and choose this tablet."
            )
        } else {
            val names = list.map { hid.nameOf(it) }.toTypedArray()
            b.setItems(names) { _, i -> hid.connectTo(list[i]) }
        }
        b.setNegativeButton("Disconnect") { _, _ -> hid.disconnect() }
        b.setPositiveButton("Close", null)
        b.show()
    }

    private fun showSettings() {
        val p = Prefs.of(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), dp(8))

        fun row(label: String, lo: Float, hi: Float, step: Float, key: String, def: Float, fmt: (Float) -> String) {
            val cur = p.getFloat(key, def)
            val tv = TextView(this)
            tv.text = "$label: ${fmt(cur)}"
            tv.setTextColor(getColor(R.color.txt))
            tv.setPadding(0, dp(10), 0, 0)
            val sb = SeekBar(this)
            sb.max = Math.round((hi - lo) / step)
            sb.progress = Math.round((cur - lo) / step)
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) {
                    val value = lo + v * step
                    tv.text = "$label: ${fmt(value)}"
                    if (fromUser) { p.edit().putFloat(key, value).apply(); applyPadSettings(); if (key == "tgap") buildTypeBlocks() }
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
            box.addView(tv)
            box.addView(sb)
        }
        row("Pointer speed", 0.5f, 4f, 0.1f, "sens", 1.6f) { "%.1f".format(it) }
        row("Scroll speed", 0.3f, 4f, 0.1f, "scroll", 1.2f) { "%.1f".format(it) }
        row("Next tap window", 100f, 1000f, 50f, "tapGap", 200f) { "${it.toInt()} ms" }
        row("Hold to drag (2nd tap)", 500f, 5000f, 100f, "dragHold", 3000f) { "%.1f s".format(it / 1000f) }

        row("Pen speed (Xiaomi Smart Pen)", 0.5f, 4f, 0.1f, "penSpeed", 1.5f) { "%.1f".format(it) }
        row("Pen smoothing", 0f, 1f, 0.05f, "penSmooth", 0.5f) { "${(it * 100).toInt()}%" }
        row("Type keyboard key gap", 0f, 12f, 1f, "tgap", 3f) { "${it.toInt()} dp" }

        fun check(label: String, key: String, def: Boolean, after: () -> Unit) {
            val cb = CheckBox(this)
            cb.text = label
            cb.setTextColor(getColor(R.color.txt))
            cb.isChecked = p.getBoolean(key, def)
            cb.setOnCheckedChangeListener { _, c -> p.edit().putBoolean(key, c).apply(); after() }
            box.addView(cb)
        }
        check("Pen mode: pen on the touchpad = hold left button", "pen", false) { applyPadSettings(); refreshTopButtons() }
        check("Cursor follows the pen while it hovers", "penHover", true) { applyPadSettings() }
        check("3-finger tap = Win+Tab, 4-finger tap = Win+S", "gest", true) { applyPadSettings() }
        check("Type layout: F / J markers", "tmarks", true) { applyPadSettings(); typeBlocks.forEach { b -> b.keys.forEach { k -> k.invalidate() } } }
        check("Type layout: split into two halves", "tsplit", false) { buildTypeBlocks(); refreshTypeButtons() }

        val sug = CheckBox(this)
        sug.text = "Word suggestions"
        sug.setTextColor(getColor(R.color.txt))
        sug.isChecked = suggestOn()
        sug.setOnCheckedChangeListener { _, c ->
            p.edit().putBoolean("sugg", c).apply()
            sugRow?.visibility = if (c) View.VISIBLE else View.GONE
            word.setLength(0)
            refreshSuggestions()
        }
        box.addView(sug)

        val mb = CheckBox(this)
        mb.text = "Show L / M / R buttons under the touchpad"
        mb.setTextColor(getColor(R.color.txt))
        mb.isChecked = p.getBoolean("mousebtns", true)
        mb.setOnCheckedChangeListener { _, c -> p.edit().putBoolean("mousebtns", c).apply(); applyMouseButtons() }
        box.addView(mb)

        val nat = CheckBox(this)
        nat.text = "Natural scrolling"
        nat.setTextColor(getColor(R.color.txt))
        nat.isChecked = p.getBoolean("natural", true)
        nat.setOnCheckedChangeListener { _, c -> p.edit().putBoolean("natural", c).apply(); applyPadSettings() }
        box.addView(nat)

        val sv = ScrollView(this)
        sv.addView(box)
        AlertDialog.Builder(this).setTitle("Touchpad settings").setView(sv).setPositiveButton("Done", null).show()
    }

    // ------------------------------------------------------------------ lifecycle
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        releaseAll()
        words.flush()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hid.stop()
        super.onDestroy()
    }
}
