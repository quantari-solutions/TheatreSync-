package com.theatresync.app

import android.app.Application

class TheatreSyncApplication : Application() {
    /** Created lazily on the main thread by the first screen that needs it. */
    val session: MovieSession by lazy { MovieSession(this) }
}
