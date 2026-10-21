package com.phonedeck.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-line "what happened last" notes for the Settings screen (PC mouse on this tablet), so that a failed file drag or clipboard
 * transfer explains itself instead of failing silently.
 */
object Diag {
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile var lastClip: String = "nothing yet"
        private set
    @Volatile var lastFile: String = "nothing yet"
        private set
    @Volatile var lastGrab: String = "nothing yet"
        private set

    private fun now(): String = synchronized(fmt) { fmt.format(Date()) }

    fun clip(msg: String) { lastClip = now() + "  " + msg }

    fun file(msg: String) { lastFile = now() + "  " + msg }

    /** What the last mouse press on the tablet found under the pointer (a file, or why not). */
    fun grab(msg: String) { lastGrab = now() + "  " + msg }
}
