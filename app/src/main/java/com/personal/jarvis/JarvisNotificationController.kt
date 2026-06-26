package com.personal.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.content.pm.ServiceInfo
import android.os.Build

class JarvisNotificationController(
    private val service: Service,
    private val defaultText: String,
) {
    private var contentText = defaultText

    fun createChannel() {
        val manager = service.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Jarvis voice control",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Jarvis 음성 명령을 듣는 서비스"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun startForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            service.startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            service.startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun reset() {
        update(defaultText)
    }

    fun update(newContentText: String) {
        if (contentText == newContentText) return

        contentText = newContentText
        val manager = service.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(service, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            service,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Jarvis 실행 중")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(service, R.drawable.ic_stat_jarvis),
                    "Jarvis 종료",
                    stopPendingIntent(),
                ).build(),
            )
            .build()
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(service, JarvisVoiceService::class.java)
            .setAction(JarvisVoiceService.ACTION_STOP_SERVICE)
        return PendingIntent.getService(
            service,
            REQUEST_STOP_SERVICE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_voice"
        private const val NOTIFICATION_ID = 2001
        private const val REQUEST_STOP_SERVICE = 4001
    }
}
