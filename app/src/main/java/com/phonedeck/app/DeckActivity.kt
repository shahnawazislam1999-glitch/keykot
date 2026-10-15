package com.phonedeck.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts

/** Hosts the PC's PhoneDeck page in a full-screen WebView. */
class DeckActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var errorBox: View
    private lateinit var errorText: TextView

    private var url = ""
    private var host: String? = null
    private var lastBack = 0L
    private var fileCb: ValueCallback<Array<Uri>>? = null

    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCb = null
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_deck)
        setupFullscreen(findViewById(R.id.root))

        url = intent.getStringExtra("url") ?: Prefs.of(this).getString("link", null) ?: ""
        if (url.isEmpty()) { finish(); return }
        host = Uri.parse(url).host

        web = findViewById(R.id.web)
        errorBox = findViewById(R.id.errorBox)
        errorText = findViewById(R.id.errorText)
        findViewById<Button>(R.id.retry).setOnClickListener { errorBox.visibility = View.GONE; web.loadUrl(url) }
        findViewById<Button>(R.id.change).setOnClickListener { finish() }

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
            allowFileAccess = false
            allowContentAccess = true
        }
        web.addJavascriptInterface(Bridge(), "PDNative")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != host      // never leave the PC page

            override fun onPageStarted(view: WebView, u: String?, favicon: android.graphics.Bitmap?) {
                errorBox.visibility = View.GONE
                injectBridge(view)
            }

            override fun onPageFinished(view: WebView, u: String?) = injectBridge(view)

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    showError("Can't reach $host.\n\nCheck that PhoneDeck is running on the PC and that both are on the same Wi-Fi or hotspot.")
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode == 403) {
                    showError("The PC refused the link (wrong or old key).\n\nTap Change link and paste the current one.")
                }
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

        // Files tab: PC -> phone downloads go to the normal Downloads folder.
        web.setDownloadListener { dlUrl, userAgent, contentDisposition, mime, _ ->
            try {
                val name = URLUtil.guessFileName(dlUrl, contentDisposition, mime)
                val req = DownloadManager.Request(Uri.parse(dlUrl))
                    .setMimeType(mime)
                    .addRequestHeader("User-Agent", userAgent)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                getSystemService(DownloadManager::class.java).enqueue(req)
                Toast.makeText(this, "Downloading $name", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
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

        web.loadUrl(url)
    }

    private fun showError(text: String) = runOnUiThread {
        errorText.text = text
        errorBox.visibility = View.VISIBLE
    }

    /** Lets the page copy/paste with the phone clipboard even though plain http has no navigator.clipboard. */
    private fun injectBridge(view: WebView) {
        view.evaluateJavascript(
            "(function(){try{if(!window.PDNative||navigator.clipboard)return;" +
                "Object.defineProperty(navigator,'clipboard',{configurable:true,value:{" +
                "readText:function(){return Promise.resolve(PDNative.readClip());}," +
                "writeText:function(t){PDNative.writeClip(String(t));return Promise.resolve();}}});}catch(e){}})();",
            null
        )
    }

    inner class Bridge {
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.onResume()
        hideSystemBars()
    }

    override fun onPause() {
        if (::web.isInitialized) web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        fileCb?.onReceiveValue(null)
        fileCb = null
        if (::web.isInitialized) {
            web.removeJavascriptInterface("PDNative")
            web.destroy()
        }
        super.onDestroy()
    }
}
