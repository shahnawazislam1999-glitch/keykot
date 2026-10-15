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
        if (!m.startsWith("http", ignoreCase = true)) m = "http://$m"
        val host = Uri.parse(m).host
        return if (host.isNullOrEmpty()) null else m
    }
}

object Prefs {
    fun of(ctx: Context): SharedPreferences = ctx.getSharedPreferences("phonedeck", Context.MODE_PRIVATE)
}
