package com.phonedeck.app

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
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

    private fun pushKeyboard() = hid.sendKeyboard(latchedMods, held.toList())

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
        btnL = false; btnM = false; btnR = false; padDrag = false
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
        (if (padDrag || btnL) 1 else 0) or (if (btnR) 2 else 0) or (if (btnM) 4 else 0)

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
                    if (fromUser) { p.edit().putFloat(key, value).apply(); applyPadSettings() }
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
