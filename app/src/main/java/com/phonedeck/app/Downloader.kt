package com.phonedeck.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Saves files from the PC into the phone's Downloads folder with their real name.
 * We do the download ourselves (instead of Android's DownloadManager) so that
 *   - the name always comes from Names.resolve (no more "download.bin"),
 *   - zip / apk / folder downloads keep the right extension,
 *   - blob: and data: downloads made by the page work too,
 *   - progress and errors are shown inside the app.
 * Needs no storage permission (uses MediaStore on Android 10+).
 */
class Downloader private constructor(private val app: Context) {

    enum class Phase { RUNNING, DONE, FAILED, CANCELED }

    data class State(
        val id: Int, val name: String, val done: Long, val total: Long,
        val phase: Phase, val uri: Uri?, val msg: String?
    )

    interface Listener { fun onDownload(s: State) }

    @Volatile var listener: Listener? = null

    private val pool = Executors.newFixedThreadPool(2)
    private val ids = AtomicInteger(0)
    private val cancelled: MutableSet<Int> = ConcurrentHashMap.newKeySet<Int>()
    private val hints = LinkedHashMap<String, String>()
    @Volatile private var lastHint: Pair<String, Long>? = null
    private val blobs = ConcurrentHashMap<String, Blob>()
    private val resolver get() = app.contentResolver

    private class CancelledException : Exception()
    private class Blob(val id: Int, val uri: Uri, val out: OutputStream, val name: String, val total: Long) {
        var done = 0L
        var lastEmit = 0L
    }

    companion object {
        @Volatile private var inst: Downloader? = null
        fun get(ctx: Context): Downloader =
            inst ?: synchronized(this) { inst ?: Downloader(ctx.applicationContext).also { inst = it } }
    }

    private fun emit(s: State) { listener?.onDownload(s) }

    fun cancel(id: Int) { cancelled.add(id) }

    // ------------------------------------------------------------------ names from the page
    // ==== ANCHOR:DL_HINTS ====
    /** The page told us the real name of [url] (<a download="name">). */
    fun hint(url: String, name: String) {
        val n = Names.clean(name)
        if (n.isEmpty()) return
        synchronized(hints) {
            hints[url] = n
            if (hints.size > 20) {
                val it = hints.keys.iterator()
                it.next()
                it.remove()
            }
        }
        lastHint = Pair(n, SystemClock.elapsedRealtime())
    }

    private fun takeHint(url: String): String? {
        val h = synchronized(hints) { hints.remove(url) }
        if (h != null) return h
        val lh = lastHint
        if (lh != null && SystemClock.elapsedRealtime() - lh.second < 8000) {
            lastHint = null
            return lh.first
        }
        return null
    }

    // ------------------------------------------------------------------ normal http(s) / data downloads
    // ==== ANCHOR:DL_HTTP ====
    fun enqueue(url: String, ua: String?, cookie: String?, referer: String?, cd: String?, mime: String?) {
        val id = ids.incrementAndGet()
        val hint = takeHint(url)
        emit(State(id, Names.resolve(url, cd, mime, hint), 0, -1, Phase.RUNNING, null, "Starting…"))
        pool.execute { runHttp(id, url, ua, cookie, referer, cd, mime, hint) }
    }

    private fun runHttp(
        id: Int, url: String, ua: String?, cookie: String?, referer: String?,
        cd: String?, mime: String?, hint: String?
    ) {
        var attempt = 0
        var name = Names.resolve(url, cd, mime, hint)
        while (true) {
            attempt++
            var uri: Uri? = null
            var conn: HttpURLConnection? = null
            try {
                if (url.startsWith("data:")) {
                    saveDataUrl(id, url, hint)
                    return
                }
                conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.setRequestProperty("Accept", "*/*")
                conn.setRequestProperty("Accept-Encoding", "identity")
                if (!ua.isNullOrEmpty()) conn.setRequestProperty("User-Agent", ua)
                if (!cookie.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookie)
                if (!referer.isNullOrEmpty()) conn.setRequestProperty("Referer", referer)

                val code = conn.responseCode
                if (code !in 200..299) throw IOException("The PC answered HTTP $code")
                val total = conn.contentLengthLong
                val realCd = conn.getHeaderField("Content-Disposition") ?: cd
                val realMime = conn.contentType ?: mime
                var n2 = Names.resolve(url, realCd, realMime, hint)
                val finalUrl = conn.url.toString()
                if (Names.isFallbackName(n2) && finalUrl != url) n2 = Names.resolve(finalUrl, realCd, realMime, hint)

                val made = createEntry(n2)
                val target = made.first
                uri = target
                name = made.second
                emit(State(id, name, 0, total, Phase.RUNNING, null, null))

                var done = 0L
                var last = 0L
                val buf = ByteArray(64 * 1024)
                conn.inputStream.use { input ->
                    val out = resolver.openOutputStream(target) ?: throw IOException("Cannot write to Downloads")
                    out.use { o ->
                        while (true) {
                            if (cancelled.contains(id)) throw CancelledException()
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            val now = SystemClock.elapsedRealtime()
                            if (now - last > 150) {
                                last = now
                                emit(State(id, name, done, total, Phase.RUNNING, null, null))
                            }
                        }
                    }
                }
                if (total > 0 && done < total) throw IOException("Connection dropped at ${done * 100 / total}%")
                finishEntry(target)
                emit(State(id, name, done, total, Phase.DONE, target, null))
                return
            } catch (e: CancelledException) {
                deleteQuiet(uri)
                emit(State(id, name, 0, -1, Phase.CANCELED, null, null))
                return
            } catch (e: Exception) {
                deleteQuiet(uri)
                if (cancelled.contains(id)) {
                    emit(State(id, name, 0, -1, Phase.CANCELED, null, null))
                    return
                }
                if (attempt >= 3) {
                    emit(State(id, name, 0, -1, Phase.FAILED, null, e.message ?: e.javaClass.simpleName))
                    return
                }
                emit(State(id, name, 0, -1, Phase.RUNNING, null, "Retrying ($attempt)…"))
                try { Thread.sleep(1200L * attempt) } catch (ie: InterruptedException) { return }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun saveDataUrl(id: Int, url: String, hint: String?) {
        val comma = url.indexOf(',')
        if (comma < 0) throw IOException("Bad data link")
        val head = url.substring(5, comma)
        val body = url.substring(comma + 1)
        val mime = head.substringBefore(';').ifBlank { null }
        val bytes = if (head.contains(";base64", ignoreCase = true)) Base64.decode(body, Base64.DEFAULT)
        else Uri.decode(body).toByteArray(Charsets.UTF_8)
        val made = createEntry(Names.resolve(null, null, mime, hint))
        try {
            val out = resolver.openOutputStream(made.first) ?: throw IOException("Cannot write to Downloads")
            out.use { it.write(bytes) }
            finishEntry(made.first)
            emit(State(id, made.second, bytes.size.toLong(), bytes.size.toLong(), Phase.DONE, made.first, null))
        } catch (e: Exception) {
            deleteQuiet(made.first)
            throw e
        }
    }

    // ------------------------------------------------------------------ blob: downloads made by the page
    // ==== ANCHOR:DL_BLOB ====
    fun blobBegin(key: String, nameHint: String, mime: String, total: Long) {
        val id = ids.incrementAndGet()
        try {
            val made = createEntry(Names.resolve(null, null, mime, nameHint.ifBlank { null }))
            val out = resolver.openOutputStream(made.first) ?: throw IOException("Cannot write to Downloads")
            blobs[key] = Blob(id, made.first, out, made.second, total)
            emit(State(id, made.second, 0, total, Phase.RUNNING, null, null))
        } catch (e: Exception) {
            emit(State(id, nameHint.ifBlank { "file" }, 0, -1, Phase.FAILED, null, e.message ?: "Cannot create file"))
        }
    }

    /** Returns false when the page should stop sending (cancelled or failed). */
    fun blobChunk(key: String, b64: String): Boolean {
        val b = blobs[key] ?: return false
        try {
            if (cancelled.contains(b.id)) throw CancelledException()
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            b.out.write(bytes)
            b.done += bytes.size
            val now = SystemClock.elapsedRealtime()
            if (now - b.lastEmit > 150) {
                b.lastEmit = now
                emit(State(b.id, b.name, b.done, b.total, Phase.RUNNING, null, null))
            }
            return true
        } catch (e: CancelledException) {
            abortBlob(key, b, null)
        } catch (e: Exception) {
            abortBlob(key, b, e.message ?: "Write failed")
        }
        return false
    }

    fun blobEnd(key: String) {
        val b = blobs.remove(key) ?: return
        try {
            b.out.close()
            finishEntry(b.uri)
            emit(State(b.id, b.name, b.done, b.total, Phase.DONE, b.uri, null))
        } catch (e: Exception) {
            deleteQuiet(b.uri)
            emit(State(b.id, b.name, 0, -1, Phase.FAILED, null, e.message ?: "Could not finish file"))
        }
    }

    fun blobFail(key: String, why: String) {
        val b = blobs[key] ?: return
        abortBlob(key, b, why)
    }

    private fun abortBlob(key: String, b: Blob, why: String?) {
        blobs.remove(key)
        try { b.out.close() } catch (e: Exception) { }
        deleteQuiet(b.uri)
        emit(State(b.id, b.name, 0, -1, if (why == null) Phase.CANCELED else Phase.FAILED, null, why))
    }

    // ------------------------------------------------------------------ MediaStore helpers
    // ==== ANCHOR:DL_MEDIASTORE ====
    /**
     * Creates the file in Downloads. We deliberately do NOT set a MIME type: Android then derives it from the
     * file extension, so it can never "fix" the name by adding .bin or .zip behind our back.
     */
    private fun createEntry(name: String): Pair<Uri, String> {
        val v = ContentValues()
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        v.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            ?: throw IOException("Cannot create the file in Downloads")
        var shown = name
        try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) shown = c.getString(0) ?: name
            }
        } catch (e: Exception) { }
        return Pair(uri, shown)
    }

    private fun finishEntry(uri: Uri) {
        val v = ContentValues()
        v.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, v, null, null)
    }

    private fun deleteQuiet(uri: Uri?) {
        if (uri == null) return
        try { resolver.delete(uri, null, null) } catch (e: Exception) { }
    }
}
