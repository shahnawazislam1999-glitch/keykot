package com.phonedeck.app

import android.app.Application

/**
 * Runs every time Android starts the app's process, for any reason (you opened it, a reboot, the system revived the
 * service, a network wake-up): if a PC link is saved, the background link is brought up right here, with no screen needed.
 */
class PhoneDeckApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            if (KeepAlive.wanted(this)) {
                KeepAlive.start(this)
                LinkHub.ensure(this)
            }
        } catch (e: Exception) { }
    }
}
