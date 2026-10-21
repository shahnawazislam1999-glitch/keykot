package com.phonedeck.app

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Files you drag from the TABLET to the PC (the opposite of [FileReceiver]).
 *
 *  1. The PC mouse presses on a file in a file manager: [capture] looks at the accessibility tree under the pointer, reads the
 *     file name(s) shown there (and the other selected items) and finds the real files on shared storage.
 *  2. The pointer is pushed to the screen edge that leads back to the PC while the button is held: [begin] tells the PC
 *     ("tdrag"), uploads the files to the PC's file port at once (/tupload), and the pointer returns to the PC.
 *  3. The PC drops them where you let go of the button (Explorer folder under the pointer, else Downloads\PhoneDeck)
 *     and answers "tsaved"; [onSaved] shows it.
 *
 * Needs "All files access" (Android 11+) to find the files by name. Folders are sent with their content.
 *
 * v2.10: the lookup ignores PhoneDeck's own pointer overlay (it used to hide the file manager underneath), tries every window under
 * the pointer, copes with truncated / combined names, and every step writes a line to [Diag] (shown in the PC mouse settings).
 * The always-working alternative is Share > PhoneDeck (see [PcSend] / [SendToPcActivity]).
 */
object FileSender {
    private const val CH = "pd_transfer"
    private const val NOTE = 80
    private const val MAX_FILES = 3000

    class Entry(val file: File, val rel: String)
    class Cand(val top: List<String>, val entries: List<Entry>)

    private class Send(val id: Int, val total: Int, val bytes: Long) {
        val finished = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val sent = AtomicLong(0)
        @Volatile var lastNote = 0L
        @Volatile var err = ""
    }

    private val ui = Handler(Looper.getMainLooper())
    private val findPool = Executors.newSingleThreadExecutor()
    private val upPool = Executors.newFixedThreadPool(2)

    @Volatile private var token = 0
    @Volatile var capturing = false
        private set
    @Volatile var cand: Cand? = null
        private set
    @Volatile private var lastHint = 0L

    fun hasAccess(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    fun enabled(ctx: Context): Boolean = Prefs.of(ctx).getBoolean("tabletdrag", true) && hasAccess()

    // ------------------------------------------------------------------ 1. which file is under the pointer?
    /** Call when the left button goes down (main thread). The result shows up in [cand] a moment later. */
    fun capture(svc: AccessibilityService, x: Int, y: Int) {
        val t = ++token
        cand = null
        capturing = true
        findPool.execute {
            var r: Cand? = null
            val tr = StringBuilder()
            try { r = findAt(svc, x, y, t, tr) } catch (e: Throwable) { tr.append(" error: ").append(e.javaClass.simpleName) }
            if (t == token) {
                cand = r
                capturing = false
                if (r != null) {
                    Diag.grab("grabbed " + r.top.take(3).joinToString(", ") + (if (r.top.size > 3) " +" + (r.top.size - 3) else "") +
                        " (" + r.entries.size + " file(s)) - push the pointer to the PC edge")
                } else {
                    Diag.grab("no file under the pointer:" + tr.toString().take(300))
                }
            }
        }
    }

    fun clear() { token++; cand = null; capturing = false }

    /** The user is pushing something towards the PC edge but nothing is being carried: say why (at most every 20 s). */
    fun hintNothingCarried(ctx: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastHint < 20_000L) return
        if (!Prefs.of(ctx).getBoolean("tabletdrag", true)) return
        lastHint = now
        val msg = if (!hasAccess()) "To send files to the PC: allow \"All files access\" in PhoneDeck > PC mouse settings"
        else "No file found under the pointer. Use Share > PhoneDeck (Send to PC) for this one"
        toast(ctx.applicationContext, msg)
    }

