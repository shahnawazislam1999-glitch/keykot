package com.phonedeck.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject

/** Hosts the PC's PhoneDeck page in a full-screen WebView. */
class DeckActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var errorBox: View
    private lateinit var errorText: TextView

    private var url = ""
    private var host: String? = null
    private var lastBack = 0L
    private var fileCb: ValueCallback<Array<Uri>>? = null

    // word suggestions
    private lateinit var words: WordStore
    private lateinit var sugBar: LinearLayout
    private val sugViews = ArrayList<TextView>()
    private var typed = ""
    private fun suggestOn() = Prefs.of(this).getBoolean("sugg", true)

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

        words = WordStore(this)
        buildSuggestionBar()

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

    // ------------------------------------------------------------------ word suggestions
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildSuggestionBar() {
        sugBar = findViewById(R.id.sugBar)
        repeat(4) {
            val tv = TextView(this)
            tv.gravity = Gravity.CENTER
            tv.maxLines = 1
            tv.ellipsize = android.text.TextUtils.TruncateAt.END
            tv.setTextColor(getColor(R.color.txt))
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
            tv.layoutParams = lp
            tv.setOnClickListener { (tv.tag as? String)?.let { applySuggestion(it) } }
            tv.setOnLongClickListener {
                (tv.tag as? String)?.let {
                    words.forget(it)
                    refreshSuggestions()
                    Toast.makeText(this, "Removed \"$it\"", Toast.LENGTH_SHORT).show()
                }
                true
            }
            sugViews.add(tv)
            sugBar.addView(tv)
        }
        applySuggestSetting()
    }

    private fun applySuggestSetting() {
        val on = suggestOn()
        sugBar.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) typed = ""
        refreshSuggestions()
    }

    private fun refreshSuggestions() {
        val list = if (typed.isEmpty() || !suggestOn()) emptyList() else words.suggest(typed, sugViews.size)
        for ((i, tv) in sugViews.withIndex()) {
            val w = list.getOrNull(i)
            tv.tag = w
            tv.text = w ?: ""
            if (w != null) tv.setBackgroundResource(R.drawable.bg_key) else tv.background = null
        }
    }

    /** Tap on a suggestion: the page script types the rest of the word plus a space. */
    private fun applySuggestion(w: String) {
        if (!w.startsWith(typed)) return
        val suffix = w.substring(typed.length) + " "
        web.evaluateJavascript("window.__pdInsert&&window.__pdInsert(" + JSONObject.quote(suffix) + ")", null)
        words.learn(w)
        typed = ""
        refreshSuggestions()
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
        view.evaluateJavascript(SUGGEST_JS, null)
    }

    inner class Bridge {
        /** Page script reports the word being typed. */
        @JavascriptInterface
        fun word(w: String) {
            if (!suggestOn()) return
            runOnUiThread { typed = w.lowercase(); refreshSuggestions() }
        }

        /** Page script reports a finished word. */
        @JavascriptInterface
        fun learn(w: String) {
            if (suggestOn()) words.learn(w)
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.onResume()
        hideSystemBars()
        if (::sugBar.isInitialized) applySuggestSetting()
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

/**
 * Injected into the PC page. It cannot see the page's source, so it watches what is typed in two ways:
 * physical/soft keyboard in text boxes, and taps on on-screen keys (buttons whose label is one character,
 * Space, Backspace, Enter). It reports the current word to the app, and types a chosen suggestion back by
 * inserting into the focused text box, or by tapping the matching on-screen keys.
 */
private const val SUGGEST_JS = """
(function(){
 if(window.__pdSug||!window.PDNative)return; window.__pdSug=true;
 var buf='', modLatch=false;
 function rep(){try{PDNative.word(buf);}catch(e){}}
 function reset(){buf='';rep();}
 function fin(){if(buf){try{PDNative.learn(buf);}catch(e){}} reset();}
 function key(k,mod){
  if(mod||modLatch){modLatch=false;reset();return;}
  if(k.length===1){
   if(/[a-z0-9]/i.test(k)){buf+=k.toLowerCase();rep();}
   else if(k==="'"&&buf){buf+=k;rep();}
   else fin();
  } else if(k==='Backspace'){buf=buf.slice(0,-1);rep();}
  else if(k==='Enter'||k==='Tab'){fin();}
  else reset();
 }
 function editable(t){return t&&(t.tagName==='INPUT'||t.tagName==='TEXTAREA'||t.isContentEditable);}
 document.addEventListener('keydown',function(e){
  if(!e.isTrusted||editable(e.target)||e.key==='Unidentified'||e.key==='Process')return;
  if(e.key==='Shift'||e.key==='CapsLock'||e.key==='Control'||e.key==='Alt'||e.key==='Meta')return;
  key(e.key,e.ctrlKey||e.altKey||e.metaKey);
 },true);
 document.addEventListener('input',function(e){
  if(!e.isTrusted||!editable(e.target))return;
  var t=e.inputType||'';
  if(t==='insertText'||t==='insertCompositionText'||t==='insertFromPaste'){
   if(t==='insertFromPaste'){reset();return;}
   var d=e.data||''; for(var i=0;i<d.length;i++)key(d.charAt(i),false);
  } else if(t==='deleteContentBackward'){key('Backspace',false);}
  else if(t==='insertLineBreak'||t==='insertParagraph'){key('Enter',false);}
  else reset();
 },true);
 function label(el){
  for(var i=0;i<4&&el&&el!==document.body;i++,el=el.parentElement){
   var d=el.getAttribute&&(el.getAttribute('data-key')||el.getAttribute('data-k'));
   var t=(d||el.textContent||'').trim();
   if(t.length===1)return t;
   if(/^(backspace|bksp|\u232B|\u2190)$/i.test(t))return 'Backspace';
   if(/^(enter|return|\u21B5|\u23CE)$/i.test(t))return 'Enter';
   if(/^(space|\u2423)$/i.test(t))return ' ';
   if(/^tab$/i.test(t))return 'Tab';
   if(/^(shift|caps|caps lock|capslock|\u21E7)$/i.test(t))return 'Shift';
   if(/^(ctrl|control|alt|win|windows|meta|cmd|super)$/i.test(t))return 'Mod';
  }
  return null;
 }
 document.addEventListener('pointerdown',function(e){
  if(!e.isTrusted||editable(e.target))return;
  var k=label(e.target); if(!k)return;
  if(k==='Shift')return;
  if(k==='Mod'){reset();modLatch=true;return;}
  key(k,false);
 },true);
 function findKey(ch){
  var els=document.querySelectorAll('button,[role=button],[data-key],[data-k],.key,[class*=key],[class*=Key]');
  var best=null;
  for(var i=0;i<els.length;i++){
   var el=els[i]; if(!el.offsetWidth)continue;
   var t=((el.getAttribute('data-key')||el.getAttribute('data-k')||el.textContent)||'').trim();
   if(ch===' '){
    if(/^(space|\u2423)$/i.test(t)||(t===''&&/space/i.test((el.className||'')+' '+(el.id||''))))return el;
   } else if(t.length===1&&t.toLowerCase()===ch)best=el;
  }
  return best;
 }
 function press(el){
  var r=el.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;
  var base={bubbles:true,cancelable:true,clientX:x,clientY:y,view:window};
  var pi={bubbles:true,cancelable:true,clientX:x,clientY:y,pointerId:1,pointerType:'touch',isPrimary:true};
  var prevented=false;
  try{if(!el.dispatchEvent(new PointerEvent('pointerdown',pi)))prevented=true;}catch(e){}
  try{
   var t=new Touch({identifier:1,target:el,clientX:x,clientY:y});
   var ti={bubbles:true,cancelable:true,touches:[t],targetTouches:[t],changedTouches:[t]};
   if(!el.dispatchEvent(new TouchEvent('touchstart',ti)))prevented=true;
  }catch(e){}
  try{el.dispatchEvent(new PointerEvent('pointerup',pi));}catch(e){}
  try{
   var t2=new Touch({identifier:1,target:el,clientX:x,clientY:y});
   var te={bubbles:true,cancelable:true,touches:[],targetTouches:[],changedTouches:[t2]};
   if(!el.dispatchEvent(new TouchEvent('touchend',te)))prevented=true;
  }catch(e){}
  if(!prevented){
   el.dispatchEvent(new MouseEvent('mousedown',base));
   el.dispatchEvent(new MouseEvent('mouseup',base));
   el.dispatchEvent(new MouseEvent('click',base));
  }
 }
 window.__pdInsert=function(text){
  var a=document.activeElement;
  if(editable(a)){
   try{if(document.execCommand('insertText',false,text)){reset();return;}}catch(e){}
  }
  var i=0;
  (function next(){
   if(i>=text.length){reset();return;}
   var el=findKey(text.charAt(i++)); if(el)press(el);
   setTimeout(next,25);
  })();
 };
})();
"""
