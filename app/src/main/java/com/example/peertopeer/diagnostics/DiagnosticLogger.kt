package com.example.peertopeer.diagnostics

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf

/**
 * UI-visible diagnostic log.
 *
 * Bluetooth callbacks frequently arrive on Binder / Bluetooth threads. Compose
 * SnapshotStateList must not be mutated concurrently from those callbacks, so
 * all visible log mutations are serialized onto the main thread.
 */
object DiagnosticLogger {
    private const val MAX_EVENTS = 500
    private val mainHandler = Handler(Looper.getMainLooper())

    val events = mutableStateListOf<DiagnosticEvent>()

    fun log(level: DiagnosticLevel, component: String, message: String) {
        val action = {
            events.add(0, DiagnosticEvent(level = level, component = component, message = message))
            while (events.size > MAX_EVENTS) events.removeAt(events.lastIndex)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    fun info(component: String, message: String) = log(DiagnosticLevel.INFO, component, message)
    fun success(component: String, message: String) = log(DiagnosticLevel.SUCCESS, component, message)
    fun warning(component: String, message: String) = log(DiagnosticLevel.WARNING, component, message)
    fun error(component: String, message: String) = log(DiagnosticLevel.ERROR, component, message)

    fun clear() {
        val action = { events.clear() }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }
}
