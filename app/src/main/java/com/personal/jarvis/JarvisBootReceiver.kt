package com.personal.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log

class JarvisBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        Log.d(TAG, "Received $action; showing Jarvis start notification")
        showStartNotification(context)
    }

    private fun showStartNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jarvis startup",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "부팅 후 Jarvis 시작 알림"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val startIntent = Intent(context, JarvisVoiceService::class.java)
        val startPendingIntent = PendingIntent.getForegroundService(
            context,
            REQUEST_START_SERVICE,
            startIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val openAppIntent = Intent(context, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Jarvis 대기 준비됨")
            .setContentText("탭하면 소유자 목소리 확인을 시작합니다.")
            .setContentIntent(startPendingIntent)
            .setAutoCancel(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_stat_jarvis),
                    "Jarvis 시작",
                    startPendingIntent,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_stat_jarvis),
                    "앱 열기",
                    openAppPendingIntent,
                ).build(),
            )
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "JarvisBootReceiver"
        private const val CHANNEL_ID = "jarvis_startup"
        private const val NOTIFICATION_ID = 2002
        private const val REQUEST_START_SERVICE = 3001
        private const val REQUEST_OPEN_APP = 3002
    }
}
