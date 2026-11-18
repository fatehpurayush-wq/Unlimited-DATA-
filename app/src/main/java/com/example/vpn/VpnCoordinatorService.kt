package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.FreePublicVpnApp
import com.example.MainActivity
import com.example.R

class VpnCoordinatorService : Service() {

    companion object {
        const val CHANNEL_ID = "vpn_status_channel"
        const val NOTIFICATION_ID = 4091
        const val ACTION_START = "com.example.vpn.ACTION_START"
        const val ACTION_STOP = "com.example.vpn.ACTION_STOP"
        const val ACTION_DISCONNECT_FROM_NOTIFICATION = "com.example.vpn.ACTION_DISCONNECT"
        const val EXTRA_SERVER_NAME = "extra_server_name"

        fun updateNotification(
            context: Context,
            serverName: String,
            downloadRateBps: Long,
            uploadRateBps: Long
        ) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            val notification = buildNotification(
                context = context,
                serverName = serverName,
                detailText = "↓ ${formatRate(downloadRateBps)}  •  ↑ ${formatRate(uploadRateBps)}"
            )
            manager.notify(NOTIFICATION_ID, notification)
        }

        private fun buildNotification(
            context: Context,
            serverName: String,
            detailText: String
        ): Notification {
            ensureChannel(context)
            val openAppIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val disconnectIntent = PendingIntent.getService(
                context,
                1,
                Intent(context, VpnCoordinatorService::class.java).apply {
                    action = ACTION_DISCONNECT_FROM_NOTIFICATION
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_vpn_notification)
                .setContentTitle("VPN Protected • $serverName")
                .setContentText(detailText)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(openAppIntent)
                .addAction(0, "Disconnect", disconnectIntent)
                .build()
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return
                val existing = manager.getNotificationChannel(CHANNEL_ID)
                if (existing == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.vpn_notification_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = context.getString(R.string.vpn_notification_channel_desc)
                        setShowBadge(false)
                    }
                    manager.createNotificationChannel(channel)
                }
            }
        }

        private fun formatRate(bytesPerSec: Long): String {
            return when {
                bytesPerSec >= 1_048_576 -> String.format("%.1f MB/s", bytesPerSec / 1_048_576.0)
                bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024.0)
                else -> "$bytesPerSec B/s"
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_DISCONNECT_FROM_NOTIFICATION -> {
                (application as? FreePublicVpnApp)?.connectionController?.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                val serverName = intent?.getStringExtra(EXTRA_SERVER_NAME) ?: "Encrypted Tunnel"
                val notification = buildNotification(
                    context = this,
                    serverName = serverName,
                    detailText = "WireGuard encrypted tunnel active"
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        }
        return START_NOT_STICKY
    }
}
