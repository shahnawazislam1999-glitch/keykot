package com.phonedeck.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

object LinkUtil {
    private val full = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
    private val hostPort = Regex("[A-Za-z0-9.-]+:\\d{2,5}(?:[/?#][^\\s\"'<>]*)?")
    private val ip = Regex("\\d{1,3}(?:\\.\\d{1,3}){3}(?:[/?#][^\\s\"'<>]*)?")

    /** Pulls a usable http(s) link out of whatever was pasted (whole console line, missing http://, extra spaces). */
    fun parse(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        var m = full.find(s)?.value ?: hostPort.find(s)?.value ?: ip.find(s)?.value ?: return null
        m = m.trimEnd('.', ',', ';', ')', ']')
        if (!m.startsWith("http", ignoreCase = true)) m = "http://$m"
        val host = Uri.parse(m).host
        return if (host.isNullOrEmpty()) null else m
    }

    /** Port of a link (80 / 443 when the link does not name one). */
    fun portOf(url: String): Int {
        val u = Uri.parse(url)
        if (u.port != -1) return u.port
        return if (u.scheme.equals("https", ignoreCase = true)) 443 else 80
    }

    /** Same link (path, key, port) but pointing at another machine. */
    fun withHost(url: String, newHost: String): String {
        val u = Uri.parse(url)
        val port = if (u.port != -1) ":" + u.port else ""
        return u.buildUpon().encodedAuthority(newHost + port).build().toString()
    }

    /** "192.168.1.5:8080" - for lists; the secret key in the link is not shown. */
    fun pretty(url: String): String {
        val u = Uri.parse(url)
        return (u.host ?: url) + ":" + portOf(url) + (if (!u.query.isNullOrEmpty()) "   (key saved)" else "")
    }
}

object Prefs {
    fun of(ctx: Context): SharedPreferences = ctx.getSharedPreferences("phonedeck", Context.MODE_PRIVATE)
}

/** The last few links that were used, newest first. */
object LinkHistory {
    private const val KEY = "history"
    private const val MAX = 8

    fun list(ctx: Context): List<String> =
        (Prefs.of(ctx).getString(KEY, "") ?: "").split('\n').filter { it.isNotBlank() }

    fun add(ctx: Context, url: String) {
        val l = list(ctx).toMutableList()
        l.remove(url)
        l.add(0, url)
        while (l.size > MAX) l.removeAt(l.size - 1)
        Prefs.of(ctx).edit().putString(KEY, l.joinToString("\n")).apply()
    }

    fun clear(ctx: Context) {
        Prefs.of(ctx).edit().remove(KEY).apply()
    }
}
