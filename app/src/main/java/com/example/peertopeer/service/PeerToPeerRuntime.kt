package com.example.peertopeer.service

import android.content.Context
import com.example.peertopeer.app.PeerToPeerController

object PeerToPeerRuntime {
    @Volatile private var instance: PeerToPeerController? = null

    fun controller(context: Context): PeerToPeerController = instance ?: synchronized(this) {
        instance ?: PeerToPeerController(context.applicationContext).also { instance = it }
    }

    fun shutdown() {
        // Keep the same controller object while the process is alive so an
        // open Activity never diverges from a newly restarted service.
        instance?.stopBle()
    }
}
