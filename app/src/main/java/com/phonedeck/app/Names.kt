package com.phonedeck.app

import android.net.Uri
import android.webkit.MimeTypeMap
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Works out the real file name of a download so it is saved exactly as it is called on the PC
 * (never "download.bin"). Looks in this order:
 *   1. the name the page gave (<a download="name.zip">)
 *   2. the Content-Disposition header
 *   3. query parameters of the link (?path=C:\Docs\Folder, ?name=..., ?file=...)
 *   4. the last part of the link path
 * and then makes sure the name has the right extension (a folder becomes Folder.zip).
 */
object Names {
    // ==== ANCHOR:NAMES_CONFIG ====
    private val GENERIC = setOf("download", "dl", "get", "file", "files", "f", "d", "zip", "fetch", "raw", "api", "index", "")
    private val FLAG = Regex("(?i)1|0|true|false|yes|no")
    private val BAD = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
    private val KEYS = listOf(
        "filename", "fileName", "fname", "name", "file", "download", "dl", "f", "path", "p", "files", "paths", "items"
    )

    fun clean(raw: String): String {
        var n = raw.trimEnd('/', '\\')
        n = n.substringAfterLast('/').substringAfterLast('\\')
        n = BAD.replace(n, "_").trim().trim('.').trim()
        if (n.length > 150) {
            val dot = n.lastIndexOf('.')
            n = if (dot in 1..10) n.take(150 - (n.length - dot)) + n.substring(dot) else n.take(150)
        }
        return n
    }

    /** Some servers send UTF-8 bytes that arrive read as Latin-1 ("CafÃ©"); put them back. */
    private fun fixLatin1(s: String): String {
        if (s.all { it.code < 0x80 }) return s
        if (s.any { it.code > 0xFF }) return s
        val dec = String(s.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        return if (dec.contains('\uFFFD')) s else dec
    }

    fun fromDisposition(cd: String?): String? {
        if (cd.isNullOrBlank()) return null
        Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let {
            val cs = it.groupValues[1].ifBlank { "UTF-8" }
            try {
                return clean(URLDecoder.decode(it.groupValues[2].trim().trim('"'), cs))
            } catch (e: Exception) { /* try the plain form */ }
        }
        Regex("filename\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", RegexOption.IGNORE_CASE).find(cd)?.let {
            return clean(fixLatin1(it.groupValues[1].replace("\\\"", "\"")))
        }
        Regex("filename\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let {
            return clean(fixLatin1(it.groupValues[1].trim().trim('"')))
        }
        return null
    }

    fun normMime(m: String?): String? =
        m?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    fun isZipMime(m: String?): Boolean {
        val n = normMime(m) ?: return false
        return n == "application/zip" || n == "application/x-zip-compressed" || n == "application/x-zip" ||
            n == "multipart/x-zip"
    }

    /** Extension for a MIME type, or null when it says nothing useful (octet-stream). */
    fun extFor(mime: String?): String? {
        val m = normMime(mime) ?: return null
        if (isZipMime(m)) return "zip"
        if (m == "application/vnd.android.package-archive") return "apk"
        if (m == "application/octet-stream" || m == "binary/octet-stream" || m == "application/force-download") return null
        val e = MimeTypeMap.getSingleton().getExtensionFromMimeType(m)
        return if (e == null || e == "bin") null else e
    }

    fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return (if (ext.isNotEmpty()) MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) else null)
            ?: "application/octet-stream"
    }

    private fun fromUrl(url: String): String? {
        val u = try { Uri.parse(url) } catch (e: Exception) { return null }
        for (k in KEYS) {
            val vals = (try { u.getQueryParameters(k) } catch (e: Exception) { emptyList<String>() })
                .filter { it.isNotBlank() && !FLAG.matches(it.trim()) }
            if (vals.isEmpty()) continue
            if (vals.size > 1) return "PhoneDeck-files.zip"
            val n = clean(vals[0])
            if (n.isNotEmpty()) return n
        }
        val seg = clean(Uri.decode(u.lastPathSegment ?: ""))
        return if (seg.lowercase() in GENERIC) null else seg
    }

    private fun withExt(name: String, mime: String?): String {
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0) name.substring(dot + 1).lowercase() else ""
        val known = ext.isNotEmpty() && ext.length <= 8 && MimeTypeMap.getSingleton().hasExtension(ext)
        if (known) return name
        val add = extFor(mime) ?: return name
        // no extension at all -> add it; a dot that is not a real extension ("My.Folder") -> only for zips
        return if (dot <= 0 || isZipMime(mime)) "$name.$add" else name
    }

    fun resolve(url: String?, cd: String?, mime: String?, hint: String?): String {
        var name: String? = null
        if (!hint.isNullOrBlank()) name = clean(hint).ifEmpty { null }
        if (name == null) name = fromDisposition(cd)?.ifEmpty { null }
        if (name == null && !url.isNullOrEmpty() && !url.startsWith("blob:") && !url.startsWith("data:")) name = fromUrl(url)
        if (name == null) {
            name = "PhoneDeck-" + SimpleDateFormat("yyMMdd-HHmmss", Locale.US).format(Date())
        }
        return withExt(name, mime)
    }

    fun isFallbackName(n: String) = n.startsWith("PhoneDeck-")
}
