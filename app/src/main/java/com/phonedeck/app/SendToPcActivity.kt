package com.phonedeck.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * "Share > PhoneDeck: Send to PC". The guaranteed way to get a file (or text) from this tablet to the PC:
 * it does not need the pointer, the accessibility service or any guessing which file is meant.
 *  - one or more files (from any file manager, gallery, browser ...) go to the PC folder Downloads\PhoneDeck;
 *  - plain text goes to the PC clipboard (Ctrl+V there).
 * The screen stays open until everything is sent, because Android only lets us read the shared files while it is open.
 */
class SendToPcActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var headView: TextView
    private lateinit var infoView: TextView
    private lateinit var barView: ProgressBar
    private lateinit var closeBtn: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(16))
        }
        headView = TextView(this).apply { textSize = 18f; text = "Send to PC" }
        infoView = TextView(this).apply { textSize = 14f; setPadding(0, dp(8), 0, dp(8)) }
        barView = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        closeBtn = Button(this).apply { text = "Close"; isAllCaps = false; setOnClickListener { finish() } }
        root.addView(headView)
        root.addView(infoView)
        root.addView(barView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(closeBtn)
        setContentView(root)
        setFinishOnTouchOutside(false)

        val uris = collect(intent)
        val text = if (uris.isEmpty()) intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() else null
        when {
            uris.isNotEmpty() -> sendFiles(uris)
            !text.isNullOrBlank() -> sendText(text)
            else -> {
                headView.text = "Nothing to send"
                infoView.text = "No file or text came with this share."
                barView.isIndeterminate = false
            }
        }
    }

    private fun sendFiles(uris: List<Uri>) {
        headView.text = "Sending " + (if (uris.size == 1) "1 file" else uris.size.toString() + " files") + " to the PC…"
        infoView.text = "Saved on the PC in Downloads\\PhoneDeck"
        PcSend.upload(this, uris, object : PcSend.Listener {
            override fun onProgress(filesDone: Int, filesTotal: Int, sent: Long, bytes: Long) {
                if (isFinishing) return
                if (bytes > 0) {
                    barView.isIndeterminate = false
                    barView.progress = ((sent * 100) / bytes).toInt().coerceIn(0, 100)
                }
                infoView.text = "File " + minOf(filesDone + 1, filesTotal) + " of " + filesTotal + "  (" + mb(sent) + " of " + mb(bytes) + ")"
            }

            override fun onFinished(ok: Int, failed: Int, firstError: String) {
                if (isFinishing) return
                barView.isIndeterminate = false
                barView.progress = 100
                if (failed == 0) {
                    headView.text = "Sent to the PC"
                    infoView.text = ok.toString() + " file(s) are in Downloads\\PhoneDeck on the PC."
                    ui.postDelayed({ if (!isFinishing) finish() }, 1400)
                } else {
                    headView.text = if (ok > 0) "Sent $ok, failed $failed" else "Could not send"
                    infoView.text = firstError + "\n\nIs the PC server running (start-lan.bat) and are both on the same network?"
                }
            }
        })
    }

    private fun sendText(text: String) {
        headView.text = "Copying to the PC clipboard…"
        infoView.text = ""
        PcSend.text(this, text) { ok, how ->
            if (isFinishing) return@text
            barView.isIndeterminate = false
            barView.progress = 100
            if (ok) {
                headView.text = "Copied to the PC clipboard"
                infoView.text = "Press Ctrl+V on the PC."
                ui.postDelayed({ if (!isFinishing) finish() }, 1000)
            } else {
                headView.text = "Could not copy to the PC"
                infoView.text = how
            }
        }
    }

    private fun mb(b: Long): String = if (b < 1_000_000L) (b / 1000L).toString() + " KB" else String.format("%.1f MB", b / 1_048_576.0)

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        /** All content links that came with a SEND / SEND_MULTIPLE intent (also the ones only present in ClipData). */
        fun collect(i: Intent?): List<Uri> {
            val out = LinkedHashSet<Uri>()
            if (i == null) return emptyList()
            try {
                if (i.action == Intent.ACTION_SEND_MULTIPLE) {
                    val l: List<Uri>? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    l?.forEach { out.add(it) }
                } else {
                    val u: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    if (u != null) out.add(u)
                }
            } catch (e: Exception) { }
            try {
                val cd = i.clipData
                if (cd != null) for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { out.add(it) }
            } catch (e: Exception) { }
            return out.toList()
        }
    }
}
