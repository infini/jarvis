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
import android.util.Log

class JarvisVoiceService : Service(), RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    @Volatile private var verifyingOwner = false
    private var ownerVerificationThread: Thread? = null
    private var destroyed = false
    private var lastCommand: String? = null
    private var lastCommandAt = 0L
    private var ownerAuthorizedUntil = 0L
    private val listeningTimeout = Runnable {
        if (!listening || destroyed) return@Runnable
        Log.w(TAG, "Listening timed out; restarting recognizer")
        listening = false
        runCatching { recognizer?.cancel() }
        scheduleNextCapture(300)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat()
        createRecognizer()
        scheduleNextCapture(300)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!listening && !verifyingOwner) scheduleNextCapture(150)
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        stopOwnerVerification()
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "Speech recognition is not available")
            stopSelf()
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
        }
        Log.d(TAG, "Speech recognizer created")
    }

    private fun scheduleListening(delayMs: Long) {
        if (destroyed) return
        handler.postDelayed({ startListening() }, delayMs)
    }

    private fun scheduleNextCapture(delayMs: Long) {
        if (destroyed) return
        handler.postDelayed({
            if (shouldUseOwnerGate() && !isOwnerAuthorized()) {
                startOwnerVerification()
            } else {
                startListening()
            }
        }, delayMs)
    }

    private fun startListening() {
        if (destroyed || listening || verifyingOwner || recognizer == null) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }

        try {
            listening = true
            recognizer?.startListening(intent)
            handler.removeCallbacks(listeningTimeout)
            handler.postDelayed(listeningTimeout, LISTENING_TIMEOUT_MS)
            Log.d(TAG, "Listening started")
        } catch (_: RuntimeException) {
            listening = false
            handler.removeCallbacks(listeningTimeout)
            Log.w(TAG, "Failed to start listening")
            scheduleNextCapture(1000)
        }
    }

    private fun startOwnerVerification() {
        if (destroyed || verifyingOwner || listening) return

        if (!OwnerVoiceStore.isConfigured(this)) {
            Log.w(TAG, "Owner voice embedding is not configured; falling back to speech recognition")
            scheduleListening(100)
            return
        }

        verifyingOwner = true
        ownerVerificationThread = Thread({
            try {
                val samples = OwnerVoiceEngine.recordSamples(
                    durationMs = OWNER_VERIFY_AUDIO_MS,
                    shouldContinue = {
                        verifyingOwner && !Thread.currentThread().isInterrupted
                    },
                )
                if (!verifyingOwner || Thread.currentThread().isInterrupted) return@Thread

                val match = OwnerVoiceEngine.verifyOwner(applicationContext, samples)
                handler.post {
                    if (destroyed || !verifyingOwner) return@post

                    verifyingOwner = false
                    ownerVerificationThread = null
                    if (match.accepted) {
                        Log.d(TAG, "Owner voice accepted: ${match.score}")
                        ownerAuthorizedUntil = System.currentTimeMillis() + OWNER_AUTH_WINDOW_MS
                        scheduleListening(100)
                    } else {
                        Log.d(TAG, "Owner voice rejected: ${match.score}")
                        scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    if (destroyed || !verifyingOwner) return@post

                    Log.w(TAG, "Owner voice verification failed: ${e.message}")
                    verifyingOwner = false
                    ownerVerificationThread = null
                    scheduleNextCapture(1000)
                }
            }
        }, "JarvisOwnerVerify").also { it.start() }
        Log.d(TAG, "Owner voice verification started")
    }

    private fun stopOwnerVerification() {
        verifyingOwner = false
        ownerVerificationThread?.interrupt()
        ownerVerificationThread = null
    }

    private fun shouldUseOwnerGate(): Boolean = OwnerVoiceStore.isConfigured(this)

    private fun isOwnerAuthorized(): Boolean {
        return System.currentTimeMillis() < ownerAuthorizedUntil
    }

    private fun handleSpeech(bundle: Bundle?) {
        val results = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        if (results.isNotEmpty()) Log.d(TAG, "Speech results: $results")
        for (candidate in results) {
            val command = CommandInterpreter.parse(candidate) ?: continue
            Log.d(TAG, "Parsed command: $command from '$candidate'")
            runCommand(command)
            break
        }
    }

    private fun runCommand(command: String) {
        val now = System.currentTimeMillis()
        if (lastCommand == command && now - lastCommandAt < COMMAND_COOLDOWN_MS) {
            Log.d(TAG, "Ignored duplicate command: $command")
            return
        }
        lastCommand = command
        lastCommandAt = now
        Log.d(TAG, "Running command: $command")

        when (command) {
            CommandBus.COMMAND_STOP_LISTENING -> stopSelf()
            CommandBus.COMMAND_OPEN_CAMERA -> {
                CameraLauncher.open(this)
                CommandBus.send(this, command, "voice")
            }
            CommandBus.COMMAND_OPEN_FRONT_CAMERA -> {
                CameraLauncher.openFront(this)
                CommandBus.send(this, command, "voice")
            }
            CommandBus.COMMAND_OPEN_CAMERA_AND_TAKE_PHOTO -> {
                CameraLauncher.open(this)
                CommandBus.send(this, CommandBus.COMMAND_OPEN_CAMERA, "voice")
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
            .setContentText("소유자 목소리 확인 후 음성 명령을 듣습니다.")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onReadyForSpeech(params: Bundle?) {
        Log.d(TAG, "Ready for speech")
    }

    override fun onBeginningOfSpeech() {
        Log.d(TAG, "Beginning of speech")
    }

    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() {
        listening = false
        handler.removeCallbacks(listeningTimeout)
        Log.d(TAG, "End of speech")
        scheduleNextCapture(250)
    }

    override fun onError(error: Int) {
        listening = false
        handler.removeCallbacks(listeningTimeout)
        Log.w(TAG, "Speech error: $error")
        val delay = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 300L
            else -> 1000L
        }
        scheduleNextCapture(delay)
    }

    override fun onResults(results: Bundle?) {
        listening = false
        handler.removeCallbacks(listeningTimeout)
        Log.d(TAG, "Final speech results received")
        handleSpeech(results)
        ownerAuthorizedUntil = 0L
        scheduleNextCapture(250)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        Log.d(TAG, "Partial speech results received")
        val results = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
        if (results.isNotEmpty()) Log.d(TAG, "Partial speech results: $results")
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    companion object {
        private const val TAG = "JarvisVoiceService"
        private const val CHANNEL_ID = "jarvis_voice"
        private const val NOTIFICATION_ID = 2001
        private const val COMMAND_COOLDOWN_MS = 1400L
        private const val CAMERA_OPEN_DELAY_MS = 1500L
        private const val LISTENING_TIMEOUT_MS = 7000L
        private const val OWNER_AUTH_WINDOW_MS = 12000L
        private const val OWNER_VERIFY_AUDIO_MS = 2500L
        private const val OWNER_VERIFY_RETRY_MS = 700L
    }
}
