package com.phonedeck.app

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
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

/** Finds the PC again when its IP address changed (router gave it a new one). */
object Discovery {
    data class Hit(val ip: String, val ms: Int)

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

    /** Every private IPv4 subnet this device is on: Wi-Fi, its own hotspot, USB tethering, Ethernet. */
    private fun localSubnets(): List<Pair<Long, Int>> {
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

    // ==== ANCHOR:DISCOVERY_SCAN ====
    /** Tries [port] on every address of the local subnets (at most a /24 each) and returns those that answer. */
    fun scan(port: Int, onProgress: (Int, Int) -> Unit, isCancelled: () -> Boolean): List<Hit> {
        val targets = LinkedHashSet<Long>()
        val self = HashSet<Long>()
        for ((addr, prefix) in localSubnets()) {
            self.add(addr)
            val p = if (prefix < 24) 24 else if (prefix > 30) 30 else prefix
            val mask = (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
            val net = addr and mask
            val size = 1L shl (32 - p)
            var i = 1L
            while (i < size - 1) { targets.add(net + i); i++ }
        }
        targets.removeAll(self)
        val list = targets.toList()
        val total = list.size
        val hits = Collections.synchronizedList(ArrayList<Hit>())
        if (total == 0) return hits
        val pool = Executors.newFixedThreadPool(48)
        val latch = CountDownLatch(total)
        val done = AtomicInteger(0)
        for (ip in list) {
            pool.execute {
                try {
                    if (!isCancelled()) {
                        val t0 = SystemClock.elapsedRealtime()
                        try {
                            Socket().use { s -> s.connect(InetSocketAddress(fmt(ip), port), 350) }
                            hits.add(Hit(fmt(ip), (SystemClock.elapsedRealtime() - t0).toInt()))
                        } catch (e: Exception) { }
                    }
                } finally {
                    val d = done.incrementAndGet()
                    if (d % 8 == 0 || d == total) onProgress(d, total)
                    latch.countDown()
                }
            }
        }
        try { latch.await() } catch (e: InterruptedException) { }
        pool.shutdownNow()
        return synchronized(hits) { hits.sortedBy { it.ms } }
    }

    /** Progress dialog -> list of PCs found. [onPick] gets the chosen IP address. */
    fun showFindDialog(act: Activity, port: Int, onPick: (String) -> Unit) {
        val cancelled = AtomicBoolean(false)
        val tv = TextView(act)
        tv.text = "Looking for a PC that answers on port $port …"
        tv.setPadding(56, 40, 56, 16)
        val dlg = AlertDialog.Builder(act)
            .setTitle("Find PC")
            .setView(tv)
            .setNegativeButton("Cancel") { _, _ -> cancelled.set(true) }
            .setOnCancelListener { cancelled.set(true) }
            .create()
        dlg.show()
        Thread {
            val hits = scan(port, { d, t -> act.runOnUiThread { tv.text = "Searching … $d / $t" } }, { cancelled.get() })
            act.runOnUiThread {
                if (dlg.isShowing) dlg.dismiss()
                if (act.isFinishing || act.isDestroyed || cancelled.get()) return@runOnUiThread
                if (hits.isEmpty()) {
                    AlertDialog.Builder(act).setTitle("No PC found")
                        .setMessage(
                            "Nothing answered on port $port.\n\nCheck that PhoneDeck is running on the PC, that both are on the " +
                                "same Wi-Fi/hotspot, and that the PC firewall allows the port."
                        )
                        .setPositiveButton("OK", null).show()
                } else {
                    val labels = hits.map { it.ip + "   (" + it.ms + " ms)" }.toTypedArray()
                    AlertDialog.Builder(act).setTitle("PC found - tap one")
                        .setItems(labels) { _, i -> onPick(hits[i].ip) }
                        .setNegativeButton("Close", null).show()
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
