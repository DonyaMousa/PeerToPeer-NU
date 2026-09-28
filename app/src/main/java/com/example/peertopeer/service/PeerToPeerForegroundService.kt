package com.example.peertopeer.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.peertopeer.diagnostics.DiagnosticLogger

class PeerToPeerForegroundService : Service() {
    private var receiverRegistered = false
    private val bluetoothReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)) {
                android.bluetooth.BluetoothAdapter.STATE_OFF -> PeerToPeerRuntime.controller(context).stopBle()
                android.bluetooth.BluetoothAdapter.STATE_ON -> PeerToPeerRuntime.controller(context).startBle()
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        com.example.peertopeer.diagnostics.CrashReporter.install(applicationContext)
        if (!com.example.peertopeer.bluetooth.BlePermissions.hasRequiredBlePermissions(this)) { stopSelf(); return }
        androidx.core.content.ContextCompat.registerReceiver(this, bluetoothReceiver,
            android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
        NotificationHelper.ensureChannels(this)
        try { startForeground(NotificationHelper.SERVICE_NOTIFICATION_ID, NotificationHelper.serviceNotification(this)) }
        catch (e: Exception) { DiagnosticLogger.error("SERVICE", "Startup failed: ${e.javaClass.simpleName}"); stopSelf(); return }
        DiagnosticLogger.info("SERVICE", "Foreground service created")
        PeerToPeerRuntime.controller(this).startBle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!com.example.peertopeer.bluetooth.BlePermissions.hasRequiredBlePermissions(this)) { stopSelf(); return START_NOT_STICKY }
        PeerToPeerRuntime.controller(this).startBle()
        return START_STICKY
    }

    override fun onDestroy() {
        DiagnosticLogger.info("SERVICE", "Foreground service destroyed")
        if (receiverRegistered) { unregisterReceiver(bluetoothReceiver); receiverRegistered = false }
        PeerToPeerRuntime.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, PeerToPeerForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(context, intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PeerToPeerForegroundService::class.java))
        }
    }
}
