package com.kotsukotsu.launcher

import android.app.Application

class LauncherApp : Application() {
    val prefs by lazy { LauncherPrefs(this) }
    val client by lazy { LauncherClient() }
    val installer by lazy { Installer(this) }
    val updater by lazy { Updater(this, client, installer) }

    override fun onCreate() {
        super.onCreate()
        UpdateWorker.schedule(this)
    }
}
