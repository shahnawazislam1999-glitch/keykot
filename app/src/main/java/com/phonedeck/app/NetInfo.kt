package com.phonedeck.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * What networks is this tablet on right now? (Wi-Fi, USB tethering = Ethernet, Bluetooth PAN; never mobile data.)
 * Used to notice a hotspot / Wi-Fi change, to remember "which PC address belongs to which network", and to send every
 * socket of the app over the right network even when Android would prefer mobile data (Wi-Fi without internet).
 */
object NetInfo {
    /** One IPv4 address of one local network. [gateway] is the router (or the PC itself when the PC hosts the hotspot). */
    data class Local(val network: Network, val ip: String, val prefix: Int, val gateway: String?, val wifi: Boolean)

    fun toLong(a: Inet4Address): Long {
        val b = a.address
        return ((b[0].toLong() and 255L) shl 24) or ((b[1].toLong() and 255L) shl 16) or
            ((b[2].toLong() and 255L) shl 8) or (b[3].toLong() and 255L)
    }

    fun parse(ip: String?): Long? {
        val parts = (ip ?: return null).trim().split('.')
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) {
            val o = p.toIntOrNull() ?: return null
            if (o < 0 || o > 255) return null
            v = (v shl 8) or o.toLong()
        }
        return v
    }

    fun format(v: Long): String =
        "" + ((v shr 24) and 255L) + "." + ((v shr 16) and 255L) + "." + ((v shr 8) and 255L) + "." + (v and 255L)

    fun sameSubnet(a: Long, b: Long, prefix: Int): Boolean {
        val p = prefix.coerceIn(1, 30)
        val mask = (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
        return (a and mask) == (b and mask)
    }

    /** "192.168.43.0/24" for 192.168.43.17 with prefix 24. */
    fun subnetKey(ip: String, prefix: Int): String? {
        val v = parse(ip) ?: return null
        val p = prefix.coerceIn(1, 30)
        val mask = (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
        return format(v and mask) + "/" + p
    }

    /** Every local (non-mobile) IPv4 network. Cheap enough to call every few seconds. */
    @Suppress("DEPRECATION")
    fun locals(ctx: Context): List<Local> {
        val out = ArrayList<Local>()
        try {
            val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return out
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val local = wifi || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
                if (!local) continue
                val lp = cm.getLinkProperties(n) ?: continue
                var gw: String? = null
                for (r in lp.routes) {
                    val g = r.gateway
                    if (r.isDefaultRoute && g is Inet4Address && !g.isAnyLocalAddress) { gw = g.hostAddress; break }
                }
                for (la in lp.linkAddresses) {
                    val a = la.address
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                        val ip = a.hostAddress ?: continue
                        out.add(Local(n, ip, la.prefixLength, gw, wifi))
                    }
                }
            }
        } catch (e: Exception) { }
        return out
    }

    /** Changes whenever the tablet joins / leaves / re-addresses a local network. */
    fun signature(ctx: Context): String =
        locals(ctx).map { it.network.networkHandle.toString() + ":" + it.ip + "/" + it.prefix }.sorted().joinToString("|")

    /** The local network that contains [hostIp] (that is where the PC must be), else the first Wi-Fi / Ethernet one. */
    fun networkFor(ctx: Context, hostIp: String?): Network? {
        val all = locals(ctx)
        val h = parse(hostIp)
        if (h != null) {
            for (l in all) {
                val a = parse(l.ip) ?: continue
                if (sameSubnet(a, h, l.prefix)) return l.network
            }
        }
        return (all.firstOrNull { it.wifi } ?: all.firstOrNull())?.network
    }
}

/** OkHttp clients whose sockets are tied to the local network (never mobile data). */
object NetUtil {
    fun client(ctx: Context, hostOrIp: String?, connectMs: Long = 3000, readMs: Long = 0, pingSec: Long = 0): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(readMs, TimeUnit.MILLISECONDS)
            .writeTimeout(30_000, TimeUnit.MILLISECONDS)
        if (pingSec > 0) b.pingInterval(pingSec, TimeUnit.SECONDS)
        try {
            val n = NetInfo.networkFor(ctx, hostOrIp)
            if (n != null) {
                b.socketFactory(n.socketFactory)
                b.dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> = n.getAllByName(hostname).toList()
                })
            }
        } catch (e: Exception) { }
        return b.build()
    }
}

/** "This subnet -> the PC address that worked there": switching back to a known hotspot reconnects at once. */
object NetMemory {
    private const val KEY = "netmem"
    private const val MAX = 16

    private fun load(ctx: Context): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        for (line in (Prefs.of(ctx).getString(KEY, "") ?: "").split('\n')) {
            val i = line.indexOf('=')
            if (i > 0) m[line.substring(0, i)] = line.substring(i + 1)
        }
        return m
    }

    /** The PC at [ip] answered: remember it for every local network that contains it. */
    fun remember(ctx: Context, ip: String) {
        val h = NetInfo.parse(ip) ?: return
        val m = load(ctx)
        var changed = false
        for (l in NetInfo.locals(ctx)) {
            val a = NetInfo.parse(l.ip) ?: continue
            if (!NetInfo.sameSubnet(a, h, l.prefix)) continue
            val k = NetInfo.subnetKey(l.ip, l.prefix) ?: continue
            if (m[k] != ip) { m.remove(k); m[k] = ip; changed = true }
        }
        if (!changed) return
        while (m.size > MAX) m.remove(m.keys.first())
        Prefs.of(ctx).edit().putString(KEY, m.entries.joinToString("\n") { it.key + "=" + it.value }).apply()
    }

    /** PC addresses remembered for the networks the tablet is on right now (newest first). */
    fun recall(ctx: Context): List<String> {
        val m = load(ctx)
        val out = ArrayList<String>()
        for (l in NetInfo.locals(ctx)) {
            val k = NetInfo.subnetKey(l.ip, l.prefix) ?: continue
            val ip = m[k] ?: continue
            if (ip != l.ip && ip !in out) out.add(ip)
        }
        return out
    }
}
