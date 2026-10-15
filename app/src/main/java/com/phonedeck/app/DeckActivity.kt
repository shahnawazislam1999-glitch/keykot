package com.phonedeck.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/**
 * Link mode: hosts the PC's PhoneDeck page in a full-screen WebView and adds, on top of it:
 *   - a word-suggestion bar (with on/off toggle) that follows what you type on the page's keyboard,
 *   - small/CAPITAL letters that follow Shift (done by assets/pd.js inside the page),
 *   - real-name downloads into the Downloads folder (Downloader),
 *   - a connection guard: network binding, live latency, automatic reconnect, "Find PC" (ConnectionGuard, Discovery).
 * Search for "ANCHOR:" to jump to a section.
 */
class DeckActivity : ComponentActivity(), ConnectionGuard.Listener, Downloader.Listener {
    private lateinit var web: WebView
    private lateinit var column: LinearLayout
    private lateinit var errorBox: View
    private lateinit var errorText: TextView
    private lateinit var errorInfo: TextView
    private lateinit var banner: TextView
    private lateinit var dlBar: View
    private lateinit var dlText: TextView
    private lateinit var dlOpen: TextView
    private lateinit var dlProg: ProgressBar

    private var url = ""
    private var host: String? = null
    private var lastBack = 0L
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val ui = Handler(Looper.getMainLooper())
    private val pdJs: String by lazy { assets.open("pd.js").bufferedReader().use { it.readText() } }

    // connection
    private var guard: ConnectionGuard? = null
    private var loadFailed = false
    private var fatalError = false
    private var attempt = 0
    private var bannerProblem = false
    private var pausedAt = 0L

    // downloads
    private lateinit var downloader: Downloader
    private var curDl = -1
    private var doneUri: Uri? = null
    private var doneName = ""
    private val hideDl = Runnable { if (::dlBar.isInitialized) dlBar.visibility = View.GONE }

    // word suggestions
    private lateinit var words: WordStore
    private var curList: List<String> = emptyList()
    private var typed = ""
    private var prevWord: String? = null
    private var fallbackWarned = false

