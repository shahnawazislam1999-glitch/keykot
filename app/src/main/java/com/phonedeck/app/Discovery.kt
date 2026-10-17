package com.phonedeck.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Finds the PC again when its IP address changed (new router, or you switched to another phone's hotspot).
 *  - scans every local network the tablet is on (Wi-Fi, USB tethering, Ethernet) and NEVER mobile data: each socket is bound to
 *    its own network, so it works even when Android would otherwise route traffic over the cellular network;
 *  - a device only counts when it really is a PhoneDeck PC (it answers like one), not any device with port 8080 open;
 *  - the secret key is only sent to a device that has already proven to be PhoneDeck.
 */
object Discovery {
    /** keyOk: true = the saved key works, false = PhoneDeck found but the key is old, null = not checked. */
    data class Hit(val ip: String, val ms: Int, val keyOk: Boolean?, val network: Network?)

    private class Net(val network: Network?, val subnets: List<Pair<Long, Int>>)

    private fun toLong(a: Inet4Address): Long {
        val b = a.address
        return ((b[0].toLong() and 255L) shl 24) or ((b[1].toLong() and 255L) shl 16) or
            ((b[2].toLong() and 255L) shl 8) or (b[3].toLong() and 255L)
    }

    private fun fmt(v: Long): String =
        "" + ((v shr 24) and 255L) + "." + ((v shr 16) and 255L) + "." + ((v shr 8) and 255L) + "." + (v and 255L)

    private fun isPrivate(v: Long): Boolean {
        val a = (v shr 24) and 255L
        val b = (v shr 16) and 255L
        return a == 10L || (a == 172L && b >= 16L && b <= 31L) || (a == 192L && b == 168L)
    }

    private val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    private fun parseIp(s: String?): Long? {
        val m = ipv4.find(s ?: return null) ?: return null
        var v = 0L
        for (i in 1..4) {
            val o = m.groupValues[i].toInt()
            if (o > 255) return null
            v = (v shl 8) or o.toLong()
        }
        return v
    }

    /** Interface-based fallback (also finds this device's own hotspot / USB-tether subnets). */
    private fun interfaceSubnets(): List<Pair<Long, Int>> {
        val out = ArrayList<Pair<Long, Int>>()
        try {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return out
            for (nif in Collections.list(ifs)) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                        val v = toLong(a)
                        if (isPrivate(v)) out.add(Pair(v, ia.networkPrefixLength.toInt()))
                    }
                }
            }
        } catch (e: Exception) { }
        return out
    }

    /** Every Wi-Fi / Ethernet(USB tether) / Bluetooth network the device is on right now, each with its own subnets. Cellular is skipped. */
    private fun localNets(ctx: Context): List<Net> {
        val out = ArrayList<Net>()
        val covered = HashSet<Long>()
        try {
            val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
            if (cm != null) {
                for (n in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(n) ?: continue
                    val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
                    if (!local) continue
                    val lp = cm.getLinkProperties(n) ?: continue
                    val subs = ArrayList<Pair<Long, Int>>()
                    for (la in lp.linkAddresses) {
                        val a = la.address
                        if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                            val v = toLong(a)
                            if (isPrivate(v)) { subs.add(Pair(v, la.prefixLength)); covered.add(v) }
                        }
                    }
                    if (subs.isNotEmpty()) out.add(Net(n, subs))
                }
            }
        } catch (e: Exception) { }
        val extra = interfaceSubnets().filter { it.first !in covered }
        if (extra.isNotEmpty()) out.add(Net(null, extra))
        return out
    }

    private fun connectOpen(net: Net, ip: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { s ->
            net.network?.bindSocket(s)
            s.connect(InetSocketAddress(ip, port), timeoutMs)
        }
        true
    } catch (e: Exception) {
        false
    }

    /** GET [path]; returns (status code, first part of the body). code -1 = no HTTP answer. */
    private fun httpGet(net: Net, ip: String, port: Int, path: String, readBody: Boolean): Pair<Int, String> {
        var c: HttpURLConnection? = null
        return try {
            val u = URL("http://" + ip + ":" + port + path)
            c = (net.network?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
            c.connectTimeout = 900
            c.readTimeout = 1800
            c.instanceFollowRedirects = false
            c.useCaches = false
            val code = c.responseCode
            var body = ""
            if (readBody) {
                body = try {
                    val st = if (code >= 400) c.errorStream else c.inputStream
                    st?.bufferedReader()?.use { r -> r.readText().take(400) } ?: ""
                } catch (e: Exception) { "" }
            }
            Pair(code, body)
        } catch (e: Exception) {
            Pair(-1, "")
        } finally {
            try { c?.disconnect() } catch (e: Exception) { }
        }
    }

    /** PhoneDeck answers a request WITHOUT the key with 403 and "...ends with ?key=...". No key is sent to unknown devices. */
    private fun isPhoneDeck(net: Net, ip: String, port: Int): Boolean {
        val (code, body) = httpGet(net, ip, port, "/", true)
        return code == 403 && body.contains("?key=")
    }

    /** Now that the device proved to be PhoneDeck: does the saved key work? (/ping on newer servers, the page itself on older ones) */
    private fun keyWorks(net: Net, ip: String, port: Int, key: String): Boolean {
        val q = "key=" + Uri.encode(key)
        var code = httpGet(net, ip, port, "/ping?$q", false).first
        if (code == 404 || code == -1) code = httpGet(net, ip, port, "/?$q", false).first
        return code == 200
    }

    // ==== ANCHOR:DISCOVERY_SCAN ====
    /**
     * Looks for PhoneDeck PCs on [port] on every local network (a /24 at most per subnet).
     * [hint] = the last known PC address: it and its neighbours are tried first.
     * [key] = the key of the saved link ("" = do not check it). [stopOnFirst] = quit as soon as a usable PC is found.
     */
    fun scan(
        ctx: Context, port: Int, hint: String?, key: String, stopOnFirst: Boolean,
        onProgress: (Int, Int) -> Unit, isCancelled: () -> Boolean
    ): List<Hit> {
        val hintLong = parseIp(hint)
        val self = HashSet<Long>()
        val targets = ArrayList<Pair<Net, Long>>()
        val seen = HashSet<Long>()
        for (net in localNets(ctx)) {
            for ((addr, prefix) in net.subnets) {
                self.add(addr)
                val p = if (prefix < 24) 24 else if (prefix > 30) 30 else prefix
                val mask = (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
                val base = addr and mask
                val size = 1L shl (32 - p)
                var i = 1L
                while (i < size - 1) {
                    val ip = base + i
                    if (seen.add(ip)) targets.add(Pair(net, ip))
                    i++
                }
            }
        }
        targets.removeAll { it.second in self }
        // the old PC address and its neighbours first (a router usually hands out a close address), then everything else
        targets.sortBy { t ->
            val ip = t.second
            if (hintLong != null && (ip ushr 8) == (hintLong ushr 8)) abs(ip - hintLong) else 1000L + (ip and 255L)
        }
        val total = targets.size
        val hits = Collections.synchronizedList(ArrayList<Hit>())
        if (total == 0) return hits
        val found = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(64)
        val latch = CountDownLatch(total)
        val done = AtomicInteger(0)
        for ((net, ipL) in targets) {
            pool.execute {
                try {
                    if (!isCancelled() && !found.get()) {
                        val ip = fmt(ipL)
                        val t0 = SystemClock.elapsedRealtime()
                        if (connectOpen(net, ip, port, 450)) {
                            val ms = (SystemClock.elapsedRealtime() - t0).toInt()
                            if (isPhoneDeck(net, ip, port)) {
                                val ok: Boolean? = if (key.isEmpty()) null else keyWorks(net, ip, port, key)
                                hits.add(Hit(ip, ms, ok, net.network))
                                if (stopOnFirst && ok != false) found.set(true)
                            }
                        }
                    }
                } catch (e: Exception) {
                } finally {
                    val d = done.incrementAndGet()
                    if (d % 8 == 0 || d == total) onProgress(d, total)
                    latch.countDown()
                }
            }
        }
        try { latch.await() } catch (e: InterruptedException) { }
        pool.shutdownNow()
        return synchronized(hits) { hits.sortedWith(compareBy<Hit>({ if (it.keyOk == false) 1 else 0 }, { it.ms })) }
    }

    /** Progress dialog -> list of PCs found. [onPick] gets the chosen IP address. */
    fun showFindDialog(act: Activity, port: Int, hint: String? = null, key: String = "", onPick: (String) -> Unit) {
        val cancelled = AtomicBoolean(false)
        val tv = TextView(act)
        tv.text = "Looking for a PhoneDeck PC on port $port …"
        tv.setPadding(56, 40, 56, 16)
        val dlg = AlertDialog.Builder(act)
            .setTitle("Find PC")
            .setView(tv)
            .setNegativeButton("Cancel") { _, _ -> cancelled.set(true) }
            .setOnCancelListener { cancelled.set(true) }
            .create()
        dlg.show()
        Thread {
            val hits = scan(act, port, hint, key, false, { d, t -> act.runOnUiThread { tv.text = "Searching … $d / $t" } }, { cancelled.get() })
            act.runOnUiThread {
                if (dlg.isShowing) dlg.dismiss()
                if (act.isFinishing || act.isDestroyed || cancelled.get()) return@runOnUiThread
                if (hits.isEmpty()) {
                    AlertDialog.Builder(act).setTitle("No PC found")
                        .setMessage(
                            "No PhoneDeck PC answered on port $port.\n\nCheck that PhoneDeck is running on the PC, that the PC and this " +
                                "device are on the same Wi-Fi/hotspot, and that the PC firewall allows the port (run fix-connection.bat on the PC)."
                        )
                        .setPositiveButton("OK", null).show()
                } else {
                    val labels = hits.map { it.ip + "   (" + it.ms + " ms)" + (if (it.keyOk == false) "   - key is old" else "") }.toTypedArray()
                    AlertDialog.Builder(act).setTitle("PC found - tap one")
                        .setItems(labels) { _, i -> onPick(hits[i].ip) }
                        .setNegativeButton("Close", null).show()
                }
            }
        }.start()
    }
}

/**
 * Hotspot switching without touching the screen. Feed it the connection state:
 *  - [outage] every time the PC is unreachable; after a few seconds it searches the networks this device is on now;
 *  - [networkChanged] when Android reports a different network (a new hotspot): the search starts almost at once;
 *  - [reset] when the PC answers again.
 * When a usable PC is found at a new address, [onMoved] gets the new IP. Call all methods on the main thread.
 */
class AutoFinder(
    private val ctx: Context,
    private val currentUrl: () -> String,
    private val onMoved: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val ui = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private var since = 0L
    private var lastTry = 0L
    private var changedAt = 0L
    /** Text for the banner while a search is running / has failed (null = nothing special to say). */
    @Volatile var status: String? = null

    fun reset() { since = 0L; status = null }

    fun networkChanged() {
        changedAt = SystemClock.elapsedRealtime()
        lastTry = 0L
    }

    fun outage() {
        val now = SystemClock.elapsedRealtime()
        if (since == 0L) since = now
        val wait = if (changedAt != 0L && now - changedAt < 20000L) 1200L else 4000L
        if (now - since < wait || now - lastTry < 9000L) return
        if (!busy.compareAndSet(false, true)) return
        lastTry = now
        val url = currentUrl()
        val host = Uri.parse(url).host ?: ""
        val port = LinkUtil.portOf(url)
        val key = Uri.parse(url).getQueryParameter("key") ?: ""
        status = "\uD83D\uDD0E Searching for the PC on this network\u2026"
        onStatus(status!!)
        Thread {
            val hits = try {
                Discovery.scan(ctx, port, host, key, true, { _, _ -> }, { false })
            } catch (e: Exception) {
                emptyList()
            }
            ui.post {
                busy.set(false)
                val good = hits.firstOrNull { it.keyOk != false }
                if (good != null) {
                    since = 0L
                    status = null
                    if (good.ip != host) onMoved(good.ip)
                } else if (hits.isNotEmpty()) {
                    status = "\u26A0 PC found, but the link key is old - tap Change link and paste the current link"
                    onStatus(status!!)
                } else {
                    status = "\u26A0 PC not found yet - join the same hotspot as the PC (searching again\u2026)"
                    onStatus(status!!)
                }
            }
        }.start()
    }
}

/** One-shot "is the PC reachable?" check with a plain-language answer. */
object Probe {
    data class Result(val ok: Boolean, val text: String)

    fun check(url: String): Result {
        val host = Uri.parse(url).host ?: return Result(false, "There is no address in that link.")
        val port = LinkUtil.portOf(url)
        val t0 = SystemClock.elapsedRealtime()
        try {
            Socket().use { s -> s.connect(InetSocketAddress(host, port), 3000) }
        } catch (e: SocketTimeoutException) {
            return Result(false, "Timed out - the PC did not answer. Same Wi-Fi/hotspot? Is a firewall blocking port $port?")
        } catch (e: ConnectException) {
            return Result(false, "Refused - PhoneDeck is not running on the PC, or the port in the link is wrong.")
        } catch (e: UnknownHostException) {
            return Result(false, "Unknown address \"$host\".")
        } catch (e: Exception) {
            return Result(false, "Cannot connect: " + (e.message ?: e.javaClass.simpleName))
        }
        val ms = SystemClock.elapsedRealtime() - t0
        var code = -1
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 4000
            code = c.responseCode
            c.disconnect()
        } catch (e: Exception) { }
        return when {
            code in 200..399 -> Result(true, "Connected - $ms ms (HTTP $code).")
            code == 403 -> Result(false, "The PC answered but refused the link (old or wrong key). Paste the current link.")
            code > 0 -> Result(true, "The PC answered with HTTP $code ($ms ms).")
            else -> Result(true, "The port is open ($ms ms) but the page did not answer yet.")
        }
    }
}
