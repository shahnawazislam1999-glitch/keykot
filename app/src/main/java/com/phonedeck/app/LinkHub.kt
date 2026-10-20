package com.phonedeck.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one and only owner of the link between this tablet and the PC. It lives in the app process, NOT in an activity,
 * so the link keeps running when you minimise or close the app. It does not even need the accessibility service:
 * files can be dragged over without it (the pointer / keyboard part needs it and is handed to [RemoteInputService]).
 *
 * "Connect once, never again": the link (address + key) is saved the first time; from then on this hub reconnects by itself.
 *
 * Switching to another Wi-Fi / hotspot, tried in parallel as fast as possible:
 *   1. the network callback notices the change at once and drops the old (dead) socket;
 *   2. the address that worked on this network last time (NetMemory) and the router / hotspot address are probed;
 *   3. the PC's UDP beacon (every 2 s) tells the tablet where the PC is right now;
 *   4. the full network scan of [AutoFinder] is the last resort.
 * The first one that finds a real PhoneDeck PC with the saved key wins and the saved link is updated.
 */
object LinkHub {
    private val ui = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var link: TabletLink? = null
    private var finder: AutoFinder? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var keepCb: ConnectivityManager.NetworkCallback? = null
    private var beacon: BeaconListener? = null
    private var netSig = ""
    private var lastBeat = 0L
    private var lastQuick = 0L
    private var lastBeaconKick = 0L
    private val quickBusy = AtomicBoolean(false)
    private val beaconBusy = AtomicBoolean(false)
    private val beaconTried = ConcurrentHashMap<String, Long>()
    private val beaconSeenAt = ConcurrentHashMap<String, Long>()

    /** SystemClock time of the last successful connect (0 = never since the process started). */
    @Volatile var lastConnectedAt = 0L
        private set

    val connected: Boolean get() = link?.connected == true

    /** The PC address in the saved link. */
    val host: String
        get() {
            val a = appCtx ?: return ""
            return Uri.parse(Prefs.of(a).getString("link", "") ?: "").host ?: ""
        }

    fun keyOfLink(): String {
        val a = appCtx ?: return ""
        return Uri.parse(Prefs.of(a).getString("link", "") ?: "").getQueryParameter("key") ?: ""
    }

