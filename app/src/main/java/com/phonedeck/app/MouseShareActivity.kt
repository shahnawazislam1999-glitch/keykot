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

/** Settings screen for "PC mouse on this tablet" (built in code, no layout file). */
class MouseShareActivity : Activity() {
    private lateinit var status: TextView
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

        root.addView(label("PC mouse on this tablet", 22f))
        root.addView(label("Push the PC mouse off the PC screen edge and the pointer appears here. " +
            "Your PC keyboard types here too while the pointer is on the tablet. " +
            "SCROLL LOCK on the PC always brings control back.", 13f, 0xFF8B93A5.toInt()))

        status = label("", 14f, 0xFF4DA3FF.toInt())
        root.addView(status)

        val sw = Switch(this).apply {
            text = "Use the PC mouse on this tablet"
            setTextColor(0xFFE8EBF2.toInt())
            isChecked = p.getBoolean("share_on", false)
            setOnCheckedChangeListener { _, on ->
                p.edit().putBoolean("share_on", on).apply()
                if (on) {
                    if (android.os.Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
                    KeepAlive.start(this@MouseShareActivity)
                } else KeepAlive.stop(this@MouseShareActivity)
                RemoteInputService.applyPrefsNow()
                refresh()
            }
        }
        root.addView(sw)

        root.addView(label("1. Allow the accessibility service (needed to click and type for you)"))
        root.addView(button("Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(label("Find \"PhoneDeck PC mouse\" and switch it on. On Android 13+ the switch may be greyed out: " +
            "tap the button below, then ⋮ (top right) > \"Allow restricted settings\", and try again.", 12f, 0xFF8B93A5.toInt()))
        root.addView(button("Open PhoneDeck app info") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        root.addView(label("2. Keep it running forever (Xiaomi / HyperOS)"))
        root.addView(button("Battery: allow always active") {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        })
        root.addView(button("Xiaomi: Autostart settings") {
            val tries = listOf(
                android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                android.content.ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"))
            var ok = false
            for (c in tries) {
                try { startActivity(Intent().setComponent(c)); ok = true; break } catch (e: Exception) { }
            }
            if (!ok) startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        root.addView(label("Also: in Recents long-press PhoneDeck and tap the lock icon; in App info set Battery saver to \"No restrictions\". " +
            "A permanent notification keeps the link alive: do not hide it.", 12f, 0xFF8B93A5.toInt()))

        root.addView(label("3. Connect to the PC"))
        root.addView(label("Uses the same link as the keyboard screen. Connect there once (Paste & Connect) so the link is saved; " +
            "this feature then connects by itself in the background.", 12f, 0xFF8B93A5.toInt()))

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
        val on = Prefs.of(this).getBoolean("share_on", false)
        val svc = RemoteInputService.instance
        status.text = when {
            !a11yOn() && svc == null -> "● Accessibility service is OFF (step 1)"
            !on -> "● Service is on, feature switched off"
            svc?.isLinked == true -> "● Connected to the PC: push the mouse to the PC screen edge"
            else -> "● Looking for the PC… (is PhoneDeck running on the PC? is the link saved?)"
        }
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }
}