    private fun suggestOn() = Prefs.of(this).getBoolean("sugg", true)
    private fun nextOn() = Prefs.of(this).getBoolean("sugnext", true)
    private fun shiftOn() = Prefs.of(this).getBoolean("shiftlabels", true)
    private fun tabsOn() = Prefs.of(this).getBoolean("tabs", true)
    private fun hidePadOn() = Prefs.of(this).getBoolean("hidepad", false)

    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCb = null
    }

    private val retryRun = Runnable {
        if (loadFailed && !fatalError && ::web.isInitialized) {
            attempt++
            web.loadUrl(url)
        }
    }

    // ==== ANCHOR:DECK_ONCREATE ====
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_deck)
        setupFullscreen(findViewById(R.id.root))

        url = intent.getStringExtra("url") ?: Prefs.of(this).getString("link", null) ?: ""
        if (url.isEmpty()) { finish(); return }
        host = Uri.parse(url).host

        words = WordStore(this)
        downloader = Downloader.get(this)

        column = findViewById(R.id.column)
        web = findViewById(R.id.web)
        errorBox = findViewById(R.id.errorBox)
        errorText = findViewById(R.id.errorText)
        errorInfo = findViewById(R.id.errorInfo)
        banner = findViewById(R.id.banner)
        dlBar = findViewById(R.id.dlBar)
        dlText = findViewById(R.id.dlText)
        dlOpen = findViewById(R.id.dlOpen)
        dlProg = findViewById(R.id.dlProg)

        findViewById<Button>(R.id.retry).setOnClickListener {
            attempt = 0; loadFailed = false; fatalError = false
            guard?.pokeNow()
            ui.removeCallbacks(retryRun)
            web.loadUrl(url)
        }
        findViewById<Button>(R.id.find).setOnClickListener { findPc() }
        findViewById<Button>(R.id.change).setOnClickListener { finish() }
        dlOpen.setOnClickListener { doneUri?.let { openFile(it, doneName) } }
        findViewById<View>(R.id.dlClose).setOnClickListener {
            if (curDl >= 0) { downloader.cancel(curDl); Toast.makeText(this, "Download cancelled", Toast.LENGTH_SHORT).show() }
            ui.removeCallbacks(hideDl)
            dlBar.visibility = View.GONE
        }

        web.setBackgroundColor(getColor(R.color.bg))
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isLongClickable = false
        web.setOnLongClickListener { true }
        web.isHapticFeedbackEnabled = false
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false)
            textZoom = 100                       // the page's keyboard must not grow with the phone's font size
            allowFileAccess = false
            allowContentAccess = true
        }
        web.addJavascriptInterface(Bridge(), "PDNative")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != host      // never leave the PC page

            override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) {
                loadFailed = false
                injectBridge(view)
            }

            override fun onPageFinished(view: WebView, u: String?) {
                injectBridge(view)
                pushConfig()
                if (!loadFailed) {
                    ui.removeCallbacks(retryRun)
                    attempt = 0
                    errorBox.visibility = View.GONE
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                if ((error.description?.toString() ?: "").contains("ERR_ABORTED")) return
                loadFailed = true
                fatalError = false
                showError(
                    "Can't reach $host.\n\nCheck that PhoneDeck is running on the PC and that both are on the same Wi-Fi or hotspot.",
                    true
                )
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode == 403) {
                    loadFailed = true
                    fatalError = true
                    showError("The PC refused the link (wrong or old key).\n\nTap Change link and paste the current one.", false)
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Toast.makeText(this@DeckActivity, "The page crashed - reloading", Toast.LENGTH_SHORT).show()
                recreate()
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                w: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try {
                    chooser.launch(params.createIntent()); true
                } catch (e: Exception) {
                    fileCb = null; false
                }
            }
        }

        // Files tab: PC -> phone downloads go to the Downloads folder under their real name.
        web.setDownloadListener { dlUrl, userAgent, contentDisposition, mime, _ ->
            if (dlUrl.startsWith("blob:")) {
                web.evaluateJavascript("window.__pdFetchBlob&&window.__pdFetchBlob(" + JSONObject.quote(dlUrl) + ")", null)
            } else {
                downloader.enqueue(dlUrl, userAgent, CookieManager.getInstance().getCookie(dlUrl), url, contentDisposition, mime)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBack < 2000) finish()
                else {
                    lastBack = now
                    Toast.makeText(this@DeckActivity, "Press back again to disconnect", Toast.LENGTH_SHORT).show()
                }
            }
        })

        LinkHistory.add(this, url)
        web.loadUrl(url)
    }

    // ==== ANCHOR:DECK_CONNECTION ====
    private fun startGuard() {
        guard?.stop()
        guard = ConnectionGuard(this, url, this).also { it.start() }
    }

    private fun showError(text: String, auto: Boolean) {
        errorText.text = text
        errorInfo.text = if (auto) "Retrying automatically…  (attempt " + (attempt + 1) + ")" else ""
        errorBox.visibility = View.VISIBLE
        ui.removeCallbacks(retryRun)
        if (auto) ui.postDelayed(retryRun, minOf(1500L + attempt * 700L, 5000L))
    }

    private fun showBanner(text: String, colorRes: Int) {
        banner.text = text
        banner.setBackgroundColor(getColor(colorRes))
        banner.visibility = View.VISIBLE
    }

    override fun onState(state: ConnectionGuard.State, rttMs: Int) {
        when (state) {
            ConnectionGuard.State.ONLINE -> {
                if (bannerProblem) { bannerProblem = false; banner.visibility = View.GONE }
            }
            ConnectionGuard.State.SLOW -> {
                if (bannerProblem) { bannerProblem = false; banner.visibility = View.GONE }
            }
            ConnectionGuard.State.OFFLINE -> {
                bannerProblem = true
                showBanner("⚠ Lost the PC - reconnecting…", R.color.warn)
            }
            ConnectionGuard.State.NO_NETWORK -> {
                bannerProblem = true
                showBanner("⚠ No network - join the PC's Wi-Fi / hotspot", R.color.bad)
            }
        }
    }

    override fun onRecovered(outageMs: Long) {
        bannerProblem = false
        showBanner("✔ PC is back", R.color.okbg)
        ui.postDelayed({ if (!bannerProblem) banner.visibility = View.GONE }, 1800)
        if (loadFailed && !fatalError) {
            attempt = 0
            ui.removeCallbacks(retryRun)
            web.loadUrl(url)
        } else if (outageMs > 6000) {
            web.loadUrl(url)                      // the page's own connection is probably dead: start fresh
        }
    }

    private fun findPc() {
        Discovery.showFindDialog(this, LinkUtil.portOf(url)) { ip ->
            val nu = LinkUtil.withHost(url, ip)
            url = nu
            host = ip
            Prefs.of(this).edit().putString("link", nu).apply()
            LinkHistory.add(this, nu)
            attempt = 0; loadFailed = false; fatalError = false
            errorBox.visibility = View.GONE
            startGuard()
            web.loadUrl(nu)
        }
    }

    // ==== ANCHOR:DECK_DOWNLOADS ====
    private fun fmtSize(b: Long): String = when {
        b >= (1L shl 30) -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
        b >= (1L shl 20) -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        b >= (1L shl 10) -> String.format(Locale.US, "%d KB", b shr 10)
        else -> "$b B"
    }

    override fun onDownload(s: Downloader.State) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            ui.removeCallbacks(hideDl)
            dlBar.visibility = View.VISIBLE
            when (s.phase) {
                Downloader.Phase.RUNNING -> {
                    curDl = s.id
                    dlOpen.visibility = View.GONE
                    val m = s.msg
                    val info = if (m != null) m
                    else if (s.total > 0) fmtSize(s.done) + " / " + fmtSize(s.total) + "  " + (s.done * 100 / s.total) + "%"
                    else fmtSize(s.done)
                    dlText.text = "⬇ " + s.name + "   " + info
                    if (s.total > 0) {
                        dlProg.isIndeterminate = false
                        dlProg.progress = (s.done * 1000 / s.total).toInt()
                    } else {
                        dlProg.isIndeterminate = true
                    }
                }
                Downloader.Phase.DONE -> {
                    curDl = -1
                    doneUri = s.uri
                    doneName = s.name
                    dlProg.isIndeterminate = false
                    dlProg.progress = 1000
                    dlText.text = "✔ Saved to Downloads: " + s.name
                    dlOpen.visibility = View.VISIBLE
                    Toast.makeText(this, "Saved: " + s.name, Toast.LENGTH_SHORT).show()
                    ui.postDelayed(hideDl, 10000)
                }
                Downloader.Phase.FAILED -> {
                    curDl = -1
                    dlOpen.visibility = View.GONE
                    dlProg.isIndeterminate = false
                    dlProg.progress = 0
                    dlText.text = "✖ " + s.name + ": " + (s.msg ?: "failed")
                    ui.postDelayed(hideDl, 10000)
                }
                Downloader.Phase.CANCELED -> {
                    curDl = -1
                    dlBar.visibility = View.GONE
                }
            }
        }
    }

    private fun openFile(uri: Uri, name: String) {
        val mime = Names.mimeFor(name)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "No app can open this file. Find it in the Files app > Downloads.", Toast.LENGTH_LONG).show()
        }
    }

    // ==== ANCHOR:DECK_SUGGESTIONS ====
    // The suggestion strip itself lives inside the PC page (assets/pd.js, JS_SUGBAR) right above the keyboard's top key row;
    // this side decides WHAT to suggest and pushes the list into the page.
    private fun setSuggest(on: Boolean, say: Boolean) {
        Prefs.of(this).edit().putBoolean("sugg", on).apply()
        applySuggestSetting()
        if (say) Toast.makeText(this, if (on) "Word suggestions ON" else "Word suggestions OFF", Toast.LENGTH_SHORT).show()
    }

    private fun applySuggestSetting() {
        if (!suggestOn()) { typed = ""; prevWord = null }
        refreshSuggestions()
        pushConfig()
    }

    private fun refreshSuggestions() {
        val p = prevWord
        curList = when {
            !suggestOn() -> emptyList()
            typed.isNotEmpty() -> words.suggest(typed, 4)
            p != null && nextOn() -> words.suggestNext(p, 4)
            else -> emptyList()
        }
        pushSug()
    }

    private fun pushSug() {
        if (!::web.isInitialized) return
        val arr = JSONArray()
        for (w in curList) arr.put(w)
        web.evaluateJavascript("window.__pdSug&&window.__pdSug(" + arr.toString() + ")", null)
    }

    /**
     * Tap on a suggestion: the page script types the rest of the word plus a space, so the half-typed word
     * becomes the whole word. The word is counted up so it rises in the list next time.
     */
    private fun applySuggestion(w: String) {
        if (!w.startsWith(typed)) return
        val suffix = w.substring(typed.length) + " "
        web.evaluateJavascript("window.__pdInsert&&window.__pdInsert(" + JSONObject.quote(suffix) + ")", null)
        words.learn(w, prevWord, 3)
        prevWord = w
        typed = ""
        refreshSuggestions()
    }

    // ==== ANCHOR:DECK_MENU ====
    // The same settings are shown inside the page's own Settings panel (pd.js JS_SETTINGS). This dialog is the fallback
    // when that panel cannot be found.
    private fun setPref(name: String, on: Boolean) {
        val e = Prefs.of(this).edit()
        when (name) {
            "sugg" -> setSuggest(on, false)
            "sugnext" -> { e.putBoolean("sugnext", on).apply(); refreshSuggestions(); pushConfig() }
            "shift" -> { e.putBoolean("shiftlabels", on).apply(); pushConfig() }
            "tabs" -> { e.putBoolean("tabs", on).apply(); pushConfig() }
            "hidepad" -> { e.putBoolean("hidepad", on).apply(); pushConfig() }
        }
    }

    private fun doAction(name: String) {
        when (name) {
            "reload" -> { attempt = 0; loadFailed = false; fatalError = false; web.loadUrl(url) }
            "find" -> findPc()
            "testkeys" -> testKeys()
            "disconnect" -> finish()
            "clearwords" -> AlertDialog.Builder(this)
                .setTitle("Clear learned words?")
                .setMessage("This removes the " + words.learnedCount() + " words you taught PhoneDeck. The built-in common words stay.")
                .setPositiveButton("Clear") { _, _ ->
                    words.clearLearned()
                    prevWord = null
                    refreshSuggestions()
                    pushConfig()
                    Toast.makeText(this, "Learned words cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showMenu() {
        val items = arrayOf(
            "Word suggestions: " + (if (suggestOn()) "ON" else "OFF"),
            "Next-word suggestions: " + (if (nextOn()) "ON" else "OFF"),
            "Shift changes the key letters: " + (if (shiftOn()) "ON" else "OFF"),
            "Pretty scrolling tabs: " + (if (tabsOn()) "ON" else "OFF"),
            "Touchpad tab - hide Speed/Scroll and Left/Right: " + (if (hidePadOn()) "HIDDEN" else "shown"),
            "Reload the PC page",
            "Find PC on the network…",
            "Test key detection",
            "Learned words: " + words.learnedCount() + "  (tap to clear)",
            "Change link / disconnect"
        )
        AlertDialog.Builder(this)
            .setTitle("PhoneDeck")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> setPref("sugg", !suggestOn())
                    1 -> setPref("sugnext", !nextOn())
                    2 -> setPref("shift", !shiftOn())
                    3 -> setPref("tabs", !tabsOn())
                    4 -> setPref("hidepad", !hidePadOn())
                    5 -> doAction("reload")
                    6 -> doAction("find")
                    7 -> doAction("testkeys")
                    8 -> doAction("clearwords")
                    9 -> doAction("disconnect")
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

        private fun testKeys() {
        web.evaluateJavascript(
            "window.__pdProbe?window.__pdProbe():'The helper script is not loaded yet. Wait for the page to finish loading.'"
        ) { r ->
            val txt = try { JSONTokener(r ?: "null").nextValue()?.toString() ?: "No answer" } catch (e: Exception) { r ?: "No answer" }
            AlertDialog.Builder(this)
                .setTitle("Key detection")
                .setMessage(txt)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // ==== ANCHOR:DECK_BRIDGE ====
    private fun pushConfig() {
        if (!::web.isInitialized || !::words.isInitialized) return
        val on = shiftOn()
        web.evaluateJavascript(
            "window.__pdConfig&&window.__pdConfig({shiftLabels:" + on + ",symbols:" + on +
                ",tabs:" + tabsOn() + ",hidePad:" + hidePadOn() +
                ",sugg:" + suggestOn() + ",sugnext:" + nextOn() + ",learned:" + words.learnedCount() + "})",
            null
        )
    }

    /** Lets the page copy/paste with the phone clipboard even though plain http has no navigator.clipboard, then loads pd.js. */
    private fun injectBridge(view: WebView) {
        view.evaluateJavascript(CLIP_JS, null)
        view.evaluateJavascript(pdJs, null)
    }

    inner class Bridge {
        /** Page script reports the word being typed (empty = a word just ended). */
        @JavascriptInterface
        fun word(w: String) {
            if (!suggestOn()) return
            runOnUiThread { typed = w.lowercase(); refreshSuggestions() }
        }

        /** Page script reports a finished word. */
        @JavascriptInterface
        fun learn(w: String) {
            if (!suggestOn()) return
            runOnUiThread {
                words.learn(w, prevWord)
                prevWord = w.lowercase()
            }
        }

        /** Sentence ended, mouse used, shortcut pressed ... forget the context. */
        @JavascriptInterface
        fun reset() {
            runOnUiThread { typed = ""; prevWord = null; refreshSuggestions() }
        }

        /** Tap on a suggestion in the page's strip. */
        @JavascriptInterface
        fun pick(i: Int) { runOnUiThread { curList.getOrNull(i)?.let { applySuggestion(it) } } }

        /** Long-press on a suggestion: forget that word. */
        @JavascriptInterface
        fun forgetAt(i: Int) {
            runOnUiThread {
                curList.getOrNull(i)?.let { w ->
                    words.forget(w)
                    refreshSuggestions()
                    Toast.makeText(this@DeckActivity, "Removed \"" + w + "\"", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun setPref(name: String, on: Boolean) { runOnUiThread { this@DeckActivity.setPref(name, on) } }

        @JavascriptInterface
        fun action(name: String) { runOnUiThread { doAction(name) } }

        @JavascriptInterface
        fun openMenu() { runOnUiThread { showMenu() } }

        @JavascriptInterface
        fun hint(url: String, name: String) = downloader.hint(url, name)

        @JavascriptInterface
        fun blobBegin(id: String, name: String, mime: String, total: Double) =
            downloader.blobBegin(id, name, mime, total.toLong())

        @JavascriptInterface
        fun blobChunk(id: String, b64: String): Boolean = downloader.blobChunk(id, b64)

        @JavascriptInterface
        fun blobEnd(id: String) = downloader.blobEnd(id)

        @JavascriptInterface
        fun blobFail(id: String, why: String) = downloader.blobFail(id, why)

        @JavascriptInterface
        fun report(kind: String, info: String) {
            runOnUiThread {
                if (kind == "fallback" && !fallbackWarned) {
                    fallbackWarned = true
                    Toast.makeText(
                        this@DeckActivity,
                        "Some on-screen keys were not found, key events were used instead. Menu > Test key detection shows what is missing.",
                        Toast.LENGTH_LONG
                    ).show()
                } else if (kind == "blobfail") {
                    Toast.makeText(this@DeckActivity, "The page could not hand over the file", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun readClip(): String = try {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this@DeckActivity).toString() else ""
        } catch (e: Exception) { "" }

        @JavascriptInterface
        fun writeClip(text: String) {
            try {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("PhoneDeck", text))
            } catch (e: Exception) { }
        }
    }

    // ==== ANCHOR:DECK_LIFECYCLE ====
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.onResume()
        hideSystemBars()
        if (::downloader.isInitialized) downloader.listener = this
        if (::words.isInitialized) applySuggestSetting()
        if (::web.isInitialized) {
            startGuard()
            // away for a while: the page's own connection is probably dead, start it fresh
            if (pausedAt != 0L && SystemClock.elapsedRealtime() - pausedAt > 45_000 && !loadFailed) web.loadUrl(url)
        }
        pausedAt = 0L
    }

    override fun onPause() {
        pausedAt = SystemClock.elapsedRealtime()
        if (::words.isInitialized) words.flush()
        guard?.stop()
        guard = null
        if (::web.isInitialized) web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        guard?.stop()
        guard = null
        if (::downloader.isInitialized && downloader.listener === this) downloader.listener = null
        fileCb?.onReceiveValue(null)
        fileCb = null
        if (::web.isInitialized) {
            web.removeJavascriptInterface("PDNative")
            web.destroy()
        }
        super.onDestroy()
    }
}

private const val CLIP_JS =
    "(function(){try{if(!window.PDNative||navigator.clipboard)return;" +
        "Object.defineProperty(navigator,'clipboard',{configurable:true,value:{" +
        "readText:function(){return Promise.resolve(PDNative.readClip());}," +
        "writeText:function(t){PDNative.writeClip(String(t));return Promise.resolve();}}});}catch(e){}})();"