    // ------------------------------------------------------------------ start / stop
    /** Idempotent. Call it from anywhere (app start, service, boot, network wake-up, after saving a link). */
    fun ensure(ctx: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) { ui.post { ensure(ctx) }; return }
        val a = ctx.applicationContext
        appCtx = a
        if (!KeepAlive.wanted(a)) { stop(); return }
        if (LinkUtil.parse(Prefs.of(a).getString("link", "")) == null) return      // nothing saved yet
        val l = link ?: TabletLink(a, ui, { onMsg(it) }, { onState(it) }).also { link = it }
        if (finder == null) {
            finder = AutoFinder(a, { Prefs.of(a).getString("link", "") ?: "" }, { ip -> moveTo(ip, null) }, { })
        }
        registerNet(a)
        startBeacon(a)
        l.start()
    }

    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) { ui.post { stop() }; return }
        val a = appCtx
        try { beacon?.stop() } catch (e: Exception) { }
        beacon = null
        if (a != null) {
            try {
                val cm = a.getSystemService(ConnectivityManager::class.java)
                netCb?.let { cm.unregisterNetworkCallback(it) }
                keepCb?.let { cm.unregisterNetworkCallback(it) }
            } catch (e: Exception) { }
        }
        netCb = null
        keepCb = null
        link?.stop()
        link = null
        finder = null
        if (a != null) FileReceiver.onLinkLost(a)
    }

    // ------------------------------------------------------------------ heartbeat (every ~2 s from KeepAliveService)
    fun heartbeat() {
        val a = appCtx ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastBeat < 1400L) return
        lastBeat = now
        if (!KeepAlive.wanted(a)) return
        val l = link
        if (l == null) { ensure(a); return }
        l.tick()
        if (l.connected) {
            finder?.reset()
        } else {
            finder?.outage()
            quickProbe(a, false)
        }
        // safety net: a network change the callbacks did not tell us about
        val sig = NetInfo.signature(a)
        if (sig != netSig) { netSig = sig; onNetworkChanged(a) }
    }

    /** Drop everything and reconnect right now (notification button, "Reconnect"). */
    fun kickNow() {
        val a = appCtx ?: return
        finder?.networkChanged()
        link?.kick()
        quickProbe(a, true)
    }

    // ------------------------------------------------------------------ messages
    private fun onMsg(o: JSONObject) {
        val a = appCtx
        when (o.optString("t")) {
            "drag" -> if (a != null) FileReceiver.onDrag(a, o)
            "drop" -> if (a != null) FileReceiver.onDrop(a)
            "dragend" -> if (a != null) FileReceiver.onDragEnd(a)
            "pong" -> {}
            else -> RemoteInputService.instance?.onHubMessage(o)
        }
    }

    private fun onState(c: Boolean) {
        val a = appCtx ?: return
        if (c) {
            lastConnectedAt = SystemClock.elapsedRealtime()
            finder?.reset()
            val h = host
            if (h.isNotEmpty()) NetMemory.remember(a, h)
            sendHello()
        } else {
            FileReceiver.onLinkLost(a)
        }
        RemoteInputService.instance?.onLinkState(c)
    }

    fun send(o: JSONObject): Boolean = link?.send(o) ?: false

    /** Tell the PC who we are (screen size, what we can do) and the settings it needs. */
    fun sendHello() {
        val a = appCtx ?: return
        val l = link ?: return
        if (!l.connected) return
        val (w, h) = screenSize(a)
        val p = Prefs.of(a)
        l.send(JSONObject().put("t", "hello").put("w", w).put("h", h)
            .put("fd", true).put("inp", RemoteInputService.instance != null))
        l.send(JSONObject().put("t", "cfg").put("edge", p.getString("pc_edge", "right"))
            .put("on", KeepAlive.wanted(a)).put("filedrag", FileReceiver.enabled(a)))
    }

    /** The accessibility service came or went: the PC must know whether a pointer can be shown. */
    fun onInputServiceChanged() { sendHello() }

    private fun screenSize(a: Context): Pair<Int, Int> {
        RemoteInputService.instance?.screenSize()?.let { return it }
        return try {
            val wm = a.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= 30) {
                val b = wm.maximumWindowMetrics.bounds
                Pair(b.width(), b.height())
            } else {
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
                Pair(dm.widthPixels, dm.heightPixels)
            }
        } catch (e: Exception) { Pair(1920, 1200) }
    }

    // ------------------------------------------------------------------ network changes (hotspot switch)
    private val netRun = Runnable {
        val a = appCtx
        if (a != null) {
            val sig = NetInfo.signature(a)
            if (sig != netSig) { netSig = sig; onNetworkChanged(a) }
        }
    }

    /** Something about the networks may have changed (called by callbacks, the wake-up receiver, the heartbeat). */
    fun networkEvent() {
        ui.removeCallbacks(netRun)
        ui.postDelayed(netRun, 350)
    }

    private fun onNetworkChanged(a: Context) {
        val l = link ?: return
        finder?.networkChanged()
        // the old socket belongs to the old network: drop it and reconnect now, while the probes look for the PC's new address
        l.kick()
        quickProbe(a, true)
    }

    private fun registerNet(a: Context) {
        if (netCb != null) return
        netSig = NetInfo.signature(a)
        try {
            val cm = a.getSystemService(ConnectivityManager::class.java) ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { ui.post { networkEvent() } }
                override fun onLost(network: Network) { ui.post { networkEvent() } }
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) { ui.post { networkEvent() } }
            }
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
            netCb = cb
        } catch (e: Exception) { }
        try {
            // Asking for the Wi-Fi network keeps Android from dropping a hotspot that has no internet in favour of mobile data.
            val cm = a.getSystemService(ConnectivityManager::class.java) ?: return
            val keep = object : ConnectivityManager.NetworkCallback() {}
            cm.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), keep)
            keepCb = keep
        } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ finding the PC fast
    /** Probes the most likely PC addresses for the networks we are on now. [force] = ignore the 3 s pause between probes. */
    private fun quickProbe(a: Context, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastQuick < 3000L) return
        if (!quickBusy.compareAndSet(false, true)) return
        lastQuick = now
        val url = Prefs.of(a).getString("link", "") ?: ""
        val curHost = Uri.parse(url).host ?: ""
        val port = LinkUtil.portOf(url)
        val key = Uri.parse(url).getQueryParameter("key") ?: ""
        val locals = NetInfo.locals(a)
        if (curHost.isEmpty() || locals.isEmpty()) { quickBusy.set(false); return }
        val cands = LinkedHashSet<String>()
        cands.addAll(NetMemory.recall(a))                       // the PC address that worked on this network before
        for (l in locals) l.gateway?.let { cands.add(it) }      // the PC may be the one sharing the hotspot
        for ((ip, t) in beaconSeenAt) if (now - t < 15_000L) cands.add(ip)
        cands.add(curHost)
        Thread {
            val hits = try { Discovery.probe(a, port, key, cands.toList()) } catch (e: Exception) { emptyList() }
            ui.post {
                quickBusy.set(false)
                val good = hits.firstOrNull { it.keyOk != false }
                if (good != null) {
                    if (good.ip != host) moveTo(good.ip, null) else if (!connected) link?.kick()
                }
            }
        }.start()
    }

    /** The PC is at a new address: save it (so the next start already knows it) and reconnect. */
    private fun moveTo(ip: String, port: Int?) {
        val a = appCtx ?: return
        val cur = Prefs.of(a).getString("link", "") ?: ""
        if (cur.isEmpty()) return
        var u = LinkUtil.withHost(cur, ip)
        if (port != null && port in 1..65535 && port != LinkUtil.portOf(cur)) u = LinkUtil.withHostPort(cur, ip, port)
        if (u == cur) return
        Prefs.of(a).edit().putString("link", u).apply()
        LinkHistory.add(a, u)
        link?.kick()
    }

    // ------------------------------------------------------------------ the PC's UDP beacon
    private fun startBeacon(a: Context) {
        if (beacon != null) return
        beacon = BeaconListener(a) { ip, port -> ui.post { onBeacon(ip, port) } }.also { it.start() }
    }

    private fun onBeacon(ip: String, port: Int) {
        val a = appCtx ?: return
        val now = SystemClock.elapsedRealtime()
        beaconSeenAt[ip] = now
        if (connected) return                                    // all fine; this may just be another adapter of the PC
        val cur = host
        if (ip == cur) {
            if (now - lastBeaconKick > 4000L) { lastBeaconKick = now; link?.kick() }
            return
        }
        // the PC announced itself at an address we do not use: check it really answers, then switch to it
        if ((beaconTried[ip] ?: 0L) + 5000L > now) return
        if (!beaconBusy.compareAndSet(false, true)) return
        beaconTried[ip] = now
        val url = Prefs.of(a).getString("link", "") ?: ""
        val usePort = if (port in 1..65535) port else LinkUtil.portOf(url)
        val key = Uri.parse(url).getQueryParameter("key") ?: ""
        Thread {
            val hits = try { Discovery.probe(a, usePort, key, listOf(ip)) } catch (e: Exception) { emptyList() }
            ui.post {
                beaconBusy.set(false)
                if (hits.any { it.keyOk != false } && !connected) moveTo(ip, usePort)
            }
        }.start()
    }

    // ------------------------------------------------------------------ for the notification
    fun statusText(a: Context): String {
        val svc = RemoteInputService.instance
        return when {
            connected -> "Connected to the PC (" + host + ")" +
                (if (svc == null) " • accessibility off: files only" else "")
            NetInfo.locals(a).isEmpty() -> "Waiting for Wi-Fi / hotspot…"
            else -> finder?.status?.takeIf { it.isNotBlank() } ?: "Searching for the PC…"
        }
    }
}

