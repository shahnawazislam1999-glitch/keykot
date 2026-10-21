package com.phonedeck.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Settings screen for the background PC link: mouse, keyboard, clipboard and dragged files (built in code, no layout file). */
class MouseShareActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var checks: TextView
    private lateinit var diag: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1000) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float = 14f, color: Int = 0xFFE8EBF2.toInt()) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        setPadding(0, dp(10), 0, dp(4))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Prefs.of(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        val sv = ScrollView(this).apply { setBackgroundColor(0xFF0F1115.toInt()); addView(root) }
        setContentView(sv)

        root.addView(label("PC link on this tablet", 22f))
        root.addView(label("Push the PC mouse off the PC screen edge and the pointer appears here. " +
            "Your PC keyboard types here too while the pointer is on the tablet. " +
            "SCROLL LOCK on the PC always brings control back.\n" +
            "Grab a file in Explorer or on the Desktop, drag it against that screen edge and let go on the tablet: " +
            "the file is saved in Downloads/PhoneDeck.", 13f, 0xFF8B93A5.toInt()))

        status = label("", 14f, 0xFF4DA3FF.toInt())
        root.addView(status)

        val sw = Switch(this).apply {
            text = "PC mouse on this tablet: ON (off by default; switch on only when you want to use it)"
            setTextColor(0xFFE8EBF2.toInt())
            isChecked = KeepAlive.wanted(this@MouseShareActivity)
            setOnCheckedChangeListener { _, on ->
                p.edit().putBoolean("share_on", on).apply()
                if (on) {
                    if (android.os.Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
                    KeepAlive.start(this@MouseShareActivity)
                } else KeepAlive.stop(this@MouseShareActivity)
                RemoteInputService.applyPrefsNow()
                if (on) LinkHub.ensure(this@MouseShareActivity) else LinkHub.stop()
                refresh()
            }
        }
        root.addView(sw)

        val fd = Switch(this).apply {
            text = "Drag files from the PC to this tablet"
            setTextColor(0xFFE8EBF2.toInt())
            isChecked = p.getBoolean("filedrag", true)
            setOnCheckedChangeListener { _, on ->
                p.edit().putBoolean("filedrag", on).apply()
                LinkHub.sendHello()
            }
        }
        root.addView(fd)

        val td = Switch(this).apply {
            text = "Drag files from this tablet to the PC (hold the mouse on a file, push to the PC edge, let go on the PC)"
            setTextColor(0xFFE8EBF2.toInt())
            isChecked = p.getBoolean("tabletdrag", true)
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean("tabletdrag", on).apply() }
        }
        root.addView(td)
        root.addView(button("Allow \"All files access\" (needed to send files from the tablet)") { Background.requestAllFiles(this) })
        root.addView(label("Needs Android 11+. The files are found by the name your file manager shows; folders are sent with their content. " +
            "On the PC they land in the Explorer folder under the pointer, on the Desktop, or in Downloads\\PhoneDeck.", 12f, 0xFF8B93A5.toInt()))

        val sc = Switch(this).apply {
            text = "Text selected on the PC: copy it automatically when the pointer crosses to the tablet"
            setTextColor(0xFFE8EBF2.toInt())
            isChecked = p.getBoolean("pc_autocopy", true)
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean("pc_autocopy", on).apply(); LinkHub.sendHello() }
        }
        root.addView(sc)
        val sp = Switch(this).apply {
            text = "...and paste it into the next text box I tap on the tablet"
            setTextColor(0xFFE8EBF2.toInt())
            isChecked = p.getBoolean("autopaste_sel", true)
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean("autopaste_sel", on).apply() }
        }
        root.addView(sp)

        val ca = Switch(this).apply {
            text = "Send text I copy on the tablet to the PC clipboard at once (then Ctrl+V on the PC)"
            setTextColor(0xFFE8EBF2.toInt())
            isChecked = p.getBoolean("clip_always", true)
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean("clip_always", on).apply() }
        }
        root.addView(ca)

        val ap = Switch(this).apply {
            text = "PC screenshot: paste it into the focused text box (ChatGPT, search...)"
            setTextColor(0xFFE8EBF2.toInt())
            isChecked = p.getBoolean("snip_autopaste", true)
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean("snip_autopaste", on).apply() }
        }
        root.addView(ap)

        root.addView(label("Send from the tablet to the PC - always works", 16f))
        root.addView(label("Open any file manager / gallery, select files, tap Share and choose \"Send to PC (PhoneDeck)\": they land in " +
            "Downloads\\PhoneDeck on the PC. The same Share button sends selected text to the PC clipboard. " +
            "No pointer, no special permission needed.", 12f, 0xFF8B93A5.toInt()))
        root.addView(button("Pick files to send to the PC now") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            try { startActivityForResult(i, 21) } catch (e: Exception) { toast("No file picker found") }
        })
        root.addView(button("Send the tablet clipboard text to the PC now") {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            val c = try { cm?.primaryClip } catch (e: Exception) { null }
            val t = if (c != null && c.itemCount > 0) c.getItemAt(0).coerceToText(this)?.toString() else null
            if (t.isNullOrEmpty()) toast("Nothing to send: copy some text first")
            else PcSend.text(this, t) { ok, why -> toast(if (ok) "Copied to the PC clipboard: Ctrl+V there" else "Could not reach the PC: $why"); refresh() }
        })
        root.addView(button("Send a test text to the PC clipboard") {
            PcSend.text(this, "PhoneDeck test " + System.currentTimeMillis() / 1000) { ok, why ->
                toast(if (ok) "Sent. Press Ctrl+V on the PC." else "Could not reach the PC: $why"); refresh()
            }
        })
        root.addView(label("Tip: the PhoneDeck notification also has a \"Send clipboard to PC\" button: copy text, pull the notification down, tap it.",
            12f, 0xFF8B93A5.toInt()))
        if (Background.isXiaomi()) {
            root.addView(button("Xiaomi / HyperOS: allow pop-ups while in the background") { Background.openMiuiPermissions(this) })
        }
        root.addView(label("What happened last", 14f))
        diag = label("", 12f, 0xFF8B93A5.toInt())
        root.addView(diag)

        root.addView(label("Background checklist (tap a line's button to fix it)"))
        checks = label("", 13f, 0xFFE8EBF2.toInt())
        root.addView(checks)
        root.addView(button("Fix everything, step by step") { fixNext() })
        root.addView(button("Reconnect now") { LinkHub.kickNow(); refresh() })

        root.addView(label("1. Allow the accessibility service (needed to click and type for you)"))
        root.addView(button("Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(label("Find \"PhoneDeck PC mouse\" and switch it on. On Android 13+ the switch may be greyed out: " +
            "tap the button below, then ⋮ (top right) > \"Allow restricted settings\", and try again.", 12f, 0xFF8B93A5.toInt()))
        root.addView(button("Open PhoneDeck app info") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        root.addView(label("2. Keep it running forever (" + Background.manufacturer() + ")"))
        root.addView(button("Battery: allow always active") { Background.requestBattery(this) })
        root.addView(button("Notifications") { Background.requestNotifications(this) })
        root.addView(button("Show over other apps") { Background.requestOverlay(this) })
        root.addView(button(Background.manufacturer() + ": autostart / background settings") {
            if (!Background.openAutostart(this)) {
                android.widget.Toast.makeText(this, "No special screen found: set Battery to \"No restrictions\" in App info.",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        })
        root.addView(label("Also: in Recents long-press PhoneDeck and tap the lock icon; in App info set Battery saver to \"No restrictions\" " +
            "and allow Autostart. The permanent notification keeps the link alive: do not hide it. " +
            "After this, closing or swiping the app away does not stop the link.", 12f, 0xFF8B93A5.toInt()))

        root.addView(label("3. Connect to the PC"))
        root.addView(label("Connect once with Paste & Connect: the link is saved and this tablet then connects by itself, " +
            "also after a reboot. If you switch to another phone's hotspot it searches for the PC at once and follows it. " +
            "Run start-lan.bat on the PC once so it is reachable.", 12f, 0xFF8B93A5.toInt()))

        root.addView(label("4. Which PC screen edge leads to the tablet?"))
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val edges = listOf("right" to "Right", "left" to "Left", "top" to "Top", "bottom" to "Bottom")
        val cur = p.getString("pc_edge", "right")
        for ((k, name) in edges) {
            rg.addView(RadioButton(this).apply {
                text = name; id = View.generateViewId(); tag = k
                setTextColor(0xFFE8EBF2.toInt()); isChecked = (k == cur)
            })
        }
        rg.setOnCheckedChangeListener { g, id ->
            val k = g.findViewById<RadioButton>(id)?.tag as? String ?: return@setOnCheckedChangeListener
            p.edit().putString("pc_edge", k).apply()
            RemoteInputService.applyPrefsNow()
        }
        root.addView(rg)

        root.addView(label("Pointer speed"))
        val gainBar = SeekBar(this).apply {
            max = 40                                    // 0.5x .. 4.5x
            progress = ((p.getFloat("mouse_gain", 1.6f) - 0.5f) * 10f).toInt().coerceIn(0, 40)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    p.edit().putFloat("mouse_gain", 0.5f + v / 10f).apply()
                    RemoteInputService.applyPrefsNow()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(gainBar)

        root.addView(label("Mouse buttons here", 14f))
        root.addView(label("Left = tap / hold / drag · wheel = scroll · right = Back · middle = Home · side buttons = Back / Recents.\n" +
            "Keyboard: text, Backspace, Delete, arrows, Enter, Esc = Back, Ctrl+A / C / X / V.\n" +
            "Copy on the PC, cross over, press Ctrl+V on a text field here. Ctrl+C here, cross back, Ctrl+V on the PC.",
            12f, 0xFF8B93A5.toInt()))
    }

    private fun a11yOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("$packageName/") && s.contains("RemoteInputService")
    }

    private fun refresh() {
        val on = KeepAlive.wanted(this)
        status.text = when {
            !on -> "● Background link is switched off"
            LinkHub.connected && !a11yOn() -> "● Connected to the PC. Files work; switch on the accessibility service (step 1) to use the mouse"
            LinkHub.connected -> "● Connected to the PC: push the mouse to the PC screen edge"
            else -> "● " + LinkHub.statusText(this)
        }
        diag.text = "Clipboard: " + Diag.lastClip + "\nFile grab: " + Diag.lastGrab + "\nFile transfer: " + Diag.lastFile
        checks.text = Background.checklist(this).joinToString("\n") { (if (it.ok) "\u2714  " else "\u2718  ") + it.label }
    }

    /** Opens the first setting that is still missing (call again after coming back). */
    private fun fixNext() {
        val missing = Background.checklist(this).firstOrNull { !it.ok }
        if (missing == null) {
            android.widget.Toast.makeText(this, "Everything is set. Last tip: allow Autostart for " + Background.manufacturer() + ".",
                android.widget.Toast.LENGTH_LONG).show()
            Background.openAutostart(this)
            return
        }
        when (missing.fix) {
            "battery" -> Background.requestBattery(this)
            "notify" -> Background.requestNotifications(this)
            "a11y" -> Background.openAccessibility(this)
            "overlay" -> Background.requestOverlay(this)
            "files" -> Background.requestAllFiles(this)
            else -> startActivity(Intent(this, ConnectActivity::class.java))
        }
    }

    private fun toast(t: String) { android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show() }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 21 || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val cd = data.clipData
        if (cd != null) for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { uris.add(it) }
        val single = data.data
        if (single != null && single !in uris) uris.add(single)
        if (uris.isEmpty()) return
        val i = Intent(this, SendToPcActivity::class.java).setAction(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = cd ?: android.content.ClipData.newRawUri("files", uris[0])
        startActivity(i)
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }
}
