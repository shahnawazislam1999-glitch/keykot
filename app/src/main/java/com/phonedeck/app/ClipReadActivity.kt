package com.phonedeck.app

import android.app.Activity
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Invisible activity that lives for a split second. Android 10+ only lets the focused app read the
 * clipboard, so this is how the tablet's clipboard is handed to the PC when you copy / when the pointer returns.
 * If it never gets focus (screen off, another window on top) it closes itself after 2 s so it cannot hang around.
 */
class ClipReadActivity : Activity() {
    private var done = false
    private val ui = Handler(Looper.getMainLooper())
    private var manual = false          // started by the user ("Send clipboard to PC" in the notification): send it ourselves

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manual = intent?.getBooleanExtra("manual", false) == true
        ui.postDelayed({ if (!done) { done = true; finishQuiet() } }, 2000)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !done) {
            done = true
            var text: String? = null
            try {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val c = cm.primaryClip
                if (c != null && c.itemCount > 0 &&
                    (c.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                        c.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)))
                    text = c.getItemAt(0).coerceToText(this)?.toString()
            } catch (e: Exception) { }
            if (manual) {
                val app = applicationContext
                if (text.isNullOrEmpty()) {
                    Diag.clip("send clipboard: nothing (or no text) on the clipboard")
                    Toast.makeText(app, "Nothing to send: copy some text first", Toast.LENGTH_SHORT).show()
                } else {
                    PcSend.text(app, text) { ok, why ->
                        Toast.makeText(app, if (ok) "Copied to the PC clipboard: Ctrl+V there" else "Could not reach the PC: $why", Toast.LENGTH_LONG).show()
                    }
                }
            } else RemoteInputService.instance?.onClipRead(text)
            finishQuiet()
        }
    }

    private fun finishQuiet() {
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
