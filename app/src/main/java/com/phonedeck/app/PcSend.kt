package com.phonedeck.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Tablet -> PC transfers that do NOT depend on the pointer, the accessibility service or any guessing:
 *  - [text]: puts text on the PC clipboard (over the live link, else one HTTP request to the PC's file port);
 *  - [upload]: sends files (content:// links from a Share sheet / file picker) to the PC's shared folder (Downloads\PhoneDeck).
 * Used by "Share > PhoneDeck: Send to PC", the file picker and "Send clipboard" in the PC mouse settings, and as the fallback when
 * the live link is down.
 */
object PcSend {
    private val io = Executors.newCachedThreadPool()
    private val ui = Handler(Looper.getMainLooper())
    private const val CH = "pd_transfer"
    private const val NOTE = 81

    class Target(val host: String, val port: Int, val fport: Int, val key: String)

    /** Where the saved link points (UI port, file port = UI port + 1, secret key); null when no link is saved. */
    fun target(ctx: Context): Target? {
        val link = Prefs.of(ctx.applicationContext).getString("link", "") ?: ""
        val u = try { Uri.parse(link) } catch (e: Exception) { return null }
        val host = u.host
        if (host.isNullOrBlank()) return null
        val port = if (u.port > 0) u.port else 8080
        return Target(host, port, port + 1, u.getQueryParameter("key") ?: "")
    }

    // ------------------------------------------------------------------ text -> PC clipboard
    /** [done] (main thread): ok, and where it went / why it failed. */
    fun text(ctx: Context, text: String, done: ((Boolean, String) -> Unit)? = null) {
        val app = ctx.applicationContext
        val t = text.take(100_000)
        if (t.isEmpty()) { done?.let { d -> ui.post { d(false, "nothing to send") } }; return }
        if (LinkHub.connected && LinkHub.send(JSONObject().put("t", "clip").put("text", t))) {
            Diag.clip("sent " + t.length + " characters over the live link")
            done?.let { d -> ui.post { d(true, "live link") } }
            return
        }
        val tg = target(app)
        if (tg == null) {
            Diag.clip("no PC link saved: connect once with Paste & Connect")
            done?.let { d -> ui.post { d(false, "no PC link saved") } }
            return
        }
        io.execute {
            var ok = false
            var why = ""
            try {
                val url = "http://" + tg.host + ":" + tg.fport + "/tclip?key=" + Uri.encode(tg.key)
                val body = t.toRequestBody("text/plain; charset=utf-8".toMediaType())
                NetUtil.client(app, tg.host, connectMs = 4000, readMs = 15_000)
                    .newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
                        ok = r.isSuccessful
                        if (!ok) why = "PC answered HTTP " + r.code +
                            (if (r.code == 403 || r.code == 404) " (copy the new server.py and tabletlink.py to the PC and restart it)" else "")
                    }
            } catch (e: Exception) {
                why = e.message ?: e.javaClass.simpleName
            }
            Diag.clip(if (ok) "sent " + t.length + " characters over HTTP" else "could not reach the PC: $why")
            done?.let { d -> ui.post { d(ok, if (ok) "HTTP" else why) } }
        }
    }

    // ------------------------------------------------------------------ files -> PC folder
    interface Listener {
        /** Main thread. */
        fun onProgress(filesDone: Int, filesTotal: Int, sent: Long, bytes: Long)
        /** Main thread, once at the end. */
        fun onFinished(ok: Int, failed: Int, firstError: String)
    }

    private class Item(val uri: Uri, val name: String, val size: Long)

    private fun describe(app: Context, uri: Uri, index: Int): Item {
        var name: String? = null
        var size = -1L
        try {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (e: Exception) { }
        if (name.isNullOrBlank()) name = uri.lastPathSegment?.substringAfterLast('/')
        if (name.isNullOrBlank()) name = "file_" + (index + 1)
        if (uri.scheme == "file") {
            try { val f = File(uri.path ?: ""); if (f.isFile) size = f.length() } catch (e: Exception) { }
        }
        return Item(uri, name!!, size)
    }

    /** Sends the files one after the other. Safe to call from the main thread; [listener] is called on the main thread. */
    fun upload(ctx: Context, uris: List<Uri>, listener: Listener?) {
        val app = ctx.applicationContext
        val tg = target(app)
        if (tg == null) {
            Diag.file("share: no PC link saved")
            ui.post { listener?.onFinished(0, uris.size, "No PC link saved: connect once with Paste & Connect") }
            return
        }
        io.execute {
            val items = uris.mapIndexed { i, u -> describe(app, u, i) }
            val total = items.size
            val bytes = items.sumOf { if (it.size > 0) it.size else 0L }
            val sent = AtomicLong(0)
            var okCount = 0
            var failed = 0
            var firstErr = ""
            var lastNote = 0L
            fun note(done: Int, force: Boolean) {
                val now = SystemClock.elapsedRealtime()
                if (!force && now - lastNote < 400) return
                lastNote = now
                val s = sent.get()
                ui.post { listener?.onProgress(done, total, s, bytes) }
                notify(app, done, total, s, bytes)
            }
            note(0, true)
            for ((idx, item) in items.withIndex()) {
                var spooled: File? = null
                try {
                    var size = item.size
                    if (size <= 0L) {
                        // unknown / zero size: copy to a temporary file first so the PC gets a real Content-Length
                        val f = File(app.cacheDir, "pdsend_" + System.nanoTime() + ".tmp")
                        spooled = f
                        val inp0 = app.contentResolver.openInputStream(item.uri) ?: throw IOException("cannot open " + item.name)
                        inp0.use { src -> f.outputStream().use { out -> src.copyTo(out) } }
                        size = f.length()
                    }
                    val len = size
                    val spoolFile: File? = spooled
                    val body = object : RequestBody() {
                        override fun contentType() = "application/octet-stream".toMediaType()
                        override fun contentLength() = len
                        override fun writeTo(sink: BufferedSink) {
                            val inp = (if (spoolFile != null) spoolFile.inputStream() else app.contentResolver.openInputStream(item.uri))
                                ?: throw IOException("cannot open " + item.name)
                            inp.use { s ->
                                val buf = ByteArray(256 * 1024)
                                var left = len
                                while (left > 0) {
                                    val n = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                    if (n < 0) throw IOException("file ended early: " + item.name)
                                    sink.write(buf, 0, n)
                                    left -= n
                                    sent.addAndGet(n.toLong())
                                    note(idx, false)
                                }
                            }
                        }
                    }
                    // /upload checks that the request looks like it comes from the PhoneDeck page of the same PC
                    val url = "http://" + tg.host + ":" + tg.fport + "/upload?key=" + Uri.encode(tg.key) + "&name=" + Uri.encode(item.name)
                    val req = Request.Builder().url(url).header("Origin", "http://" + tg.host + ":" + tg.port).post(body).build()
                    NetUtil.client(app, tg.host, connectMs = 5000, readMs = 180_000).newCall(req).execute().use { r ->
                        if (!r.isSuccessful) throw IOException("PC answered HTTP " + r.code)
                    }
                    okCount++
                } catch (e: Exception) {
                    failed++
                    if (firstErr.isEmpty()) firstErr = item.name + ": " + (e.message ?: e.javaClass.simpleName)
                } finally {
                    try { spooled?.delete() } catch (e: Exception) { }
                }
                note(idx + 1, true)
            }
            try { app.getSystemService(NotificationManager::class.java).cancel(NOTE) } catch (e: Exception) { }
            Diag.file(if (failed == 0) "share: sent $okCount file(s) to the PC folder" else "share: $okCount sent, $failed failed ($firstErr)")
            ui.post { listener?.onFinished(okCount, failed, firstErr) }
        }
    }

    private fun notify(app: Context, done: Int, total: Int, sent: Long, bytes: Long) {
        try {
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH, "Files to / from the PC", NotificationManager.IMPORTANCE_LOW))
            val pct = if (bytes > 0) ((sent * 100) / bytes).toInt().coerceIn(0, 100) else 0
            val n = Notification.Builder(app, CH)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Sending to the PC (" + minOf(done + 1, total) + " of " + total + ")")
                .setContentText("$pct %")
                .setProgress(100, pct, bytes <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(NOTE, n)
        } catch (e: Exception) { }
    }
}
