package com.phonedeck.app

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle

/**
 * Invisible activity that lives for a split second. Android 10+ only lets the focused app read the
 * clipboard, so this is how the tablet's clipboard is handed to the PC when the pointer returns.
 */
class ClipReadActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !done) {
            done = true
            var text: String? = null
            try {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val c = cm.primaryClip
                if (c != null && c.itemCount > 0) text = c.getItemAt(0).coerceToText(this)?.toString()
            } catch (e: Exception) { }
            RemoteInputService.instance?.onClipRead(text)
            finish()
            overridePendingTransition(0, 0)
        }
    }
}
