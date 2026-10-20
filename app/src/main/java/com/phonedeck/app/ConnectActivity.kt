package com.phonedeck.app

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity

class ConnectActivity : ComponentActivity() {
    private lateinit var input: EditText
    private lateinit var msg: TextView
    private lateinit var auto: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)
        setupFullscreen(findViewById(R.id.root))

        input = findViewById(R.id.link)
        msg = findViewById(R.id.msg)
        auto = findViewById(R.id.auto)

        val p = Prefs.of(this)
        input.setText(p.getString("link", ""))
        auto.isChecked = p.getBoolean("auto", true)
        auto.setOnCheckedChangeListener { _, checked -> p.edit().putBoolean("auto", checked).apply() }
        val sugg = findViewById<CheckBox>(R.id.sugg)
        sugg.isChecked = p.getBoolean("sugg", true)
        sugg.setOnCheckedChangeListener { _, checked -> p.edit().putBoolean("sugg", checked).apply() }

        findViewById<Button>(R.id.paste).setOnClickListener { pasteAndConnect() }
        findViewById<Button>(R.id.go).setOnClickListener { connect(input.text.toString()) }
        findViewById<Button>(R.id.bt).setOnClickListener { startBt() }
        findViewById<Button>(R.id.share).setOnClickListener { startActivity(Intent(this, MouseShareActivity::class.java)) }
        findViewById<Button>(R.id.recent).setOnClickListener { showRecent() }
        findViewById<Button>(R.id.find).setOnClickListener { findPc() }
        findViewById<Button>(R.id.test).setOnClickListener { testLink() }
        findViewById<Button>(R.id.forget).setOnClickListener {
            p.edit().remove("link").apply()
            input.setText("")
            say("Saved link removed.", null)
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { connect(input.text.toString()); true } else false
        }

        val shared = handleShare(intent)
        if (!shared && savedInstanceState == null && auto.isChecked) {
            if (p.getString("mode", "wifi") == "bt") startBt()
            else if (LinkUtil.parse(input.text.toString()) != null) connect(input.text.toString())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun handleShare(i: Intent?): Boolean {
        if (i?.action != Intent.ACTION_SEND) return false
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return false
        input.setText(text.trim())
        connect(text)
        return true
    }

    private fun pasteAndConnect() {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
        if (text.isNullOrBlank()) {
            say("Clipboard is empty. Copy the link first.", false)
            return
        }
        input.setText(text.trim())
        connect(text)
    }

    private fun connect(raw: String) {
        val url = LinkUtil.parse(raw)
        if (url == null) {
            say(getString(R.string.bad_link), false)
            return
        }
        val pr = Prefs.of(this)
        pr.edit().putString("link", url).putString("mode", "wifi").apply()
        LinkHistory.add(this, url)
        input.setText(url)
        // connect once, never again: the background link starts now and from then on after every reboot / network change
        if (!pr.contains("share_on")) pr.edit().putBoolean("share_on", true).apply()
        if (KeepAlive.wanted(this)) {
            KeepAlive.start(this)
            LinkHub.ensure(this)
            LinkHub.kickNow()
            Background.askEssentials(this)
        }
        say("Connecting…", true)
        startActivity(Intent(this, DeckActivity::class.java).putExtra("url", url))
    }

    // ==== ANCHOR:CONNECT_TOOLS ====
    private fun showRecent() {
        val list = LinkHistory.list(this)
        if (list.isEmpty()) { say("No recent links yet.", null); return }
        AlertDialog.Builder(this)
            .setTitle("Recent links")
            .setItems(list.map { LinkUtil.pretty(it) }.toTypedArray()) { _, i ->
                input.setText(list[i])
                connect(list[i])
            }
            .setNeutralButton("Clear") { _, _ -> LinkHistory.clear(this) }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Looks for the PC again (its IP may have changed) and puts the new link in the box. */
    private fun findPc() {
        val base = LinkUtil.parse(input.text.toString())
        val port = if (base != null) LinkUtil.portOf(base) else 8080
        Discovery.showFindDialog(this, port, if (base != null) Uri.parse(base).host else null, if (base != null) (Uri.parse(base).getQueryParameter("key") ?: "") else "") { ip ->
            val nu = if (base != null) LinkUtil.withHost(base, ip) else "http://" + ip + ":" + port + "/"
            input.setText(nu)
            say("Found the PC at " + ip + ". Tap Connect.", true)
        }
    }

    private fun testLink() {
        val u = LinkUtil.parse(input.text.toString())
        if (u == null) { say(getString(R.string.bad_link), false); return }
        say("Testing…", null)
        Thread {
            val r = Probe.check(u)
            runOnUiThread { say(r.text, r.ok) }
        }.start()
    }

    private fun startBt() {
        Prefs.of(this).edit().putString("mode", "bt").apply()
        startActivity(Intent(this, BtActivity::class.java))
    }

    private fun say(text: String, ok: Boolean?) {
        msg.text = text
        msg.setTextColor(
            getColor(
                when (ok) {
                    true -> R.color.ok
                    false -> R.color.bad
                    null -> R.color.dim
                }
            )
        )
    }
}
