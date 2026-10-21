package com.phonedeck.app

import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Everything that keeps PhoneDeck alive when you leave the app, swipe it away, lock the screen or reboot:
 *  - checklist of the Android permissions / settings that matter, and one-tap shortcuts to them;
 *  - shortcuts to the "autostart / background" screens of Xiaomi, Huawei, Oppo, Vivo, Samsung, OnePlus, Asus ...;
 *  - a network wake-up: Android calls us (even if the process was killed) whenever Wi-Fi / hotspot changes;
 *  - a persisted JobScheduler job that restarts the service every ~15 minutes, also after a reboot.
 * Together with the foreground service, the 1-minute alarm and the boot receiver (KeepAlive.kt) there are five
 * independent ways back, so one of them failing does not end the link.
 */
object Background {
    private const val JOB_ID = 4711

    data class Check(val label: String, val ok: Boolean, val fix: String)

    fun batteryExempt(ctx: Context): Boolean = try {
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (e: Exception) { false }

    fun notificationsOk(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) false
        else ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    } catch (e: Exception) { true }

    fun accessibilityOn(ctx: Context): Boolean {
        if (RemoteInputService.instance != null) return true
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains(ctx.packageName + "/") && s.contains("RemoteInputService")
    }

    /** Sending files from the tablet needs "All files access" (Android 11+); older Android has no such switch. */
    fun filesOk(ctx: Context): Boolean = Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()

    fun overlayOk(ctx: Context): Boolean = try { Settings.canDrawOverlays(ctx) } catch (e: Exception) { false }

    fun checklist(ctx: Context): List<Check> = listOf(
        Check("Battery: no restrictions (always active)", batteryExempt(ctx), "battery"),
        Check("Notifications allowed (keeps the service visible)", notificationsOk(ctx), "notify"),
        Check("Accessibility service on (PC mouse + keyboard on the tablet)", accessibilityOn(ctx), "a11y"),
        Check("Show over other apps (floating keyboard)", overlayOk(ctx), "overlay"),
        Check("All files access (drag files from the tablet to the PC)", filesOk(ctx), "files"),
        Check("Link saved (connect once, never again)", (Prefs.of(ctx).getString("link", "") ?: "").isNotBlank(), "link")
    )

    // ------------------------------------------------------------------ one-tap shortcuts
    fun requestBattery(act: Activity) {
        try {
            act.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + act.packageName)))
        } catch (e: Exception) {
            try { act.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (x: Exception) { appInfo(act) }
        }
    }

    fun requestNotifications(act: Activity) {
        if (Build.VERSION.SDK_INT >= 33 && act.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 11)
        } else {
            try {
                act.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, act.packageName))
            } catch (e: Exception) { appInfo(act) }
        }
    }

    fun requestOverlay(act: Activity) {
        try {
            act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + act.packageName)))
        } catch (e: Exception) { appInfo(act) }
    }

    fun requestAllFiles(act: Activity) {
        try {
            act.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + act.packageName)))
        } catch (e: Exception) {
            try { act.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (x: Exception) { appInfo(act) }
        }
    }

    fun isXiaomi(): Boolean = (Build.MANUFACTURER ?: "").lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }

    /** Xiaomi / HyperOS: the page with "Display pop-up windows while running in the background" (needed for the clipboard helper screen). */
    fun openMiuiPermissions(act: Activity): Boolean {
        for (cls in listOf("com.miui.permcenter.permissions.PermissionsEditorActivity", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")) {
            try {
                act.startActivity(Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter", cls)
                    .putExtra("extra_pkgname", act.packageName))
                return true
            } catch (e: Exception) { }
        }
        appInfo(act)
        return false
    }

    fun openAccessibility(act: Activity) {
        try { act.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) { appInfo(act) }
    }

    fun appInfo(act: Activity) {
        try {
            act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + act.packageName)))
        } catch (e: Exception) { }
    }

    /** First screen asking for the essentials after the very first connect (no repeat once granted). */
    fun askEssentials(act: Activity) {
        val p = Prefs.of(act)
        if (!notificationsOk(act) && !p.getBoolean("asked_notify", false)) {
            p.edit().putBoolean("asked_notify", true).apply()
            requestNotifications(act)
            return
        }
        if (!batteryExempt(act) && !p.getBoolean("asked_battery", false)) {
            p.edit().putBoolean("asked_battery", true).apply()
            requestBattery(act)
        }
    }

    // ------------------------------------------------------------------ manufacturer "autostart" screens
    private val OEM = listOf(
        // Xiaomi / Redmi / POCO (MIUI, HyperOS)
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.miui.securitycenter" to "com.miui.powercenter.PowerSettings",
        // Huawei / Honor
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        // Oppo / Realme / OnePlus (ColorOS, OxygenOS)
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        // Vivo / iQOO
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        // Samsung
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        "com.samsung.android.sm_cn" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        // Asus / Lenovo / Letv / Nokia / Meizu
        "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
        "com.lenovo.security" to "com.lenovo.security.purebackground.PureBackgroundActivity",
        "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
        "com.evenwell.powersaving.g3" to "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity",
        "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity"
    )

    fun manufacturer(): String = (Build.MANUFACTURER ?: "").trim().ifEmpty { "this phone" }

    /** Opens the first manufacturer screen that exists on this device, else the app info page. Returns true when a special screen opened. */
    fun openAutostart(act: Activity): Boolean {
        for ((pkg, cls) in OEM) {
            try {
                act.startActivity(Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: Exception) { }
        }
        appInfo(act)
        return false
    }

    // ------------------------------------------------------------------ wake-ups that survive a killed process
    private fun netPending(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(ctx, 9, Intent(ctx, NetworkWakeReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    /** Android calls [NetworkWakeReceiver] whenever any network appears / disappears / changes, even if our process is gone. */
    fun registerNetworkWake(ctx: Context) {
        try {
            val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
            val pi = netPending(ctx.applicationContext)
            try { cm.unregisterNetworkCallback(pi) } catch (e: Exception) { }
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), pi)
        } catch (e: Exception) { }
    }

    fun unregisterNetworkWake(ctx: Context) {
        try {
            ctx.applicationContext.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(netPending(ctx.applicationContext))
        } catch (e: Exception) { }
    }

    /** A job the system keeps across reboots: every ~15 min it restarts the service if it died. */
    fun scheduleRevivalJob(ctx: Context) {
        try {
            val js = ctx.applicationContext.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            val info = JobInfo.Builder(JOB_ID, ComponentName(ctx.applicationContext, RevivalJobService::class.java))
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build()
            js.schedule(info)
        } catch (e: Exception) { }
    }

    fun cancelRevivalJob(ctx: Context) {
        try { ctx.applicationContext.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) } catch (e: Exception) { }
    }
}

/** Woken by Android on every network change: bring the service and the link back, and reconnect at once. */
class NetworkWakeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (!KeepAlive.wanted(ctx)) return
        KeepAlive.start(ctx)
        LinkHub.ensure(ctx)
        LinkHub.networkEvent()
    }
}

class RevivalJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        if (KeepAlive.wanted(this)) {
            KeepAlive.start(this)
            LinkHub.ensure(this)
        }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
