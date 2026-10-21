package com.phonedeck.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import org.json.JSONObject

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
        } else if (!LinkHub.send(JSONObject().put("t", "clip").put("text", text.take(100_000)))) {
            Toast.makeText(this, "PC not connected", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Copied to the PC clipboard: Ctrl+V there", Toast.LENGTH_SHORT).show()
        }
        finishQuiet()
    }

    private fun finishQuiet() {
        finish()
        overridePendingTransition(0, 0)
    }
}
