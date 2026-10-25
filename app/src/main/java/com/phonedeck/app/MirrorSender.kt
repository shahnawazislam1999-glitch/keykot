package com.phonedeck.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * "Mirror typing": sends what is typed on the tablet to the PC through the PC's normal keyboard socket (ws://host:port/ws?key=...,
 * the same one the phone page uses) with the existing {"t":"type"} message. The PC side needs NO update.
 * The socket is opened when the first text arrives and closed again when the switch is off or the link is gone.
 */
object MirrorSender {
    private val ui = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var isOpen = false
    private var gen = 0
    private var lastTry = 0L
    private var queuedAt = 0L
    private val queue = ArrayList<String>()

    fun type(ctx: Context, bs: Int, ins: String) {
        val a = ctx.applicationContext
        val msg = JSONObject().put("t", "type").put("bs", bs.coerceIn(0, 2000)).put("ins", ins.take(20000))
            .put("method", "unicode").toString()
        ui.post {
            if (isOpen && (ws?.send(msg) == true)) return@post
            if (queue.isEmpty()) queuedAt = SystemClock.elapsedRealtime()
            if (queue.size < 300) queue.add(msg)
            connect(a)
        }
    }

    /** About every 2 s from [LinkHub.heartbeat]: keeps the socket from idling out, closes it when not wanted. */
    fun tick(ctx: Context) {
        val a = ctx.applicationContext
        ui.post {
            val wanted = Prefs.of(a).getBoolean("mirror_typing", false) && LinkHub.connected
            if (!wanted) { close(); return@post }
            if (isOpen) ws?.send("{\"t\":\"ping\",\"ts\":" + SystemClock.elapsedRealtime() + "}")
        }
    }

    fun stop() { ui.post { close() } }

    private fun close() {
        gen++
        try { ws?.cancel() } catch (e: Exception) { }
        ws = null; isOpen = false; queue.clear()
    }

    private fun connect(a: Context) {
        if (ws != null) return                                   // already connecting
        val now = SystemClock.elapsedRealtime()
        if (now - lastTry < 1500L) { queue.clear(); return }     // do not hammer a PC that is not answering
        lastTry = now
        val tg = PcSend.target(a) ?: run { queue.clear(); return }
        val my = ++gen
        val req = try {
            Request.Builder().url("http://" + tg.host + ":" + tg.port + "/ws?key=" + Uri.encode(tg.key))
                .header("Origin", "http://" + tg.host + ":" + tg.port).build()
        } catch (e: Exception) { queue.clear(); return }
        ws = NetUtil.client(a, tg.host, connectMs = 3000, readMs = 0, pingSec = 5).newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ui.post {
                    if (my != gen) return@post
                    isOpen = true
                    val fresh = SystemClock.elapsedRealtime() - queuedAt < 5000L      // never type old text much later
                    if (fresh) for (m in queue) webSocket.send(m)
                    queue.clear()
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { lost(my) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { lost(my) }
        })
    }

    private fun lost(my: Int) {
        ui.post { if (my == gen) { ws = null; isOpen = false; queue.clear() } }
    }
}