/**
 * Listens for the PC's "PhoneDeck is here" broadcast (UDP 48123, every 2 s). Only packets whose fingerprint matches the
 * saved key are used; the key itself is never broadcast.
 */
class BeaconListener(private val ctx: Context, private val onSeen: (String, Int) -> Unit) {
    companion object {
        const val PORT = 48123

        fun fingerprint(key: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(("pd:$key").toByteArray(Charsets.UTF_8))
            val sb = StringBuilder()
            for (i in 0 until 4) sb.append(String.format("%02x", d[i].toInt() and 255))
            return sb.toString()
        }
    }

    @Volatile private var running = false
    @Volatile private var sock: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null

    fun start() {
        if (running) return
        running = true
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wm.createMulticastLock("phonedeck-beacon").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) { }
        val t = Thread({ loop() }, "pd-beacon")
        t.isDaemon = true
        t.start()
    }

    fun stop() {
        running = false
        try { sock?.close() } catch (e: Exception) { }
        try { lock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        lock = null
    }

    private fun loop() {
        while (running) {
            var s: DatagramSocket? = null
            try {
                s = DatagramSocket(null)
                s.reuseAddress = true
                s.bind(InetSocketAddress(PORT))
                s.soTimeout = 1500
                sock = s
                val buf = ByteArray(512)
                while (running) {
                    val pk = DatagramPacket(buf, buf.size)
                    try { s.receive(pk) } catch (e: SocketTimeoutException) { continue }
                    handle(pk)
                }
            } catch (e: Exception) {
                if (running) SystemClock.sleep(3000)               // port busy / no network yet: try again
            } finally {
                try { s?.close() } catch (e: Exception) { }
            }
        }
    }

    private fun handle(pk: DatagramPacket) {
        try {
            val o = JSONObject(String(pk.data, 0, pk.length, Charsets.UTF_8))
            if (o.optString("app") != "PhoneDeck") return
            val key = LinkHub.keyOfLink()
            if (key.isEmpty() || o.optString("id") != fingerprint(key)) return
            val ip = pk.address?.hostAddress ?: return
            onSeen(ip, o.optInt("port", 0))
        } catch (e: Exception) { }
    }
}
