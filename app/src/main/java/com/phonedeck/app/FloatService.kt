package com.phonedeck.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Floating mode: the Type tab's keyboard + mouse pad, drawn ABOVE every other app.
 *
 *  - a foreground service (so Android keeps it alive when PhoneDeck is minimised or swiped away),
 *  - an overlay window (TYPE_APPLICATION_OVERLAY, needs "Display over other apps"),
 *  - inside it a small native bar (drag handle, Adjust, Split, Reset, opacity slider, minimise, close),
 *    a resize grip, and a WebView that loads the PC page with ?float=1 (page shows only the Type keyboard + mouse pad).
 *  - the window is NOT_FOCUSABLE, so the app underneath keeps its focus; touches outside it go to the app underneath.
 *  - own ConnectionGuard (binds the network that holds the PC) and a partial wake lock, so the link stays up with the screen dark.
 */
class FloatService : Service(), ConnectionGuard.Listener {

    companion object {
        @Volatile var running = false
        const val ACTION_STOP = "com.phonedeck.app.FLOAT_STOP"
        private const val CHANNEL = "pd_float"
        private const val NID = 41

        fun start(ctx: Context, url: String) {
            ctx.startForegroundService(Intent(ctx, FloatService::class.java).putExtra("url", url))
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, FloatService::class.java)) }
    }

    private lateinit var wm: WindowManager
    private var root: FrameLayout? = null
    private var web: WebView? = null
    private var column: LinearLayout? = null
    private var grip: View? = null
    private lateinit var lp: WindowManager.LayoutParams
    private var guard: ConnectionGuard? = null
    private var wake: PowerManager.WakeLock? = null
    private val ui = Handler(Looper.getMainLooper())
    private var base = ""
    private var floatUrl = ""
    private var collapsed = false
    private var savedH = 0
    private val retry = Runnable { web?.loadUrl(floatUrl) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun scrW() = resources.displayMetrics.widthPixels
    private fun scrH() = resources.displayMetrics.heightPixels

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires startForeground() right after startForegroundService(), whatever happens next
        goForeground(intent?.getStringExtra("url") ?: Prefs.of(this).getString("link", null) ?: "")
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val u = intent?.getStringExtra("url") ?: Prefs.of(this).getString("link", null) ?: ""
        if (u.isEmpty() || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if (root == null) {
            base = u
            floatUrl = Uri.parse(u).buildUpon().appendQueryParameter("float", "1").build().toString()
            running = true
            acquireWake()
            guard = ConnectionGuard(this, base, this).also { it.start() }
            buildOverlay()
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------- foreground notification
    private fun goForeground(url: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Floating keyboard", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, DeckActivity::class.java).putExtra("url", url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FloatService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("PhoneDeck floating keyboard is on")
            .setContentText("Tap to open PhoneDeck · Stop to remove the floating keyboard")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_launcher_foreground), "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NID, n)
    }

    private fun acquireWake() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonedeck:float").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------- overlay window
    private fun pill(text: String, onTap: () -> Unit): TextView = TextView(ContextThemeWrapper(this, R.style.Theme_PhoneDeck)).apply {
        this.text = text
        setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER
        setPadding(dp(10), 0, dp(10), 0)
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xFF343A4A.toInt()) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)).apply { marginStart = dp(5) }
        setOnClickListener { onTap() }
    }

    private fun js(code: String) { web?.evaluateJavascript("(function(){try{$code}catch(e){}})()", null) }

    private fun applyLp() { val v = root ?: return; try { wm.updateViewLayout(v, lp) } catch (e: Exception) { } }

    private fun clampPos() {
        lp.x = lp.x.coerceIn(-(lp.width - dp(90)), scrW() - dp(90))
        lp.y = lp.y.coerceIn(0, maxOf(0, scrH() - dp(40)))
    }

    private fun savePos() {
        Prefs.of(this).edit().putInt("fl_x", lp.x).putInt("fl_y", lp.y).putInt("fl_w", lp.width).putInt("fl_h", if (collapsed) savedH else lp.height).apply()
    }

    private fun buildOverlay() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val themed = ContextThemeWrapper(this, R.style.Theme_PhoneDeck)
        val p = Prefs.of(this)
        val w = p.getInt("fl_w", (scrW() * 0.72f).toInt()).coerceIn(minOf(dp(340), scrW()), scrW())
        val h = p.getInt("fl_h", (scrH() * 0.5f).toInt()).coerceIn(dp(180), scrH())
        lp = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = p.getInt("fl_x", (scrW() - w) / 2)
        lp.y = p.getInt("fl_y", scrH() - h - dp(24))
        clampPos()

        val r = FrameLayout(themed)
        r.background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xFF0F1116.toInt()); setStroke(dp(1), 0xFF3A4152.toInt()) }
        r.clipToOutline = true
        r.alpha = p.getInt("fl_alpha", 100) / 100f

        val col = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1B1E27.toInt())
            setPadding(dp(8), 0, dp(6), 0)
        }
        val drag = TextView(themed).apply {
            text = "⠿  PhoneDeck"; setTextColor(0xFFB8C0D0.toInt()); textSize = 13f; gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
        }
        var ox = 0f; var oy = 0f; var sx = 0; var sy = 0
        drag.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { ox = e.rawX; oy = e.rawY; sx = lp.x; sy = lp.y; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - ox).toInt(); lp.y = sy + (e.rawY - oy).toInt(); clampPos()
                    applyLp()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { savePos(); true }
                else -> false
            }
        }
        bar.addView(drag, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        bar.addView(pill("Adjust") { js("document.getElementById('tAdj').click()") })
        bar.addView(pill("Split") { js("document.getElementById('tSplit').click()") })
        bar.addView(pill("Reset") { js("document.getElementById('tReset').click()") })

        // opacity ("volume") slider: 20% .. 100%
        val seek = SeekBar(themed).apply {
            max = 80
            progress = p.getInt("fl_alpha", 100) - 20
            progressTintList = ColorStateList.valueOf(0xFF4DA3FF.toInt())
            thumbTintList = ColorStateList.valueOf(0xFF4DA3FF.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, user: Boolean) { r.alpha = (v + 20) / 100f }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { Prefs.of(this@FloatService).edit().putInt("fl_alpha", s.progress + 20).apply() }
            })
        }
        bar.addView(TextView(themed).apply { text = "◐"; setTextColor(0xFFB8C0D0.toInt()); textSize = 15f; setPadding(dp(8), 0, 0, 0) })
        bar.addView(seek, LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(pill("▁") { toggleCollapse() })
        bar.addView(pill("✕") { stopSelf() })
        col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))

        val wv = WebView(themed)
        wv.setBackgroundColor(0xFF0F1116.toInt())
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isLongClickable = false
        wv.setOnLongClickListener { true }
        wv.isHapticFeedbackEnabled = false
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportZoom(false); builtInZoomControls = false; displayZoomControls = false
            textZoom = 100
            allowFileAccess = false
        }
        val host = Uri.parse(base).host
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.host != host
            override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) { ui.removeCallbacks(retry) }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                if ((error.description?.toString() ?: "").contains("ERR_ABORTED")) return
                ui.removeCallbacks(retry); ui.postDelayed(retry, 2500)      // PC not reachable yet: keep trying
            }
        }
        col.addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        r.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // resize grip (bottom-right corner)
        val g = TextView(themed).apply {
            text = "◢"; setTextColor(0xCC8FA3C8.toInt()); textSize = 20f; gravity = Gravity.CENTER
        }
        var rx = 0f; var ry = 0f; var sw = 0; var sh = 0
        g.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { rx = e.rawX; ry = e.rawY; sw = lp.width; sh = lp.height; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.width = (sw + (e.rawX - rx)).toInt().coerceIn(minOf(dp(340), scrW()), scrW())
                    lp.height = (sh + (e.rawY - ry)).toInt().coerceIn(dp(180), scrH())
                    applyLp()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { savePos(); true }
                else -> false
            }
        }
        r.addView(g, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM or Gravity.END))

        root = r; web = wv; column = col; grip = g
        try { wm.addView(r, lp) } catch (e: Exception) { stopSelf(); return }
        wv.loadUrl(floatUrl)
    }

    private fun toggleCollapse() {
        val wv = web ?: return
        collapsed = !collapsed
        if (collapsed) { savedH = lp.height; lp.height = dp(42); wv.visibility = View.GONE; grip?.visibility = View.GONE }
        else { lp.height = savedH.coerceAtLeast(dp(180)); wv.visibility = View.VISIBLE; grip?.visibility = View.VISIBLE }
        applyLp()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (root == null) return
        lp.width = lp.width.coerceAtMost(scrW()); if (!collapsed) lp.height = lp.height.coerceAtMost(scrH())
        clampPos()
        applyLp()
    }

    // ---------------------------------------------------------------- connection guard
    override fun onState(state: ConnectionGuard.State, rttMs: Int) {}
    override fun onRecovered(outageMs: Long) { if (outageMs > 6000) web?.loadUrl(floatUrl) }

    override fun onDestroy() {
        running = false
        ui.removeCallbacks(retry)
        try { root?.let { wm.removeView(it) } } catch (e: Exception) { }
        root = null
        try { web?.destroy() } catch (e: Exception) { }
        web = null
        guard?.stop(); guard = null
        try { wake?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        super.onDestroy()
    }
}
