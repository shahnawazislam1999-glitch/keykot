package com.phonedeck.app

import android.app.Activity
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Invisible activity that lives for a split second. Android 10+ only lets the focused app read the
 * clipboard, so this is how the tablet's clipboard is handed to the PC when you copy / when the pointer returns.
 * If it never gets focus (screen off, another window on top) it closes itself after 2 s so it cannot hang around.
 */
class ClipReadActivity : Activity() {
    private var done = false
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            RemoteInputService.instance?.onClipRead(text)
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
