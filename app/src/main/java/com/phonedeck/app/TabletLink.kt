package com.phonedeck.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Keeps a WebSocket open to the PC server (ws://host:port/tablet?key=...) and reconnects aggressively:
 *  - retry after 0.5 s, 1 s, 1.5 s, then every 2 s, forever;
 *  - [tick] (called every ~2 s by the keep-alive service) pings the PC and drops a link that went silent for 7 s;
 *  - [kick] reconnects at once (network changed, PC moved, ...).
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
    private var lastRx = 0L
    private var lastAttempt = 0L
    private val retryRun = Runnable { open() }

    fun start() {
        if (running) return
        running = true
        failures = 0
        open()
    }

    fun stop() {
        running = false
        conn++
        ui.removeCallbacks(retryRun)
        try { ws?.cancel() } catch (e: Exception) { }
        ws = null
        setState(false)
    }

    /** Drop whatever we have and reconnect right now. */
    fun kick() {
        if (!running) return
        conn++
        ui.removeCallbacks(retryRun)
        try { ws?.cancel() } catch (e: Exception) { }
        ws = null
        setState(false)
        failures = 0
        open()
    }

    /** Called about every 2 s: heartbeat while connected, safety net while not. */
    fun tick() {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        if (connected) {
            send(JSONObject().put("t", "ping").put("ts", now))
            if (now - lastRx > 7000L) kick()
        } else if (now - lastAttempt > 6000L) {
            kick()
        }
    }

    fun send(o: JSONObject): Boolean = try { ws?.send(o.toString()) ?: false } catch (e: Exception) { false }

    /** Milliseconds since the last thing arrived from the PC (a big number while not connected). */
    fun silenceMs(): Long = if (lastRx == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - lastRx

    private fun setState(c: Boolean) {
        if (connected != c) { connected = c; onState(c) }
    }

    private fun retry() {
        if (!running) return
        failures++
        val wait = (500L * failures).coerceAtMost(2000L)
        ui.removeCallbacks(retryRun)
        ui.postDelayed(retryRun, wait)
    }

    private fun client(host: String?): OkHttpClient = NetUtil.client(ctx, host, connectMs = 3000, readMs = 0, pingSec = 5)

    private fun open() {
        if (!running) return
        lastAttempt = SystemClock.elapsedRealtime()
        val link = Prefs.of(ctx).getString("link", "") ?: ""
        val u = try { Uri.parse(link) } catch (e: Exception) { null }
        val host = u?.host
        if (u == null || host.isNullOrBlank()) { retry(); return }
        val port = if (u.port > 0) u.port else 8080
        val key = u.getQueryParameter("key") ?: ""
        val url = "http://$host:$port/tablet?key=" + Uri.encode(key)
        val req = try {
            Request.Builder().url(url).header("Origin", "http://$host:$port").build()
        } catch (e: Exception) { retry(); return }
        val my = ++conn
        try { ws?.cancel() } catch (e: Exception) { }
        ws = client(host).newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ui.post {
                    if (my == conn && running) {
                        failures = 0
                        lastRx = SystemClock.elapsedRealtime()
                        setState(true)
                    }
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = try { JSONObject(text) } catch (e: Exception) { return }
                ui.post {
                    if (my == conn && running) {
                        lastRx = SystemClock.elapsedRealtime()
                        onMsg(o)
                    }
                }
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
