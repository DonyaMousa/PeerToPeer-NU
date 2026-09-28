package com.example.peertopeer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.peertopeer.diagnostics.CrashReporter
import com.example.peertopeer.diagnostics.DiagnosticLogger
import com.example.peertopeer.service.PeerToPeerRuntime
import com.example.peertopeer.ui.PeerToPeerApp
import com.example.peertopeer.ui.theme.PeerToPeerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CrashReporter.install(applicationContext)
        CrashReporter.consumeLastCrash(applicationContext)?.let { crash ->
            val headline = crash.lineSequence().firstOrNull().orEmpty().ifBlank { "Unknown crash" }
            DiagnosticLogger.error("CRASH", "Previous app crash: $headline")
        }

        val controller = PeerToPeerRuntime.controller(applicationContext)

        enableEdgeToEdge()
        setContent {
            PeerToPeerTheme {
                PeerToPeerApp(controller)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PeerToPeerRuntime.controller(applicationContext).onAppForegrounded()
    }
}
