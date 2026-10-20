package com.phonedeck.app

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.Toast
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Files you drag from the PC onto the tablet.
 *
 *  PC  "drag"    -> the files were grabbed and the pointer reached the tablet: start downloading right away (into the app's
 *                   private cache, so nothing half-finished ever shows up in Downloads);
 *  PC  "drop"    -> the mouse button was released here: as soon as everything has arrived it is moved to Downloads/PhoneDeck
 *                   and a notification (tap = open) tells you;
 *  PC  "dragend" -> the pointer went back to the PC without letting go: everything is thrown away.
 *
 * Works without the accessibility service too (the PC then sends "drag" with auto=true = keep the files at once).
 * The download goes over the local network (never mobile data) from the PC's file port, only for the files that
 * were really offered by the drag.
 */
object FileReceiver {
    private const val CH = "pd_transfer"
    private const val NOTE_PROGRESS = 78
    private const val NOTE_DONE = 79

    class Item(val name: String, val size: Long, val dir: Boolean) {
        @Volatile var done = 0L
        @Volatile var total = size
        /** 0 waiting, 1 downloading, 2 finished, 3 failed */
        @Volatile var state = 0
        @Volatile var file: File? = null
        @Volatile var error: String? = null
    }

    private class Session(
        val id: Int, val host: String, val port: Int, val key: String,
        val items: List<Item>, val dir: File
    ) {
        @Volatile var dropped = false
        @Volatile var cancelled = false
        @Volatile var saved = false
        @Volatile var lastNote = 0L
    }

    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2)
    @Volatile private var cur: Session? = null

    /** Is the "drag files from the PC" feature switched on? (default: yes) */
    fun enabled(ctx: Context): Boolean = Prefs.of(ctx).getBoolean("filedrag", true)

    /** Label for the little chip that follows the pointer while the files are being carried. */
    fun label(): String? {
        val s = cur ?: return null
        if (s.cancelled || s.saved) return null
        return if (s.items.size == 1) "📄 " + s.items[0].name else "📄 " + s.items.size + " files"
    }

    val active: Boolean get() = cur.let { it != null && !it.cancelled && !it.saved }

    // ------------------------------------------------------------------ messages from the PC
    fun onDrag(ctx: Context, o: JSONObject) {
        val app = ctx.applicationContext
        if (!enabled(app)) return
        cancelCurrent(app, "replaced")
        val arr = o.optJSONArray("files") ?: return
        val items = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val n = safeName(f.optString("n", "file"))
            items.add(Item(n, f.optLong("s", 0L), f.optBoolean("d", false)))
        }
        if (items.isEmpty()) return
        val link = Prefs.of(app).getString("link", "") ?: ""
        val u = Uri.parse(link)
        val host = u.host
        if (host.isNullOrBlank()) return
        val linkPort = if (u.port > 0) u.port else 8080
        val fp = o.optInt("fp", 0).let { if (it in 1..65535) it else linkPort + 1 }
        val key = u.getQueryParameter("key") ?: ""
        val id = o.optInt("id", 0)
        val dir = File(app.cacheDir, "drop/" + id + "_" + System.currentTimeMillis())
        try { dir.mkdirs() } catch (e: Exception) { }
        val s = Session(id, host, fp, key, items, dir)
        s.dropped = o.optBoolean("auto", false)
        cur = s
        for ((idx, it) in items.withIndex()) pool.execute { download(app, s, it, idx) }
        postProgress(app, s, true)
        if (!s.dropped) RemoteInputService.instance?.showDragChip(label())
        else toast(app, "Receiving " + describe(s) + " from the PC…")
    }

    fun onDrop(ctx: Context) {
        val app = ctx.applicationContext
        val s = cur ?: return
        s.dropped = true
        RemoteInputService.instance?.showDragChip(null)
        maybeFinish(app, s)
    }

    fun onDragEnd(ctx: Context) {
        val s = cur ?: return
        if (!s.dropped) cancelCurrent(ctx.applicationContext, "cancelled")
    }

    /** The link to the PC dropped while files were still being carried (not yet released): forget them. */
    fun onLinkLost(ctx: Context) {
        val s = cur ?: return
        if (!s.dropped) cancelCurrent(ctx.applicationContext, "link lost")
    }

    private fun cancelCurrent(app: Context, why: String) {
        val s = cur ?: return
        if (s.dropped && !s.cancelled && !s.saved) return          // released already: let it finish
        s.cancelled = true
        cur = null
        try { s.dir.deleteRecursively() } catch (e: Exception) { }
        try { app.getSystemService(NotificationManager::class.java).cancel(NOTE_PROGRESS) } catch (e: Exception) { }
        ui.post { RemoteInputService.instance?.showDragChip(null) }
    }

    // ------------------------------------------------------------------ download
    private fun download(app: Context, s: Session, it: Item, idx: Int) {
        if (s.cancelled) return
        it.state = 1
        val part = File(s.dir, idx.toString() + "_" + it.name + ".part")
        try {
            val url = "http://" + s.host + ":" + s.port + "/tfile?key=" + Uri.encode(s.key) + "&id=" + s.id + "&i=" + idx
            val client = NetUtil.client(app, s.host, connectMs = 5000, readMs = 30_000)
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("The PC answered HTTP " + resp.code)
                val body = resp.body ?: throw IOException("empty answer")
                val len = body.contentLength()
                if (len > 0) it.total = len
                body.byteStream().use { inp ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            if (s.cancelled) throw IOException("cancelled")
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            it.done += n
                            postProgress(app, s, false)
                        }
                    }
                }
            }
            val fin = File(s.dir, idx.toString() + "_" + it.name)
            if (!part.renameTo(fin)) throw IOException("could not finish the file")
            it.file = fin
            it.state = 2
        } catch (e: Exception) {
            it.state = 3
            it.error = e.message ?: e.javaClass.simpleName
            try { part.delete() } catch (x: Exception) { }
        }
        maybeFinish(app, s)
    }

    @Synchronized
    private fun maybeFinish(app: Context, s: Session) {
        if (s.cancelled || s.saved || !s.dropped) return
        if (s.items.any { it.state == 0 || it.state == 1 }) return
        s.saved = true
        val saved = ArrayList<Pair<String, Uri>>()
        var failed = 0
        for (it in s.items) {
            val f = it.file
            if (it.state == 2 && f != null) {
                val uri = try { saveToDownloads(app, f, it.name) } catch (e: Exception) { null }
                if (uri != null) saved.add(Pair(it.name, uri)) else failed++
            } else failed++
        }
        try { s.dir.deleteRecursively() } catch (e: Exception) { }
        if (cur === s) cur = null
        try { app.getSystemService(NotificationManager::class.java).cancel(NOTE_PROGRESS) } catch (e: Exception) { }
        ui.post { RemoteInputService.instance?.showDragChip(null) }
        postResult(app, saved, failed, s.items.firstOrNull { it.error != null }?.error)
    }

    // ------------------------------------------------------------------ saving
    private fun saveToDownloads(app: Context, src: File, name: String): Uri? {
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, Names.mimeFor(name))
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/PhoneDeck")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val r = app.contentResolver
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
        try {
            r.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out, 256 * 1024) } }
                ?: throw IOException("cannot write")
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            r.update(uri, done, null, null)
            return uri
        } catch (e: Exception) {
            try { r.delete(uri, null, null) } catch (x: Exception) { }
            return null
        }
    }

    private fun safeName(raw: String): String {
        var n = Names.clean(raw)
        n = n.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.')
        if (n.length > 120) {
            val dot = n.lastIndexOf('.')
            val ext = if (dot > 0 && n.length - dot <= 12) n.substring(dot) else ""
            n = n.substring(0, 120 - ext.length) + ext
        }
        return if (n.isEmpty()) "file" else n
    }

    // ------------------------------------------------------------------ notifications
    private fun channel(app: Context) {
        try {
            app.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CH, "Files from the PC", NotificationManager.IMPORTANCE_LOW))
        } catch (e: Exception) { }
    }

    private fun describe(s: Session): String =
        if (s.items.size == 1) s.items[0].name else s.items.size.toString() + " files"

    private fun postProgress(app: Context, s: Session, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - s.lastNote < 400) return
        s.lastNote = now
        try {
            channel(app)
            val total = s.items.sumOf { if (it.total > 0) it.total else 0L }
            val done = s.items.sumOf { it.done }
            val known = total > 0 && s.items.none { it.total <= 0 }
            val pct = if (known) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
            val n = Notification.Builder(app, CH)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Receiving " + describe(s) + " from the PC")
                .setContentText(if (known) "$pct %" else mb(done) + " received")
                .setProgress(100, pct, !known)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            app.getSystemService(NotificationManager::class.java).notify(NOTE_PROGRESS, n)
        } catch (e: Exception) { }
    }

    private fun mb(b: Long): String = String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)

    private fun postResult(app: Context, saved: List<Pair<String, Uri>>, failed: Int, err: String?) {
        val text = when {
            saved.isEmpty() -> "Could not receive the file" + (if (err != null) ": $err" else "")
            saved.size == 1 && failed == 0 -> saved[0].first + "  (Downloads/PhoneDeck)"
            else -> saved.size.toString() + " files in Downloads/PhoneDeck" + (if (failed > 0) ", $failed failed" else "")
        }
        toast(app, if (saved.isEmpty()) text else "Received: $text")
        try {
            channel(app)
            val open: Intent = if (saved.size == 1) {
                Intent(Intent.ACTION_VIEW).setDataAndType(saved[0].second, Names.mimeFor(saved[0].first))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            } else {
                Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pi = PendingIntent.getActivity(app, 5, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(app, CH)
                .setSmallIcon(if (saved.isEmpty()) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done)
                .setContentTitle(if (saved.isEmpty()) "PC file transfer failed" else "Received from the PC")
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            app.getSystemService(NotificationManager::class.java).notify(NOTE_DONE, n)
        } catch (e: Exception) { }
    }

    private fun toast(app: Context, text: String) {
        ui.post { try { Toast.makeText(app, text, Toast.LENGTH_LONG).show() } catch (e: Exception) { } }
    }
}
