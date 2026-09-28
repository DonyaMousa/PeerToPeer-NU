package com.example.peertopeer.diagnostics

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter

/** Minimal local crash breadcrumb for device testing. No network upload. */
object CrashReporter {
    private const val PREFS = "peer2peer_crash"
    private const val KEY = "last_crash"
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val appContext = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY, sw.toString().take(12_000))
                        .commit()
                }
                previous?.uncaughtException(thread, throwable)
            }
            installed = true
        }
    }

    fun consumeLastCrash(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY, null)
        // Retain the full trace until a later crash replaces it.
        return value
    }
}
