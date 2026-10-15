package com.phonedeck.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the link to the PC strong while DeckActivity is on screen.
 *  1. Binds the app to the network that actually contains the PC. A Wi-Fi without internet is otherwise
 *     silently dropped by Android in favour of mobile data, which cannot see the PC.
 *  2. Holds a low-latency Wi-Fi lock so the radio does not nap between key presses.
 *  3. Pings the PC's port every few seconds (and instantly when the network changes), measures the delay,
 *     reports ONLINE / SLOW / OFFLINE / NO_NETWORK, and says when the PC came back after an outage.
 */
class ConnectionGuard(ctx: Context, url: String, private val listener: Listener) {

    enum class State { ONLINE, SLOW, OFFLINE, NO_NETWORK }

    /** Both callbacks arrive on the main thread. */
    interface Listener {
        fun onState(state: State, rttMs: Int)
        fun onRecovered(outageMs: Long)
    }

    private val app = ctx.applicationContext
    private val cm: ConnectivityManager? = app.getSystemService(ConnectivityManager::class.java)
    private val ui = Handler(Looper.getMainLooper())
    private val host: String = Uri.parse(url).host ?: ""
    private val port: Int = LinkUtil.portOf(url)
    private val running = AtomicBoolean(false)
    private val pokeQ = LinkedBlockingQueue<Int>()
    private val nets = ConcurrentHashMap<Network, LinkProperties>()
    @Volatile private var hostAddr: InetAddress? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        registerNetworkCallback()
        acquireWifiLock()
        val t = Thread({ loop() }, "pd-guard")
        t.isDaemon = true
        t.start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        pokeQ.offer(1)
        try { callback?.let { cm?.unregisterNetworkCallback(it) } } catch (e: Exception) { }
        callback = null
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        wifiLock = null
        try { cm?.bindProcessToNetwork(null) } catch (e: Exception) { }
    }

    /** Check right now instead of waiting for the next tick. */
    fun pokeNow() { pokeQ.offer(1) }

    // ------------------------------------------------------------------ network binding
    // ==== ANCHOR:GUARD_BIND ====
    private fun registerNetworkCallback() {
        val c = cm ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                c.getLinkProperties(network)?.let { nets[network] = it }
                rebind()
                pokeQ.offer(1)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                nets[network] = linkProperties
                rebind()
            }

            override fun onLost(network: Network) {
                nets.remove(network)
                rebind()
                pokeQ.offer(1)
            }
        }
        try {
            c.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
            callback = cb
        } catch (e: Exception) { }
    }

    private fun ipToLong(a: Inet4Address): Long {
        val b = a.address
        return ((b[0].toLong() and 255L) shl 24) or ((b[1].toLong() and 255L) shl 16) or
            ((b[2].toLong() and 255L) shl 8) or (b[3].toLong() and 255L)
    }

    /** Binds to the network whose subnet contains the PC; if none does, normal routing is used. */
    private fun rebind() {
        val c = cm ?: return
        val addr = hostAddr
        var target: Network? = null
        if (addr is Inet4Address) {
            val h = ipToLong(addr)
            for ((n, lp) in nets) {
                for (la in lp.linkAddresses) {
                    val a = la.address
                    if (a is Inet4Address) {
                        val p = la.prefixLength
                        if (p <= 0 || p >= 32) continue
                        val mask = (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
                        if ((ipToLong(a) and mask) == (h and mask)) target = n
                    }
                }
            }
        }
        try { c.bindProcessToNetwork(target) } catch (e: Exception) { }
    }

    private fun acquireWifiLock() {
        try {
            val wm = app.getSystemService(WifiManager::class.java) ?: return
            val lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "PhoneDeck")
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
        } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ heartbeat
    // ==== ANCHOR:GUARD_LOOP ====
    private fun hasNetwork(): Boolean = nets.isNotEmpty() || cm?.activeNetwork != null

    private fun probe(): Boolean = try {
        Socket().use { s -> s.connect(InetSocketAddress(host, port), 2500) }
        true
    } catch (e: Exception) {
        false
    }

    private fun post(s: State, rtt: Int) {
        ui.post { if (running.get()) listener.onState(s, rtt) }
    }

    private fun loop() {
        hostAddr = try { InetAddress.getByName(host) } catch (e: Exception) { null }
        rebind()
        var fails = 0
        var firstFail = 0L
        var down = false
        while (running.get()) {
            val t0 = SystemClock.elapsedRealtime()
            val ok = probe()
            val rtt = (SystemClock.elapsedRealtime() - t0).toInt()
            if (!running.get()) break
            if (ok) {
                val wasDown = down
                val outage = if (firstFail != 0L) t0 - firstFail else 0L
                fails = 0; firstFail = 0L; down = false
                post(if (rtt > 350) State.SLOW else State.ONLINE, rtt)
                if (wasDown) ui.post { if (running.get()) listener.onRecovered(outage) }
            } else {
                fails++
                if (fails == 1) firstFail = t0
                if (fails >= 2) {
                    down = true
                    post(if (hasNetwork()) State.OFFLINE else State.NO_NETWORK, -1)
                }
                if (hostAddr == null) {          // name could not be resolved yet: try again
                    hostAddr = try { InetAddress.getByName(host) } catch (e: Exception) { null }
                    rebind()
                }
            }
            val wait = when {
                fails >= 2 -> minOf(1200L + fails * 400L, 4000L)
                fails == 1 -> 800L
                else -> 3500L
            }
            try { pokeQ.poll(wait, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { break }
            pokeQ.clear()
        }
    }
}
