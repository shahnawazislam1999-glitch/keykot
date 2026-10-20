package com.phonedeck.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Keeps a WebSocket open to the PC server (ws://host:port/tablet?key=...), reconnecting by itself.
 * The PC pushes mouse / keyboard / clipboard events through it; see PhoneDeck/tabletlink.py.
 * All callbacks run on [ui] (the main thread).
 */
class TabletLink(
    private val ctx: Context,
    private val ui: Handler,
    private val onMsg: (JSONObject) -> Unit,
    private val onState: (Boolean) -> Unit
) {
    @Volatile private var running = false
    @Volatile private var ws: WebSocket? = null
    @Volatile var connected = false
        private set
    private var conn = 0
    private var failures = 0

    fun start() {
        if (running) return
        running = true
        failures = 0
        open()
    }

    fun stop() {
        running = false
        conn++
        try { ws?.close(1000, null) } catch (e: Exception) { }
        ws = null
        setState(false)
    }

    fun send(o: JSONObject) { try { ws?.send(o.toString()) } catch (e: Exception) { } }

    private fun setState(c: Boolean) {
        if (connected != c) { connected = c; onState(c) }
    }

    private fun retry() {
        if (!running) return
        failures++
        val wait = (1000L * failures).coerceAtMost(5000L)
        ui.postDelayed({ open() }, wait)
    }

    private fun client(): OkHttpClient {
        val b = OkHttpClient.Builder()
            .pingInterval(8, TimeUnit.SECONDS)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val c = cm.getNetworkCapabilities(n) ?: continue
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    b.socketFactory(n.socketFactory)
                    b.dns(object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> = n.getAllByName(hostname).toList()
                    })
                    break
                }
            }
        } catch (e: Exception) { }
        return b.build()
    }

    private fun open() {
        if (!running) return
        val link = Prefs.of(ctx).getString("link", "") ?: ""
        val u = try { Uri.parse(link) } catch (e: Exception) { null }
        val host = u?.host
        if (u == null || host.isNullOrBlank()) { failures = 3; retry(); return }
        val port = if (u.port > 0) u.port else 8080
        val key = u.getQueryParameter("key") ?: ""
        val url = "http://$host:$port/tablet?key=" + Uri.encode(key)
        val req = try {
            Request.Builder().url(url).header("Origin", "http://$host:$port").build()
        } catch (e: Exception) { retry(); return }
        val my = ++conn
        ws = client().newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ui.post { if (my == conn && running) { failures = 0; setState(true) } }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = try { JSONObject(text) } catch (e: Exception) { return }
                ui.post { if (my == conn && running) onMsg(o) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { lost(my) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { lost(my) }
        })
    }

    private fun lost(my: Int) {
        ui.post {
            if (my == conn && running) {
                ws = null
                setState(false)
                retry()
            }
        }
    }
}
