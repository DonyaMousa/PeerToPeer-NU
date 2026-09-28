package com.example.peertopeer.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.peertopeer.MainActivity
import com.example.peertopeer.R
import com.example.peertopeer.diagnostics.DiagnosticLogger

object NotificationHelper {
    const val SERVICE_CHANNEL = "peer2peer_service"
    private const val MESSAGE_CHANNEL = "peer2peer_messages"
    const val SERVICE_NOTIFICATION_ID = 1001

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    SERVICE_CHANNEL,
                    "Peer2Peer background service",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    MESSAGE_CHANNEL,
                    "Peer2Peer messages",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    fun serviceNotification(context: Context) = NotificationCompat.Builder(context, SERVICE_CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("Peer2Peer NU active")
        .setContentText("BLE discovery, routing and relaying are running in the background.")
        .setOngoing(true)
        .setContentIntent(mainPendingIntent(context))
        .build()

    fun showMessage(context: Context, peerName: String, text: String) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            DiagnosticLogger.info("NOTIFICATION", "Message received; notification permission is not granted")
            return
        }

        runCatching {
            ensureChannels(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val id = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
            val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(peerName)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(mainPendingIntent(context))
                .build()
            nm.notify(id, notification)
        }.onFailure {
            DiagnosticLogger.warning("NOTIFICATION", "Incoming message notification could not be shown")
        }
    }

    private fun mainPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(context, 0, intent, flags)
    }
}
