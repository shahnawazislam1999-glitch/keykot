package com.phonedeck.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * "Send to PC" item in the text-selection toolbar of every app (Chrome, chats, notes ...).
 * Android hands the selected text straight to this screen, so the tablet clipboard never has to be read
 * (Android 10+ blocks that for background apps). The text goes to the PC clipboard: Ctrl+V there.
 */
class ProcessTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        if (text.isEmpty()) {
            finishQuiet()
            return
        }
        val svc = RemoteInputService.instance
        if (svc != null) {
            svc.sendSelection(text)
        } else {
            val app = applicationContext
            PcSend.text(app, text) { ok, why ->
                Toast.makeText(app, if (ok) "Copied to the PC clipboard: Ctrl+V there" else "PC not reachable: $why", Toast.LENGTH_SHORT).show()
            }
        }
        finishQuiet()
    }

    private fun finishQuiet() {
        finish()
        overridePendingTransition(0, 0)
    }
}
