package com.personal.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class JarvisVoiceService : Service(), RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var destroyed = false
    private var lastCommand: String? = null
    private var lastCommandAt = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat()
        createRecognizer()
        scheduleListening(300)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!listening) scheduleListening(150)
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopSelf()
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
        }
    }

    private fun scheduleListening(delayMs: Long) {
        if (destroyed) return
        handler.postDelayed({ startListening() }, delayMs)
    }

    private fun startListening() {
        if (destroyed || listening || recognizer == null) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

        try {
            listening = true
            recognizer?.startListening(intent)
        } catch (_: RuntimeException) {
            listening = false
            scheduleListening(1000)
        }
    }

    private fun handleSpeech(bundle: Bundle?) {
        val results = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        for (candidate in results) {
            val command = CommandInterpreter.parse(candidate) ?: continue
            runCommand(command)
            break
        }
    }

    private fun runCommand(command: String) {
        val now = System.currentTimeMillis()
        if (lastCommand == command && now - lastCommandAt < COMMAND_COOLDOWN_MS) return
        lastCommand = command
        lastCommandAt = now

        when (command) {
            CommandBus.COMMAND_STOP_LISTENING -> stopSelf()
            CommandBus.COMMAND_OPEN_CAMERA -> CameraLauncher.open(this)
            CommandBus.COMMAND_OPEN_CAMERA_AND_TAKE_PHOTO -> {
                CameraLauncher.open(this)
                handler.postDelayed(
                    { CommandBus.send(this, CommandBus.COMMAND_TAKE_PHOTO, "voice") },
                    CAMERA_OPEN_DELAY_MS,
                )
            }
            else -> CommandBus.send(this, command, "voice")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
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
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Jarvis 실행 중")
            .setContentText("음성 명령을 기다리고 있습니다.")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() {
        listening = false
        scheduleListening(250)
    }

    override fun onError(error: Int) {
        listening = false
        val delay = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 300L
            else -> 1000L
        }
        scheduleListening(delay)
    }

    override fun onResults(results: Bundle?) {
        listening = false
        handleSpeech(results)
        scheduleListening(250)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        handleSpeech(partialResults)
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    companion object {
        private const val CHANNEL_ID = "jarvis_voice"
        private const val NOTIFICATION_ID = 2001
        private const val COMMAND_COOLDOWN_MS = 1400L
        private const val CAMERA_OPEN_DELAY_MS = 1500L
    }
}
