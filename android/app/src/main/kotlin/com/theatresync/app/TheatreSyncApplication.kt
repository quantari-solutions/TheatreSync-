package com.theatresync.app

import android.app.Application

class TheatreSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        // Anything recorded last time (including a crash) goes to GitHub now, in the background.
        GitHubLink.uploadPending(this)
    }

    /** Created lazily on the main thread by the first screen that needs it. */
    val session: MovieSession by lazy { MovieSession(this) }
}