    private fun findAt(svc: AccessibilityService, x: Int, y: Int, t: Int, tr: StringBuilder): Cand? {
        val own = svc.packageName
        val roots = ArrayList<AccessibilityNodeInfo>()
        var seen = 0
        try {
            val ws = svc.windows.sortedByDescending { it.layer }
            for (w in ws) {
                seen++
                // PhoneDeck's own pointer is an accessibility overlay on top of everything: it must not hide the app underneath
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                    w.type == AccessibilityWindowInfo.TYPE_SYSTEM) continue
                val r = Rect(); w.getBoundsInScreen(r)
                if (!r.contains(x, y)) continue
                val root = w.root ?: continue
                if (root.packageName?.toString() == own) continue
                roots.add(root)
            }
        } catch (e: Exception) { }
        if (roots.isEmpty()) {
            val a = svc.rootInActiveWindow
            if (a != null && a.packageName?.toString() != own) roots.add(a)
        }
        tr.append(" windows=").append(seen).append(" tried=").append(roots.size)
        var first = true
        for (root in roots) {
            if (t != token) return null
            val chain = hit(root, x, y, intArrayOf(4000), t)
            if (chain == null) { tr.append(" [").append(root.packageName).append(": nothing at the point]"); continue }
            val c = fromChain(svc, chain, t, first, tr)
            if (c != null) return c
            first = false
        }
        return null
    }

    /** Path from the window root down to the deepest visible node that contains the point. */
    private fun hit(n: AccessibilityNodeInfo, x: Int, y: Int, budget: IntArray, t: Int): ArrayList<AccessibilityNodeInfo>? {
        if (budget[0]-- <= 0 || t != token) return null
        val r = Rect(); n.getBoundsInScreen(r)
        if (!r.contains(x, y) || !n.isVisibleToUser) return null
        for (i in n.childCount - 1 downTo 0) {
            val c = n.getChild(i) ?: continue
            val sub = hit(c, x, y, budget, t)
            if (sub != null) { sub.add(0, n); return sub }
        }
        return arrayListOf(n)
    }

    private fun texts(n: AccessibilityNodeInfo, out: MutableList<String>, budget: IntArray): Boolean {
        if (budget[0]-- <= 0) return false
        n.text?.toString()?.trim()?.let { if (it.isNotEmpty() && it !in out) out.add(it) }
        n.contentDescription?.toString()?.trim()?.let { if (it.isNotEmpty() && it !in out) out.add(it) }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (!texts(c, out, budget)) return false
        }
        return true
    }

    private fun isSel(n: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (n.isSelected || (n.isCheckable && n.isChecked)) return true
        if (depth >= 2) return false
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (isSel(c, depth + 1)) return true
        }
        return false
    }

    private fun nameLike(s: String) = s.length in 1..255 && !s.contains('\n') && !s.contains('/')

    private fun isEllipsized(s: String) = s.endsWith("…") || s.endsWith("...")

    private fun stripEllipsis(s: String) = s.removeSuffix("…").removeSuffix("...").trimEnd()

    /**
     * Everything on an item that might be its file name. Some file managers put "name, size, date" into one description,
     * so the text is also cut at the usual separators.
     */
    private fun candidates(tx: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (s0 in tx) {
            val s = s0.trim()
            if (nameLike(s)) out.add(s)
            for (part in s.split("\n", ", ", " | ", " • ", " · ", "，", "、")) {
                val p = part.trim()
                if (p != s && p.length >= 2 && nameLike(p)) out.add(p)
            }
            if (out.size >= 40) break
        }
        return out.toList()
    }

    private fun fromChain(svc: AccessibilityService, chain: List<AccessibilityNodeInfo>, t: Int, allowWalk: Boolean, tr: StringBuilder): Cand? {
        val ctx = svc.applicationContext
        val cache = HashMap<String, File?>()
        val seenNames = LinkedHashSet<String>()
        val walkNames = LinkedHashSet<String>()
        var levels = 0
        var named = 0
        var i = chain.size - 1
        while (i >= 0 && levels < 8) {
            if (t != token) return null
            val a = chain[i]
            val tx = ArrayList<String>()
            if (!texts(a, tx, intArrayOf(40))) break            // too big: that is a list, not one item
            val names = candidates(tx)
            if (names.isNotEmpty()) {
                seenNames.addAll(names)
                if (named < 2) walkNames.addAll(names)           // the first two levels that show text: used for the folder search
                named++
                var f: File? = null
                for (n in names) { f = cache.getOrPut(n) { Resolver.fast(ctx, n) }; if (f != null) break }
                if (f == null) for (n in names) {
                    if (!isEllipsized(n)) continue
                    f = Resolver.prefix(ctx, stripEllipsis(n)); if (f != null) break
                }
                if (f != null) return build(ctx, chain, i, a, f, cache, t)
            }
            levels++
            i--
        }
        if (seenNames.isNotEmpty()) tr.append(" names=").append(seenNames.take(6))
        if (!allowWalk || walkNames.isEmpty()) return null
        val f = Resolver.walk(ctx, walkNames, t) ?: return null
        return build(ctx, chain, -1, null, f, cache, t)
    }

    /** [item] found at chain index [idx] and resolved to [first]; adds the other selected siblings when the item is selected. */
    private fun build(ctx: Context, chain: List<AccessibilityNodeInfo>, idx: Int, item: AccessibilityNodeInfo?,
                      first: File, cache: HashMap<String, File?>, t: Int): Cand? {
        val files = LinkedHashSet<File>()
        files.add(first)
        if (item != null && idx > 0 && isSel(item)) {
            val parent = chain[idx - 1]
            for (k in 0 until parent.childCount) {
                if (t != token) return null
                val sib = parent.getChild(k) ?: continue
                if (sib == item || files.size >= 100 || !isSel(sib)) continue
                val tx = ArrayList<String>()
                if (!texts(sib, tx, intArrayOf(40))) continue
                for (n in candidates(tx)) {
                    val f = cache.getOrPut(n) { Resolver.fast(ctx, n) }
                    if (f != null) { files.add(f); break }
                }
            }
        }
        val entries = ArrayList<Entry>()
        val top = ArrayList<String>()
        for (f in files) {
            top.add(f.name)
            if (f.isDirectory) expand(f, f.name, entries) else entries.add(Entry(f, f.name))
            if (entries.size >= MAX_FILES) break
        }
        if (entries.isEmpty()) return null
        return Cand(top, entries)
    }

    private fun expand(dir: File, rel: String, out: MutableList<Entry>) {
        val kids = try { dir.listFiles() } catch (e: Exception) { null } ?: return
        for (k in kids) {
            if (out.size >= MAX_FILES) return
            if (k.isDirectory) expand(k, rel + "/" + k.name, out) else if (k.isFile) out.add(Entry(k, rel + "/" + k.name))
        }
    }

    /** Looks up a file or folder by its display name. */
    private object Resolver {
        private fun query(ctx: Context, selection: String, args: Array<String>): File? {
            try {
                @Suppress("DEPRECATION")
                val data = MediaStore.MediaColumns.DATA
                ctx.contentResolver.query(
                    MediaStore.Files.getContentUri("external"),
                    arrayOf(data), selection, args,
                    MediaStore.MediaColumns.DATE_MODIFIED + " DESC")?.use { c ->
                    while (c.moveToNext()) {
                        val p = c.getString(0) ?: continue
                        val f = File(p)
                        if (f.exists()) return f
                    }
                }
            } catch (e: Exception) { }
            return null
        }

        fun fast(ctx: Context, name: String): File? {
            if (!FileSender.hasAccess()) return null
            return query(ctx, MediaStore.MediaColumns.DISPLAY_NAME + "=?", arrayOf(name))
        }

        /** A name the file manager cut short ("holiday_pho…"): the newest file that starts with it. */
        fun prefix(ctx: Context, start: String): File? {
            if (!FileSender.hasAccess() || start.length < 3) return null
            val esc = start.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            return query(ctx, MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? ESCAPE '\\'", arrayOf("$esc%"))
        }

        /** Breadth-first search of shared storage (shallow first, at most ~1.5 s) for a file or folder with one of [names]. */
        fun walk(ctx: Context, names: Set<String>, t: Int): File? {
            if (!FileSender.hasAccess()) return null
            val exact = names.filter { !FileSender.isEllipsized(it) }.toSet()
            val starts = names.filter { FileSender.isEllipsized(it) }.map { FileSender.stripEllipsis(it) }.filter { it.length >= 3 }
            val end = SystemClock.elapsedRealtime() + 1500
            val queue = ArrayDeque<Pair<File, Int>>()
            queue.add(Pair(Environment.getExternalStorageDirectory(), 0))
            var seen = 0
            var best: File? = null
            while (queue.isNotEmpty()) {
                if (t != FileSender.token || SystemClock.elapsedRealtime() > end || seen > 80_000) break
                val (d, depth) = queue.removeFirst()
                val kids = try { d.listFiles() } catch (e: Exception) { null } ?: continue
                for (k in kids) {
                    seen++
                    val hit = k.name in exact || starts.any { k.name.startsWith(it) }
                    if (hit && (best == null || k.lastModified() > best.lastModified())) best = k
                    if (k.isDirectory && depth < 6 && !k.name.startsWith(".") && !(depth == 0 && k.name == "Android")) queue.add(Pair(k, depth + 1))
                }
                if (best != null) return best               // shallowest folder level that has a match
            }
            return best
        }
    }

    // ------------------------------------------------------------------ 2. carry them to the PC
    /** The pointer reached the PC edge with the files. Tells the PC and starts uploading. False = could not start (nothing sent). */
    fun begin(ctx: Context, c: Cand): Boolean {
        val app = ctx.applicationContext
        val link = Prefs.of(app).getString("link", "") ?: ""
        val u = try { Uri.parse(link) } catch (e: Exception) { null }
        val host = u?.host
        if (u == null || host.isNullOrBlank()) { Diag.file("cannot send: no PC link saved"); return false }
        val port = (if (u.port > 0) u.port else 8080) + 1
        val key = u.getQueryParameter("key") ?: ""
        val id = (System.currentTimeMillis() % 2_000_000_000L).toInt()
        val msg = JSONObject().put("t", "tdrag").put("id", id).put("n", c.entries.size)
            .put("names", JSONArray(c.top.take(500)))
        if (!LinkHub.send(msg)) { Diag.file("cannot send: the link to the PC is down"); return false }
        val total = c.entries.sumOf { it.file.length() }
        val s = Send(id, c.entries.size, total)
        Diag.file("uploading " + c.entries.size + " file(s) to the PC ...")
        toast(app, "Sending " + (if (c.top.size == 1) c.top[0] else c.top.size.toString() + " items") + " to the PC…")
        progress(app, s, c, true)
        for (e in c.entries) upPool.execute { upload(app, host, port, key, s, c, e) }
        return true
    }

    private fun upload(app: Context, host: String, port: Int, key: String, s: Send, c: Cand, e: Entry) {
        var ok = false
        try {
            val url = "http://" + host + ":" + port + "/tupload?key=" + Uri.encode(key) +
                "&id=" + s.id + "&name=" + Uri.encode(e.rel)
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = e.file.length()
                override fun writeTo(sink: BufferedSink) {
                    e.file.inputStream().use { inp ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                            s.sent.addAndGet(n.toLong())
                            progress(app, s, c, false)
                        }
                    }
                }
            }
            val client = NetUtil.client(app, host, connectMs = 5000, readMs = 120_000)
            client.newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("PC answered HTTP " + r.code)
                ok = true
            }
        } catch (x: Exception) {
            if (s.err.isEmpty()) s.err = e.rel + ": " + (x.message ?: x.javaClass.simpleName)
        }
        if (!ok) s.failed.incrementAndGet()
        if (s.finished.incrementAndGet() == s.total) {
            LinkHub.send(JSONObject().put("t", "tdone").put("id", s.id).put("ok", s.failed.get() == 0))
            try { app.getSystemService(NotificationManager::class.java).cancel(NOTE) } catch (x: Exception) { }
            if (s.failed.get() > 0) {
                Diag.file(s.failed.get().toString() + " of " + s.total + " file(s) failed: " + s.err)
                toast(app, s.failed.get().toString() + " file(s) could not be sent to the PC: " + s.err)
            } else Diag.file("uploaded " + s.total + " file(s); waiting for the PC to drop them")
        }
    }

    // ------------------------------------------------------------------ 3. the PC dropped them
    fun onSaved(ctx: Context, o: JSONObject) {
        val app = ctx.applicationContext
        if (o.optBoolean("ok", false)) {
            Diag.file("PC saved " + o.optInt("n", 0) + " file(s) in " + o.optString("dir", ""))
            toast(app, "Sent to the PC: " + o.optString("dir", ""))
        } else Diag.file("the PC could not save the files")
    }

    // ------------------------------------------------------------------ helpers
    private fun progress(app: Context, s: Send, c: Cand, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - s.lastNote < 500) return
        s.lastNote = now
        try {
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH, "Files from the PC", NotificationManager.IMPORTANCE_LOW))
            val pct = if (s.bytes > 0) ((s.sent.get() * 100) / s.bytes).toInt().coerceIn(0, 100) else 0
            val n = Notification.Builder(app, CH)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Sending " + (if (c.top.size == 1) c.top[0] else c.top.size.toString() + " items") + " to the PC")
                .setContentText("$pct %")
                .setProgress(100, pct, s.bytes <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(NOTE, n)
        } catch (e: Exception) { }
    }

    private fun toast(app: Context, text: String) {
        ui.post { try { Toast.makeText(app, text, Toast.LENGTH_LONG).show() } catch (e: Exception) { } }
    }
}
