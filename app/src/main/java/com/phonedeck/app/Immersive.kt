package com.phonedeck.app

import android.app.Activity
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Always-fullscreen setup: draws behind the bars/cutout, keeps the screen on, pads only for camera cutout + keyboard. */
fun Activity.setupFullscreen(root: View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.setPadding(cut.left, cut.top, cut.right, maxOf(cut.bottom, ime.bottom))
        WindowInsetsCompat.CONSUMED
    }
    hideSystemBars()
}

fun Activity.hideSystemBars() {
    val c = WindowCompat.getInsetsController(window, window.decorView)
    c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    c.hide(WindowInsetsCompat.Type.systemBars())
}
