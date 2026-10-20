package com.phonedeck.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * "Never stop" machinery for the PC-mouse feature:
 *  - [KeepAliveService]: foreground service (permanent notification), CPU wake lock + Wi-Fi lock,
 *    restarts itself when killed / swiped away, and drives the connection heartbeat every 2 s.
 *  - [WatchdogReceiver]: an alarm every minute that restarts the service if the system killed it.
 *  - [BootReceiver]: starts everything after a reboot / app update.
 *  - re-enables the accessibility service by itself if WRITE_SECURE_SETTINGS was granted (see README).
 */
object KeepAlive {
    fun wanted(ctx: Context) = Prefs.of(ctx).getBoolean("share_on", false)

    fun start(ctx: Context) {
        try { ContextCompat.startForegroundService(ctx, Intent(ctx, KeepAliveService::class.java)) } catch (e: Exception) { }
        scheduleWatchdog(ctx, 60_000L)
    }

    fun stop(ctx: Context) {
        try { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) } catch (e: Exception) { }
        cancelWatchdog(ctx)
    }

    private fun pending(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(ctx, 7, Intent(ctx, WatchdogReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun scheduleWatchdog(ctx: Context, inMs: Long) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + inMs, pending(ctx))
        } catch (e: Exception) { }
    }

    fun cancelWatchdog(ctx: Context) {
        try { (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(ctx)) } catch (e: Exception) { }
    }

    /** Switches the accessibility service back on if Android / HyperOS turned it off (needs the one-time adb grant). */
    fun reenableAccessibility(ctx: Context) {
        try {
            if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return
            val cn = ComponentName(ctx, RemoteInputService::class.java).flattenToString()
            val cr = ctx.contentResolver
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            if (!cur.split(':').contains(cn)) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (cur.isEmpty()) cn else "$cur:$cn")
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } catch (e: Exception) { }
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (!KeepAlive.wanted(ctx)) return
        KeepAlive.start(ctx)                       // also re-arms the next alarm
        RemoteInputService.instance?.heartbeat()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (KeepAlive.wanted(ctx)) KeepAlive.start(ctx)
    }
}

class KeepAliveService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var lastText = ""
    private var lastReenable = 0L
    private val loop = object : Runnable {
        override fun run() {
            if (!KeepAlive.wanted(this@KeepAliveService)) { stopSelf(); return }
            val svc = RemoteInputService.instance
            if (svc == null) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastReenable > 10_000L) { lastReenable = now; KeepAlive.reenableAccessibility(this@KeepAliveService) }
            } else svc.heartbeat()
            updateNotification()
            ui.postDelayed(this, 2000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("keepalive", "PC mouse link", NotificationManager.IMPORTANCE_LOW))
        startFg(buildNotification("Starting…"))
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonedeck:keepalive").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) { }
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "phonedeck:keepalive").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) { }
        ui.post(loop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        KeepAlive.scheduleWatchdog(this, 60_000L)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // swiped away from Recents: come straight back
        if (KeepAlive.wanted(this)) KeepAlive.scheduleWatchdog(this, 1_000L)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        ui.removeCallbacks(loop)
        try { wake?.release() } catch (e: Exception) { }
        try { wifi?.release() } catch (e: Exception) { }
        if (KeepAlive.wanted(this)) KeepAlive.scheduleWatchdog(this, 2_000L)   // killed by the system: restart
        super.onDestroy()
    }

    private fun startFg(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(77, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(77, n)
        } catch (e: Exception) { }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MouseShareActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "keepalive")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("PhoneDeck: PC mouse")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification() {
        val svc = RemoteInputService.instance
        val text = when {
            svc == null -> "Accessibility service is off: tap to fix"
            svc.isLinked -> "Connected to the PC"
            else -> "Searching for the PC…"
        }
        if (text != lastText) {
            lastText = text
            try { getSystemService(NotificationManager::class.java).notify(77, buildNotification(text)) } catch (e: Exception) { }
        }
    }
}
