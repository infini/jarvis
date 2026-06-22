package com.personal.jarvis

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.File
import org.json.JSONObject

class JarvisVoiceService : Service(), RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private val commandExecutor by lazy {
        JarvisCommandExecutor(
            context = this,
            handler = handler,
        )
    }
    private val notificationController by lazy {
        JarvisNotificationController(this, DEFAULT_NOTIFICATION_TEXT)
    }
    private val ownerVoiceGate by lazy {
        OwnerVoiceGate(
            context = applicationContext,
            handler = handler,
            onAuthorized = { match ->
                if (!destroyed) {
                    startLatencyTrace(
                        "owner_authorized",
                        "activationWindowMs=$COMMAND_SESSION_AUTH_WINDOW_MS acceptance=${match.acceptance} " +
                            "speechMs=${match.activeSpeechMs} score=${match.score} " +
                            "ownerElapsedMs=${match.verificationElapsedMs} " +
                            "ownerAttempts=${match.verificationAttempts} " +
                            "profileEmbeddings=${match.ownerEmbeddingCount} " +
                            "peakRms=${match.peakRms} reason=${match.rejectReason ?: "none"}",
                    )
                    commandFeedbackEnabled = false
                    if (!startOwnerAudioActivationRecognition(match)) {
                        finishLatency("activation_asr_unavailable")
                        scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
                    }
                }
            },
            onMissingProfile = {
                if (!destroyed) scheduleListening(100)
            },
            onVerificationError = {
                if (!destroyed) scheduleNextCapture(1000)
            },
        )
    }
    private val localCommandSession by lazy {
        LocalCommandSession(applicationContext, handler)
    }
    private val localActivationSession by lazy {
        LocalActivationSession(applicationContext, handler)
    }
    private val feedbackController by lazy {
        JarvisFeedbackController(applicationContext, handler)
    }
    private val idleWakeAudioBuffer by lazy {
        IdleWakeAudioBuffer(IDLE_WAKE_AUDIO_BUFFER_MS)
    }
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var destroyed = false
    private var idleAndroidWakeListening = false
    private var currentListeningAllowsCommandWithoutWake = false
    private var partialCommandHandled = false
    private var partialCommandKeepsWindowOpen = false
    private var partialActivationHandled = false
    private var commandFeedbackEnabled = false
    private var commandReadyFeedbackPending = false
    private var forceLocalCommandOnce = false
    private var forceAndroidCommandOnce = false
    private var androidListenAfterLocal = false
    private var commandWindowDeadlineAt = 0L
    private var commandWindowSpeechGraceUntil = 0L
    private var speechStartedInCurrentListen = false
    private var idleAndroidWakeConsecutiveStartErrors = 0
    private var idleAndroidWakeDisabledUntil = 0L
    private var latencyTrace: JarvisLatencyTrace? = null
    private var suppressCancelledRecognizerCallbacks = false
    @Volatile private var ownerAudioActivationActive = false
    private var ownerAudioActivationThread: Thread? = null
    @Volatile private var activationOwnerVerificationActive = false
    private var activationOwnerVerificationThread: Thread? = null
    @Volatile private var androidActivationReplayActive = false
    private var androidActivationReplayThread: Thread? = null
    private val startListeningRunnable = Runnable {
        startListening()
    }
    private val nextCaptureRunnable = Runnable {
        if (destroyed) return@Runnable

        when {
            commandWindowDeadlineAt <= 0L -> {
                stopAfterCommandWindow("no_command_window")
            }
            isCommandWindowExpired() -> {
                closeCommandWindow(playFeedback = false)
                stopAfterCommandWindow("command_window_expired_before_next_capture")
            }
            isCommandWindowOpen() -> {
                notificationController.reset()
                startListening()
            }
            else -> {
                stopAfterCommandWindow("command_window_not_authorized")
            }
        }
    }
    private val listeningTimeout: Runnable = Runnable {
        if (!listening || destroyed) return@Runnable
        markLatency("listen_timeout", "commandWindow=$currentListeningAllowsCommandWithoutWake")
        Log.w(TAG, "Listening timed out; restarting recognizer")
        val wasListeningForCommand = currentListeningAllowsCommandWithoutWake
        val wasIdleAndroidWake = idleAndroidWakeListening
        if (wasListeningForCommand && speechStartedInCurrentListen) {
            markLatency("listen_timeout_waiting_for_speech")
            handler.postDelayed(listeningTimeout, ACTIVE_SPEECH_DEADLINE_RECHECK_MS)
            return@Runnable
        }
        if (wasListeningForCommand && isCommandWindowExpired()) {
            stopActiveCommandRecognitionForWindowClose()
            closeCommandWindow(playFeedback = false)
            finishLatency("command_window_expired_on_listen_timeout")
            stopAfterCommandWindow("listen_timeout_expired")
            return@Runnable
        }
        listening = false
        idleAndroidWakeListening = false
        currentListeningAllowsCommandWithoutWake = false
        androidListenAfterLocal = false
        speechStartedInCurrentListen = false
        if (wasIdleAndroidWake) {
            val snapshot = idleWakeAudioBuffer.stopAndSnapshot()
            markLatency(
                "android_activation_timeout",
                "source=${snapshot.source} samplesMs=${snapshot.durationMs} " +
                    "peakRms=${snapshot.peakRms} meanRms=${snapshot.meanRms}",
            )
        }
        if (localCommandSession.isActive) {
            localCommandSession.stop()
        } else {
            suppressCancelledRecognizerCallbacks = true
            runCatching { recognizer?.cancel() }
        }
        if (wasListeningForCommand && shouldUseOwnerGate()) {
            extendCommandWindowWithinDeadline(COMMAND_RETRY_GRACE_MS)
        }
        scheduleNextCapture(if (wasListeningForCommand) COMMAND_RETRY_DELAY_MS else DEFAULT_RETRY_DELAY_MS)
    }
    private val commandWindowTimeout: Runnable = Runnable {
        if (destroyed || commandWindowDeadlineAt == 0L) return@Runnable

        val remainingMs = remainingCommandWindowMs()
        if (remainingMs > 0L) {
            handler.postDelayed(commandWindowTimeout, remainingMs)
            return@Runnable
        }

        if (listening && speechStartedInCurrentListen) {
            val now = System.currentTimeMillis()
            if (commandWindowSpeechGraceUntil == 0L) {
                commandWindowSpeechGraceUntil = now + ACTIVE_SPEECH_DEADLINE_GRACE_MS
            }

            val graceRemainingMs = commandWindowSpeechGraceUntil - now
            if (graceRemainingMs > 0L) {
                Log.d(TAG, "Command window deadline reached while speech is active; waiting briefly")
                markLatency(
                    "command_window_deadline_waiting_for_speech",
                    "graceRemainingMs=$graceRemainingMs",
                )
                handler.postDelayed(
                    commandWindowTimeout,
                    graceRemainingMs.coerceAtMost(ACTIVE_SPEECH_DEADLINE_RECHECK_MS),
                )
                return@Runnable
            }

            Log.d(TAG, "Command window speech grace expired; returning to owner gate")
            markLatency("command_window_deadline_speech_grace_expired")
            stopActiveCommandRecognitionForWindowClose()
            closeCommandWindow(playFeedback = false)
            finishLatency("command_window_expired_after_speech_grace")
            stopAfterCommandWindow("speech_grace_expired")
            return@Runnable
        }

        Log.d(TAG, "Command window deadline reached; stopping active Jarvis session")
        finishLatency("command_window_timeout")
        stopActiveCommandRecognitionForWindowClose()
        closeCommandWindow(playFeedback = false)
        stopAfterCommandWindow("command_window_timeout")
    }
    private val partialCommandFinalize: Runnable = Runnable {
        if (destroyed || !partialCommandHandled) return@Runnable
        markLatency("partial_finalize_timeout")
        Log.d(TAG, "Finalizing partial command without recognizer callback")
        listening = false
        speechStartedInCurrentListen = false
        handler.removeCallbacks(listeningTimeout)
        resetRecognizer()
        completePartialCommandRun("partial finalize timeout")
    }

    override fun onCreate() {
        super.onCreate()
        if (!hasConfiguredOwnerProfile("onCreate")) {
            isRunning = false
            stopSelf()
            return
        }

        isRunning = true
        Log.d(TAG, "JarvisVoiceService created")
        notificationController.createChannel()
        notificationController.startForeground()
        createRecognizer()
        LocalCommandRecognizer.warmUp(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            Log.d(TAG, "JarvisVoiceService stop requested from notification")
            stopSelf()
            return START_NOT_STICKY
        }

        val source = intent?.getStringExtra(JarvisVoiceServiceStarter.EXTRA_START_SOURCE).orEmpty()
        Log.d(TAG, "JarvisVoiceService start command: source=$source flags=$flags startId=$startId")
        if (!hasConfiguredOwnerProfile("onStartCommand source=$source")) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (handleCommandWindowIntent(intent, source)) return START_NOT_STICKY
        if (handleDebugCommandWindowIntent(intent)) return START_NOT_STICKY
        Log.d(TAG, "JarvisVoiceService started without command window; staying passive")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "JarvisVoiceService destroyed")
        destroyed = true
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        ownerVoiceGate.stop()
        stopOwnerAudioActivationRecognition()
        stopActivationOwnerVerification()
        stopAndroidActivationReplay()
        idleWakeAudioBuffer.stop()
        localActivationSession.stop()
        localCommandSession.stop()
        recognizer?.destroy()
        recognizer = null
        JarvisStateBus.send(applicationContext, JarvisVoiceState.IDLE)
        feedbackController.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasConfiguredOwnerProfile(reason: String): Boolean {
        if (OwnerVoiceStore.isConfigured(applicationContext)) return true

        Log.w(
            TAG,
            "Stopping JarvisVoiceService: owner voice profile is not configured; " +
                "reason=$reason embeddings=${OwnerVoiceStore.embeddingCount(applicationContext)} " +
                "phraseId=${OwnerVoiceStore.enrollmentPhraseId(applicationContext) ?: "unknown"}",
        )
        return false
    }

    private fun createRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "Speech recognition is not available; keeping service alive for owner gate/local fallback")
            return
        }
        var recognitionServiceName = "default"
        recognizer = runCatching {
            SpeechRecognizer.createSpeechRecognizer(this)
        }.getOrElse { error ->
            Log.w(
                TAG,
                "Default speech recognizer unavailable: ${error.message}",
            )
            val recognitionService = AIAI_RECOGNITION_SERVICE
            recognitionServiceName = recognitionService.flattenToShortString()
            SpeechRecognizer.createSpeechRecognizer(this, recognitionService)
        }.also {
            it.setRecognitionListener(this)
        }
        Log.d(TAG, "Speech recognizer created: $recognitionServiceName")
    }

    private fun handleCommandWindowIntent(intent: Intent?, source: String): Boolean {
        if (intent?.hasExtra(JarvisVoiceServiceStarter.EXTRA_COMMAND_WINDOW_MS) != true) return false

        val requestedWindowMs = intent
            .getLongExtra(JarvisVoiceServiceStarter.EXTRA_COMMAND_WINDOW_MS, 0L)
            .coerceIn(MIN_COMMAND_WINDOW_MS, MAX_COMMAND_WINDOW_MS)
        if (requestedWindowMs <= 0L) return false

        Log.i(TAG, "command_window_open source=$source windowMs=$requestedWindowMs")
        openCommandWindowSession(
            source = source.ifBlank { "command_window" },
            requestId = "",
            windowMs = requestedWindowMs,
            listenDelayMs = COMMAND_WINDOW_LISTEN_DELAY_MS,
            command = null,
        )
        return true
    }

    private fun handleDebugCommandWindowIntent(intent: Intent?): Boolean {
        if (!isDebuggableApp()) return false
        if (intent?.hasExtra(EXTRA_DEBUG_COMMAND_WINDOW_MS) != true) return false

        val requestedWindowMs = intent
            .getLongExtra(EXTRA_DEBUG_COMMAND_WINDOW_MS, 0L)
            .coerceIn(DEBUG_MIN_COMMAND_WINDOW_MS, DEBUG_MAX_COMMAND_WINDOW_MS)
        if (requestedWindowMs <= 0L) return false

        val requestId = intent?.getStringExtra(EXTRA_DEBUG_REQUEST_ID).orEmpty()
        Log.i(
            TAG,
            "debug_command_window_open request_id=$requestId windowMs=$requestedWindowMs",
        )
        val debugCommand = intent?.getStringExtra(EXTRA_DEBUG_COMMAND)
        openCommandWindowSession(
            source = "debug",
            requestId = requestId,
            windowMs = requestedWindowMs,
            listenDelayMs = DEBUG_COMMAND_WINDOW_LISTEN_DELAY_MS,
            command = debugCommand,
        )
        return true
    }

    private fun openCommandWindowSession(
        source: String,
        requestId: String,
        windowMs: Long,
        listenDelayMs: Long,
        command: String?,
    ) {
        stopOwnerAudioActivationRecognition()
        ownerVoiceGate.stop()
        stopActiveCommandRecognitionForWindowClose()
        commandFeedbackEnabled = true
        startLatencyTrace(
            "${source}_command_window_open",
            "request_id=$requestId windowMs=$windowMs",
        )
        openCommandWindow(windowMs)
        signalCommandReady()
        if (command.isNullOrBlank()) {
            scheduleListening(listenDelayMs)
        } else {
            markLatency("command_injected", "source=$source command=$command")
            completeCommandRun(runCommand(command, source).keepsCommandWindowOpen)
        }
    }

    private fun resetRecognizer() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        createRecognizer()
    }

    private fun stopActiveCommandRecognitionForWindowClose() {
        listening = false
        idleAndroidWakeListening = false
        currentListeningAllowsCommandWithoutWake = false
        androidListenAfterLocal = false
        partialCommandHandled = false
        partialCommandKeepsWindowOpen = false
        partialActivationHandled = false
        speechStartedInCurrentListen = false
        handler.removeCallbacks(listeningTimeout)
        handler.removeCallbacks(partialCommandFinalize)
        if (localCommandSession.isActive) {
            localCommandSession.stop()
        } else {
            suppressCancelledRecognizerCallbacks = true
            runCatching { recognizer?.cancel() }
        }
        idleWakeAudioBuffer.stop()
        localActivationSession.stop()
        stopActivationOwnerVerification()
        stopAndroidActivationReplay()
    }

    private fun scheduleListening(delayMs: Long) {
        if (destroyed) return
        idleAndroidWakeListening = false
        idleWakeAudioBuffer.stop()
        localActivationSession.stop()
        handler.removeCallbacks(nextCaptureRunnable)
        handler.removeCallbacks(startListeningRunnable)
        handler.postDelayed(startListeningRunnable, delayMs)
    }

    private fun scheduleNextCapture(delayMs: Long) {
        if (destroyed) return
        if (commandWindowDeadlineAt <= 0L) {
            stopAfterCommandWindow("idle_capture_disabled")
            return
        }
        handler.removeCallbacks(startListeningRunnable)
        handler.removeCallbacks(nextCaptureRunnable)
        handler.postDelayed(nextCaptureRunnable, delayMs)
    }

    private fun startIdleAndroidWakeListening(): Boolean {
        if (
            destroyed ||
            listening ||
            idleAndroidWakeListening ||
            ownerVoiceGate.isVerifying ||
            localActivationSession.isActive ||
            localCommandSession.isActive ||
            isCommandWindowOpen() ||
            System.currentTimeMillis() < idleAndroidWakeDisabledUntil ||
            recognizer == null
        ) {
            return false
        }

        val intent = SpeechRecognitionIntentFactory.create(this, commandWindowOpen = false)
        suppressCancelledRecognizerCallbacks = false
        listening = true
        idleAndroidWakeListening = true
        currentListeningAllowsCommandWithoutWake = false
        forceLocalCommandOnce = false
        forceAndroidCommandOnce = false
        androidListenAfterLocal = false
        partialCommandHandled = false
        partialCommandKeepsWindowOpen = false
        partialActivationHandled = false
        commandFeedbackEnabled = false
        speechStartedInCurrentListen = false
        feedbackController.showWakeWaiting()
        startLatencyTrace(
            "idle_activation_listen_start",
            "engine=android_stt_wake timeoutMs=$IDLE_ANDROID_WAKE_TIMEOUT_MS",
        )
        markLatency(
            "activation_listen_start",
            "engine=android_stt_wake timeoutMs=$IDLE_ANDROID_WAKE_TIMEOUT_MS",
        )

        return try {
            recognizer?.startListening(intent)
            handler.removeCallbacks(listeningTimeout)
            handler.postDelayed(listeningTimeout, IDLE_ANDROID_WAKE_TIMEOUT_MS)
            Log.d(TAG, "Idle Android wake listening started")
            true
        } catch (_: RuntimeException) {
            listening = false
            idleAndroidWakeListening = false
            speechStartedInCurrentListen = false
            handler.removeCallbacks(listeningTimeout)
            idleWakeAudioBuffer.stop()
            finishLatency("android_activation_start_failed")
            Log.w(TAG, "Failed to start idle Android wake listening")
            false
        }
    }

    private fun startListening() {
        if (
            destroyed ||
            listening ||
            idleAndroidWakeListening ||
            ownerVoiceGate.isVerifying ||
            localActivationSession.isActive
        ) {
            return
        }

        val commandWindowOpen = isCommandWindowOpen()
        if (shouldUseOwnerGate() && !commandWindowOpen) {
            currentListeningAllowsCommandWithoutWake = false
            forceLocalCommandOnce = false
            forceAndroidCommandOnce = false
            androidListenAfterLocal = false
            partialCommandHandled = false
            partialCommandKeepsWindowOpen = false
            partialActivationHandled = false
            commandFeedbackEnabled = false
            speechStartedInCurrentListen = false
            feedbackController.showOwnerVerifying()
            scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
            return
        }
        suppressCancelledRecognizerCallbacks = false
        if (commandWindowOpen && forceLocalCommandOnce && localCommandSession.canStart()) {
            ensureLatencyTrace("listen_cycle_start", "engine=local_asr mode=fallback_after_android")
                .mark("fallback_listen_requested")
            forceLocalCommandOnce = false
            startLocalCommandListening("fallback_after_android")
            return
        }
        if (recognizer == null) {
            if (commandWindowOpen && localCommandSession.canStart()) {
                ensureLatencyTrace("listen_cycle_start", "engine=local_asr recognizer=null")
                    .mark("fallback_listen_requested")
                startLocalCommandListening("android_unavailable")
            }
            return
        }

        val isAndroidFallbackAfterLocal = commandWindowOpen && forceAndroidCommandOnce
        forceAndroidCommandOnce = false
        forceLocalCommandOnce = false
        val intent = SpeechRecognitionIntentFactory.create(this, commandWindowOpen)

        try {
            listening = true
            currentListeningAllowsCommandWithoutWake = commandWindowOpen
            androidListenAfterLocal = isAndroidFallbackAfterLocal
            partialCommandHandled = false
            partialCommandKeepsWindowOpen = false
            partialActivationHandled = false
            speechStartedInCurrentListen = false
            recognizer?.startListening(intent)
            if (commandWindowOpen) {
                ensureLatencyTrace(
                    "listen_cycle_start",
                    "engine=android_stt fallbackAfterLocal=$isAndroidFallbackAfterLocal",
                ).mark(
                    "listen_start",
                    "engine=android_stt timeoutMs=${listeningTimeoutMs()} " +
                        "fallbackAfterLocal=$isAndroidFallbackAfterLocal",
                )
            }
            if (commandWindowOpen) {
                feedbackController.commandListening()
            } else {
                feedbackController.showWakeWaiting()
            }
            handler.removeCallbacks(listeningTimeout)
            handler.postDelayed(listeningTimeout, listeningTimeoutMs())
            Log.d(TAG, "Listening started")
        } catch (_: RuntimeException) {
            listening = false
            currentListeningAllowsCommandWithoutWake = false
            androidListenAfterLocal = false
            partialCommandHandled = false
            partialCommandKeepsWindowOpen = false
            partialActivationHandled = false
            speechStartedInCurrentListen = false
            handler.removeCallbacks(listeningTimeout)
            Log.w(TAG, "Failed to start listening")
            if (commandWindowOpen) feedbackController.commandFailed()
            if (commandWindowOpen) finishLatency("listen_start_failed", "engine=android_stt")
            scheduleNextCapture(1000)
        }
    }

    private fun startLocalCommandListening(mode: String) {
        if (
            destroyed ||
            listening ||
            ownerVoiceGate.isVerifying ||
            localActivationSession.isActive ||
            localCommandSession.isActive
        ) {
            return
        }

        suppressCancelledRecognizerCallbacks = false
        listening = true
        currentListeningAllowsCommandWithoutWake = true
        androidListenAfterLocal = false
        partialCommandHandled = false
        partialCommandKeepsWindowOpen = false
        partialActivationHandled = false
        speechStartedInCurrentListen = false
        ensureLatencyTrace("listen_cycle_start", "engine=local_asr mode=$mode")
            .mark("listen_start", "engine=local_asr mode=$mode timeoutMs=$LOCAL_COMMAND_TIMEOUT_MS")
        feedbackController.commandListening()
        handler.removeCallbacks(listeningTimeout)
        handler.postDelayed(listeningTimeout, LOCAL_COMMAND_TIMEOUT_MS + 500L)

        localCommandSession.start(
            timeoutMs = LOCAL_COMMAND_TIMEOUT_MS,
            onText = { text ->
                if (text.isNotBlank()) speechStartedInCurrentListen = true
                if (text.isNotBlank()) markLatency("local_partial", "text=$text")
                Log.d(TAG, "Local command partial text: $text")
            },
            onComplete = ::handleLocalCommandOutcome,
        )
        Log.d(TAG, "Local command listening started")
    }

    private fun startIdleActivationListening() {
        if (
            destroyed ||
            listening ||
            idleAndroidWakeListening ||
            ownerVoiceGate.isVerifying ||
            localActivationSession.isActive ||
            localCommandSession.isActive ||
            isCommandWindowOpen()
        ) {
            return
        }

        currentListeningAllowsCommandWithoutWake = false
        forceLocalCommandOnce = false
        forceAndroidCommandOnce = false
        androidListenAfterLocal = false
        partialCommandHandled = false
        partialCommandKeepsWindowOpen = false
        partialActivationHandled = false
        commandFeedbackEnabled = false
        speechStartedInCurrentListen = false
        feedbackController.showWakeWaiting()
        startLatencyTrace(
            "idle_activation_listen_start",
            "engine=local_activation_asr timeoutMs=$LOCAL_ACTIVATION_TIMEOUT_MS",
        )
        markLatency(
            "activation_listen_start",
            "engine=local_activation_asr timeoutMs=$LOCAL_ACTIVATION_TIMEOUT_MS",
        )

        localActivationSession.start(
            timeoutMs = LOCAL_ACTIVATION_TIMEOUT_MS,
            onText = { text ->
                if (text.isNotBlank()) {
                    markLatency("activation_partial", "source=local_activation_asr text=$text")
                }
                Log.d(TAG, "Local activation partial text: $text")
            },
            onRejected = ::handleLocalActivationRejectedSegment,
            onComplete = ::handleLocalActivationOutcome,
        )
        Log.d(TAG, "Local activation listening started")
    }

    private fun handleAndroidActivationRecognized(text: String, source: String): Boolean {
        if (!idleAndroidWakeListening || activationOwnerVerificationActive) return false

        val snapshot = idleWakeAudioBuffer.stopAndSnapshot()
        listening = false
        idleAndroidWakeListening = false
        currentListeningAllowsCommandWithoutWake = false
        androidListenAfterLocal = false
        speechStartedInCurrentListen = false
        handler.removeCallbacks(listeningTimeout)
        suppressCancelledRecognizerCallbacks = true
        runCatching { recognizer?.cancel() }

        markLatency("android_activation_detected", "source=$source text=$text")
        markLatency(
            "android_activation_audio_snapshot",
            "source=${snapshot.source} samplesMs=${snapshot.durationMs} " +
                "peakRms=${snapshot.peakRms} meanRms=${snapshot.meanRms}",
        )
        Log.d(
            TAG,
            "Idle Android wake recognized '$text' from $source; " +
                "owner snapshot source=${snapshot.source}, samplesMs=${snapshot.durationMs}, " +
                "peakRms=${snapshot.peakRms}",
        )

        if (snapshot.samples.isEmpty()) {
            finishLatency("android_activation_audio_missing", "source=$source text=$text")
            scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
            return true
        }

        val result = LocalCommandRecognizer.ActivationResult(
            text = text,
            samples = snapshot.samples,
            elapsedMs = snapshot.durationMs,
            endpoint = "android_stt_wake_$source",
            activeSpeechMs = snapshot.durationMs,
            trailingSilenceMs = 0L,
            peakRms = snapshot.peakRms,
            meanRms = snapshot.meanRms,
            asrGain = 1f,
        )
        saveActivationAttemptDebugCapture(
            samples = snapshot.samples,
            result = result,
            accepted = true,
        )
        startActivationOwnerVerification(result)
        return true
    }

    private fun saveAndroidActivationSnapshot(
        snapshot: IdleWakeAudioBuffer.Snapshot,
        endpoint: String,
        text: String = "",
        accepted: Boolean = false,
    ) {
        if (snapshot.samples.isEmpty()) return

        saveActivationAttemptDebugCapture(
            samples = snapshot.samples,
            accepted = accepted,
            text = text,
            endpoint = endpoint,
            elapsedMs = snapshot.durationMs,
            activeSpeechMs = snapshot.durationMs,
            trailingSilenceMs = 0L,
            peakRms = snapshot.peakRms,
            meanRms = snapshot.meanRms,
            asrGain = 1f,
        )
    }

    private fun shouldFallbackAndroidWakeToLocal(error: Int, hadSpeechInCurrentListen: Boolean): Boolean {
        if (!hadSpeechInCurrentListen) return false
        return error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
    }

    private fun startAndroidActivationReplay(
        snapshot: IdleWakeAudioBuffer.Snapshot,
        endpoint: String,
        reason: String,
        text: String = "",
    ): Boolean {
        if (androidActivationReplayActive || snapshot.samples.isEmpty()) return false
        if (!LocalCommandRecognizer.isAvailable(applicationContext)) return false

        androidActivationReplayActive = true
        idleAndroidWakeDisabledUntil = System.currentTimeMillis() + ANDROID_WAKE_FALLBACK_DISABLE_MS
        idleAndroidWakeConsecutiveStartErrors = 0
        markLatency(
            "android_activation_disabled",
            "reason=$reason durationMs=$ANDROID_WAKE_FALLBACK_DISABLE_MS",
        )
        markLatency(
            "android_activation_local_replay_start",
            "endpoint=$endpoint samplesMs=${snapshot.durationMs} text=$text",
        )
        androidActivationReplayThread = Thread({
            var failed = false
            val result = runCatching {
                LocalCommandRecognizer.recognizeBufferedActivation(
                    context = applicationContext,
                    samples = snapshot.samples,
                    endpoint = "${endpoint}_local_replay",
                )
            }.onFailure {
                failed = true
                Log.w(TAG, "Android wake local replay failed: ${it.message}")
            }.getOrNull()
            result?.let {
                saveActivationAttemptDebugCapture(
                    samples = snapshot.samples,
                    result = it,
                    accepted = CommandInterpreter.isActivationWakeAsrEquivalent(it.text),
                )
            }

            handler.post {
                if (!androidActivationReplayActive || destroyed) return@post

                androidActivationReplayActive = false
                androidActivationReplayThread = null
                handleAndroidActivationReplayOutcome(
                    result = result,
                    failed = failed,
                    reason = reason,
                    samples = snapshot.samples,
                )
            }
        }, "JarvisAndroidActivationReplay").also { it.start() }
        return true
    }

    private fun handleAndroidActivationReplayOutcome(
        result: LocalCommandRecognizer.Result?,
        failed: Boolean,
        reason: String,
        samples: FloatArray,
    ) {
        if (result != null) {
            markLatency(
                "android_activation_local_replay_complete",
                "endpoint=${result.endpoint} elapsedMs=${result.elapsedMs} " +
                    "speechMs=${result.activeSpeechMs} peakRms=${result.peakRms} " +
                    "meanRms=${result.meanRms} asrGain=${result.asrGain} text=${result.text}",
            )
        }

        when {
            result != null && CommandInterpreter.isActivationWakeAsrEquivalent(result.text) -> {
                val activationSamples = LocalCommandRecognizer.activationSamplesForResult(samples, result)
                markLatency("android_activation_local_replay_detected", "text=${result.text}")
                startActivationOwnerVerification(
                    LocalCommandRecognizer.ActivationResult(
                        text = result.text,
                        samples = samples,
                        alternateSamples = activationSamples.takeUnless { it === samples },
                        elapsedMs = result.elapsedMs,
                        unavailable = result.unavailable,
                        endpoint = result.endpoint,
                        activeSpeechMs = result.activeSpeechMs,
                        trailingSilenceMs = result.trailingSilenceMs,
                        peakRms = result.peakRms,
                        meanRms = result.meanRms,
                        asrGain = result.asrGain,
                    ),
                )
            }
            failed || result?.unavailable == true -> {
                finishLatency("android_activation_local_replay_unavailable", "reason=$reason")
                scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
            }
            else -> {
                finishLatency(
                    "android_activation_local_replay_rejected",
                    "reason=$reason text=${result?.text.orEmpty()}",
                )
                scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
            }
        }
    }

    private fun stopAndroidActivationReplay() {
        androidActivationReplayActive = false
        androidActivationReplayThread?.interrupt()
        androidActivationReplayThread = null
    }

    private fun handleLocalActivationRejectedSegment(result: LocalCommandRecognizer.ActivationResult) {
        markLatency(
            "activation_asr_rejected_segment",
            "endpoint=${result.endpoint} elapsedMs=${result.elapsedMs} " +
                "speechMs=${result.activeSpeechMs} trailingMs=${result.trailingSilenceMs} " +
                "peakRms=${result.peakRms} meanRms=${result.meanRms} " +
                "asrGain=${result.asrGain} text=${result.text}",
        )
        saveActivationAttemptDebugCapture(
            samples = result.samples,
            result = result,
            accepted = false,
        )
    }

    private fun handleLocalActivationOutcome(outcome: LocalActivationSession.Outcome) {
        if (destroyed) return

        val result = outcome.result
        val acceptedResult = result?.takeIf {
            CommandInterpreter.isActivationWakeAsrEquivalent(it.text)
        }
        if (acceptedResult != null) {
            startLatencyTrace(
                "idle_activation_detected",
                "engine=local_activation_asr elapsedMs=${acceptedResult.elapsedMs} text=${acceptedResult.text}",
            )
        }
        if (result != null) {
            markLatency(
                "activation_asr_complete",
                "endpoint=${result.endpoint} elapsedMs=${result.elapsedMs} " +
                    "speechMs=${result.activeSpeechMs} trailingMs=${result.trailingSilenceMs} " +
                    "peakRms=${result.peakRms} meanRms=${result.meanRms} " +
                    "asrGain=${result.asrGain} text=${result.text}",
            )
            saveActivationAttemptDebugCapture(
                samples = result.samples,
                result = result,
                accepted = CommandInterpreter.isActivationWakeAsrEquivalent(result.text),
            )
        }

        when {
            outcome.unavailable -> {
                finishLatency("activation_asr_unavailable")
                scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
            }
            acceptedResult != null -> {
                startActivationOwnerVerification(acceptedResult)
            }
            else -> {
                finishLatency(
                    "activation_phrase_missing",
                    "text=${result?.text.orEmpty()} endpoint=${result?.endpoint.orEmpty()}",
                )
                scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
            }
        }
    }

    private fun startActivationOwnerVerification(result: LocalCommandRecognizer.ActivationResult) {
        if (activationOwnerVerificationActive) return

        activationOwnerVerificationActive = true
        val acousticWakeFallback = LocalCommandRecognizer.isAcousticWakeFallback(result)
        markLatency(
            "activation_owner_verify_start",
            "samples=${result.samples.size} endpoint=${result.endpoint} " +
                "acousticFallback=$acousticWakeFallback text=${result.text}",
        )
        activationOwnerVerificationThread = Thread({
            val ownerWindowedMatch = runCatching {
                val primary = OwnerVoiceEngine.verifyOwnerBestWindow(applicationContext, result.samples)
                val alternate = result.alternateSamples
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { OwnerVoiceEngine.verifyOwnerBestWindow(applicationContext, it) }
                if (alternate != null && isBetterActivationOwnerWindow(alternate, primary)) {
                    alternate
                } else {
                    primary
                }
            }.onFailure {
                Log.w(TAG, "Activation owner verification failed: ${it.message}")
            }.getOrElse {
                val fallback = OwnerVoiceEngine.Match(
                    score = 0f,
                    accepted = false,
                    rejectReason = OwnerVoiceEngine.RejectReason.EMBEDDING_NOT_READY,
                )
                OwnerVoiceEngine.WindowedMatch(
                    match = fallback,
                    fullMatch = fallback,
                    windowStartMs = 0L,
                    windowDurationMs = result.samples.size * 1000L / OwnerVoiceEngine.SAMPLE_RATE_HZ,
                    evaluatedWindows = 0,
                )
            }
            val ownerMatch = if (acousticWakeFallback) {
                OwnerVoiceEngine.acceptAcousticWakeFallbackMatch(ownerWindowedMatch.match)
            } else {
                OwnerVoiceEngine.acceptActivationPhraseMatch(ownerWindowedMatch.match)
            }

            handler.post {
                if (!activationOwnerVerificationActive || destroyed) return@post

                activationOwnerVerificationActive = false
                activationOwnerVerificationThread = null
                handleActivationOwnerVerificationOutcome(result, ownerMatch, ownerWindowedMatch)
            }
        }, "JarvisActivationOwnerVerify").also { it.start() }
    }

    private fun isBetterActivationOwnerWindow(
        candidate: OwnerVoiceEngine.WindowedMatch,
        current: OwnerVoiceEngine.WindowedMatch,
    ): Boolean {
        return when {
            candidate.match.accepted && !current.match.accepted -> true
            !candidate.match.accepted && current.match.accepted -> false
            candidate.match.score != current.match.score -> candidate.match.score > current.match.score
            else -> candidate.match.activeSpeechMs > current.match.activeSpeechMs
        }
    }

    private fun handleActivationOwnerVerificationOutcome(
        result: LocalCommandRecognizer.ActivationResult,
        ownerMatch: OwnerVoiceEngine.Match,
        ownerWindowedMatch: OwnerVoiceEngine.WindowedMatch,
    ) {
        markLatency(
            "activation_owner_verified",
            "accepted=${ownerMatch.accepted} acceptance=${ownerMatch.acceptance} " +
                "endpoint=${result.endpoint} " +
                "score=${ownerMatch.score} fullScore=${ownerWindowedMatch.fullMatch.score} " +
                "speechMs=${ownerMatch.activeSpeechMs} windowStartMs=${ownerWindowedMatch.windowStartMs} " +
                "windowMs=${ownerWindowedMatch.windowDurationMs} windows=${ownerWindowedMatch.evaluatedWindows} " +
                "peakRms=${ownerMatch.peakRms} reason=${ownerMatch.rejectReason ?: "none"}",
        )
        if (ownerMatch.accepted) {
            markLatency("owner_audio_activation", "source=${result.endpoint} text=${result.text}")
            Log.d(TAG, "Parsed activation phrase from '${result.text}' via ${result.endpoint}")
            commandFeedbackEnabled = true
            openCommandWindow(COMMAND_SESSION_AUTH_WINDOW_MS)
            signalCommandReady()
            scheduleListening(OWNER_READY_LISTEN_DELAY_MS)
        } else {
            finishLatency("activation_owner_rejected", "text=${result.text} score=${ownerMatch.score}")
            scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
        }
    }

    private fun stopActivationOwnerVerification() {
        activationOwnerVerificationActive = false
        activationOwnerVerificationThread?.interrupt()
        activationOwnerVerificationThread = null
    }

    private fun handleLocalCommandOutcome(outcome: LocalCommandSession.Outcome) {
        if (destroyed) return

        val wasListeningForCommand = currentListeningAllowsCommandWithoutWake
        listening = false
        currentListeningAllowsCommandWithoutWake = false
        androidListenAfterLocal = false
        speechStartedInCurrentListen = false
        handler.removeCallbacks(listeningTimeout)

        val result = outcome.result
        val command = result?.command
        if (result != null) {
            markLatency(
                "local_complete",
                "endpoint=${result.endpoint} elapsedMs=${result.elapsedMs} " +
                    "speechMs=${result.activeSpeechMs} silenceMs=${result.trailingSilenceMs} " +
                    "peakRms=${result.peakRms} meanRms=${result.meanRms} " +
                    "asrGain=${result.asrGain} " +
                    "text=${result.text}",
            )
        }
        when {
            command != null -> {
                markLatency("command_parsed", "source=local command=$command text=${result.text}")
                Log.d(
                    TAG,
                    "Parsed local command: $command from '${result.text}' in ${result.elapsedMs}ms",
                )
                completeCommandRun(runCommand(command, "local").keepsCommandWindowOpen)
            }
            outcome.unavailable -> {
                Log.w(TAG, "Local command recognizer unavailable")
                if (wasListeningForCommand && startAndroidFallbackAfterLocal("local_asr_unavailable")) return
                feedbackController.commandFailed()
                finishLatency("local_asr_unavailable")
                scheduleNextCapture(COMMAND_RETRY_DELAY_MS)
            }
            else -> {
                markLatency(
                    "local_no_command",
                    "endpoint=${result?.endpoint.orEmpty()} text=${result?.text.orEmpty()} " +
                        "elapsedMs=${result?.elapsedMs ?: 0L}",
                )
                Log.d(
                    TAG,
                    "Local command finished without command: " +
                        "endpoint='${result?.endpoint.orEmpty()}', " +
                        "text='${result?.text.orEmpty()}', elapsed=${result?.elapsedMs ?: 0L}ms",
                )
                if (wasListeningForCommand && isCommandWindowExpired()) {
                    closeCommandWindow(playFeedback = false)
                    finishLatency("command_window_expired_after_local_no_command")
                    stopAfterCommandWindow("local_no_command_expired")
                    return
                }
                if (result?.endpoint == "no_speech_timeout") {
                    if (wasListeningForCommand && shouldUseOwnerGate()) {
                        extendCommandWindowWithinDeadline(COMMAND_RETRY_GRACE_MS)
                    }
                    finishLatency("local_no_speech_retry")
                    scheduleListening(COMMAND_RETRY_DELAY_MS)
                    return
                }
                if (shouldContinueLocalAfterShortSpeech(result)) {
                    if (wasListeningForCommand && shouldUseOwnerGate()) {
                        extendCommandWindowWithinDeadline(COMMAND_RETRY_GRACE_MS)
                    }
                    finishLatency("local_short_speech_retry")
                    scheduleListening(COMMAND_RETRY_DELAY_MS)
                    return
                }
                if (wasListeningForCommand && startAndroidFallbackAfterLocal("local_no_command")) return
                feedbackController.commandFailed()
                if (wasListeningForCommand && shouldUseOwnerGate()) {
                    extendCommandWindowWithinDeadline(COMMAND_RETRY_GRACE_MS)
                }
                finishLatency("local_no_command_retry")
                scheduleNextCapture(COMMAND_RETRY_DELAY_MS)
            }
        }
    }

    private fun shouldContinueLocalAfterShortSpeech(result: LocalCommandRecognizer.Result?): Boolean {
        if (result == null) return false
        if (result.text.isNotBlank()) return false
        return result.activeSpeechMs in 1 until LOCAL_ANDROID_FALLBACK_MIN_SPEECH_MS
    }

    private fun startOwnerAudioActivationRecognition(match: OwnerVoiceEngine.Match): Boolean {
        val samples = match.commandSamples ?: return false
        if (!LocalCommandRecognizer.isAvailable(applicationContext)) return false
        if (ownerAudioActivationActive) return false

        ownerAudioActivationActive = true
        val samplesMs = samples.size * 1000L / OwnerVoiceEngine.SAMPLE_RATE_HZ
        markLatency(
            "owner_audio_asr_start",
            "engine=owner_audio_asr samplesMs=$samplesMs acceptance=${match.acceptance}",
        )
        ownerAudioActivationThread = Thread({
            var failed = false
            val result = runCatching {
                LocalCommandRecognizer.recognizeBufferedActivation(
                    context = applicationContext,
                    samples = samples,
                    endpoint = "owner_audio",
                )
            }.onFailure {
                failed = true
                Log.w(TAG, "Owner audio command recognition failed: ${it.message}")
            }.getOrNull()
            result?.let {
                saveActivationAttemptDebugCapture(
                    samples = samples,
                    result = it,
                    accepted = CommandInterpreter.isActivationWakeAsrEquivalent(it.text),
                )
            }

            handler.post {
                if (!ownerAudioActivationActive) return@post

                ownerAudioActivationActive = false
                ownerAudioActivationThread = null
                handleOwnerAudioActivationOutcome(result, failed)
            }
        }, "JarvisOwnerAudioActivation").also { it.start() }
        return true
    }

    private fun saveActivationAttemptDebugCapture(
        samples: FloatArray,
        result: LocalCommandRecognizer.Result,
        accepted: Boolean,
    ) {
        saveActivationAttemptDebugCapture(
            samples = samples,
            accepted = accepted,
            text = result.text,
            endpoint = result.endpoint,
            elapsedMs = result.elapsedMs,
            activeSpeechMs = result.activeSpeechMs,
            trailingSilenceMs = result.trailingSilenceMs,
            peakRms = result.peakRms,
            meanRms = result.meanRms,
            asrGain = result.asrGain,
        )
    }

    private fun saveActivationAttemptDebugCapture(
        samples: FloatArray,
        result: LocalCommandRecognizer.ActivationResult,
        accepted: Boolean,
    ) {
        saveActivationAttemptDebugCapture(
            samples = samples,
            accepted = accepted,
            text = result.text,
            endpoint = result.endpoint,
            elapsedMs = result.elapsedMs,
            activeSpeechMs = result.activeSpeechMs,
            trailingSilenceMs = result.trailingSilenceMs,
            peakRms = result.peakRms,
            meanRms = result.meanRms,
            asrGain = result.asrGain,
        )
    }

    private fun saveActivationAttemptDebugCapture(
        samples: FloatArray,
        accepted: Boolean,
        text: String,
        endpoint: String,
        elapsedMs: Long,
        activeSpeechMs: Long,
        trailingSilenceMs: Long,
        peakRms: Float,
        meanRms: Float,
        asrGain: Float,
    ) {
        if (!isDebuggableApp()) return

        runCatching {
            val dir = File(cacheDir, ACTIVATION_CAPTURE_DIR)
            val timestampMs = System.currentTimeMillis()
            val outcome = if (accepted) "accepted" else "rejected"
            val baseName = "activation-${timestampMs}-$outcome"
            val wavFile = File(dir, "$baseName.wav")
            val metadataFile = File(dir, "$baseName.json")

            PcmWavFile.writeMono16(
                file = wavFile,
                samples = samples,
                sampleRateHz = OwnerVoiceEngine.SAMPLE_RATE_HZ,
            )
            val metadata = JSONObject()
                .put("timestampMs", timestampMs)
                .put("accepted", accepted)
                .put("text", text)
                .put("endpoint", endpoint)
                .put("elapsedMs", elapsedMs)
                .put("activeSpeechMs", activeSpeechMs)
                .put("trailingSilenceMs", trailingSilenceMs)
                .put("peakRms", peakRms)
                .put("meanRms", meanRms)
                .put("asrGain", asrGain)
                .put("sampleCount", samples.size)
                .put("sampleRateHz", OwnerVoiceEngine.SAMPLE_RATE_HZ)
            metadataFile.writeText(metadata.toString(2), Charsets.UTF_8)
            pruneActivationAttemptDebugCaptures(dir)
            Log.i(
                TAG,
                "activation_debug_capture wav=${wavFile.absolutePath} " +
                    "metadata=${metadataFile.absolutePath} accepted=$accepted text=$text",
            )
        }.onFailure {
            Log.w(TAG, "Failed to write activation debug capture: ${it.message}")
        }
    }

    private fun isDebuggableApp(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun pruneActivationAttemptDebugCaptures(dir: File) {
        val files = dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(MAX_ACTIVATION_CAPTURE_FILES).forEach { file ->
            runCatching { file.delete() }
        }
    }

    private fun handleOwnerAudioActivationOutcome(
        result: LocalCommandRecognizer.Result?,
        failed: Boolean,
    ) {
        if (destroyed) return

        if (result != null) {
            markLatency(
                "owner_audio_asr_complete",
                "endpoint=${result.endpoint} elapsedMs=${result.elapsedMs} " +
                    "speechMs=${result.activeSpeechMs} peakRms=${result.peakRms} " +
                    "meanRms=${result.meanRms} asrGain=${result.asrGain} text=${result.text}",
            )
        }

        when {
            result != null && CommandInterpreter.isActivationWakeAsrEquivalent(result.text) -> {
                markLatency("owner_audio_activation", "text=${result.text}")
                Log.d(TAG, "Parsed owner audio activation phrase from '${result.text}'")
                commandFeedbackEnabled = true
                openCommandWindow(COMMAND_SESSION_AUTH_WINDOW_MS)
                signalCommandReady()
                if (listening) {
                    markLatency("owner_audio_activation_listening")
                } else {
                    scheduleListening(ownerReadyDelayAfter(result))
                }
            }
            failed || result?.unavailable == true -> {
                markLatency("owner_audio_asr_unavailable")
                ownerVoiceGate.clearAuthorization()
                finishLatency("activation_asr_unavailable")
                if (!listening) scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
            }
            else -> {
                markLatency(
                    "owner_audio_activation_rejected",
                    "text=${result?.text.orEmpty()} elapsedMs=${result?.elapsedMs ?: 0L}",
                )
                ownerVoiceGate.clearAuthorization()
                finishLatency("activation_phrase_missing")
                if (!listening) scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
            }
        }
    }

    private fun ownerReadyDelayAfter(result: LocalCommandRecognizer.Result?): Long {
        val elapsedMs = result?.elapsedMs ?: 0L
        return (OWNER_READY_LISTEN_DELAY_MS - elapsedMs).coerceAtLeast(0L)
    }

    private fun stopOwnerAudioActivationRecognition() {
        ownerAudioActivationActive = false
        ownerAudioActivationThread?.interrupt()
        ownerAudioActivationThread = null
    }

    private fun startAndroidFallbackAfterLocal(reason: String): Boolean {
        if (recognizer == null || isCommandWindowExpired()) return false

        markLatency("fallback_to_android", "reason=$reason")
        forceAndroidCommandOnce = true
        scheduleListening(FALLBACK_LISTEN_DELAY_MS)
        return true
    }

    private fun startOwnerVerification() {
        if (destroyed || ownerVoiceGate.isVerifying || localActivationSession.isActive || listening) return
        ownerVoiceGate.startVerification(
            audioWindowMs = OWNER_VERIFY_AUDIO_MS,
            verificationIntervalMs = OWNER_VERIFY_INTERVAL_MS,
            postAcceptAudioMs = OWNER_POST_ACCEPT_AUDIO_MS,
            activationAudioWindowMs = OWNER_ACTIVATION_AUDIO_MS,
        )
    }

    private fun shouldUseOwnerGate(): Boolean = ownerVoiceGate.isConfigured()

    private fun isOwnerAuthorized(): Boolean {
        return ownerVoiceGate.isAuthorized()
    }

    private fun startLatencyTrace(event: String, detail: String = ""): JarvisLatencyTrace {
        return JarvisLatencyTrace.start(event, detail).also {
            latencyTrace = it
        }
    }

    private fun ensureLatencyTrace(event: String, detail: String = ""): JarvisLatencyTrace {
        return latencyTrace ?: startLatencyTrace(event, detail)
    }

    private fun markLatency(event: String, detail: String = "") {
        latencyTrace?.mark(event, detail)
    }

    private fun finishLatency(event: String, detail: String = "") {
        latencyTrace?.finish(event, detail)
        latencyTrace = null
    }

    private fun runCommand(command: String, source: String): JarvisCommandExecutor.Result {
        if (source != "owner_audio") {
            stopOwnerAudioActivationRecognition()
        }
        val trace = ensureLatencyTrace("command_trace_start", "source=$source command=$command")
        trace.mark("command_execute_start", "source=$source command=$command")
        val result = commandExecutor.run(command, trace.id, trace.startedAtMs)
        trace.mark(
            "command_execute_return",
            "source=$source command=$command keepWindow=${result.keepsCommandWindowOpen}",
        )
        return result
    }

    private fun isCommandWindowOpen(): Boolean {
        if (!shouldUseOwnerGate()) return false
        if (isCommandWindowExpired()) {
            closeCommandWindow(playFeedback = false)
            return false
        }
        return commandWindowDeadlineAt > 0L && isOwnerAuthorized()
    }

    private fun isCommandWindowExpired(now: Long = System.currentTimeMillis()): Boolean {
        return commandWindowDeadlineAt > 0L && now >= commandWindowDeadlineAt
    }

    private fun remainingCommandWindowMs(now: Long = System.currentTimeMillis()): Long {
        return (commandWindowDeadlineAt - now).coerceAtLeast(0L)
    }

    private fun openCommandWindow(durationMs: Long) {
        if (!shouldUseOwnerGate()) return

        commandWindowDeadlineAt = System.currentTimeMillis() + durationMs
        commandWindowSpeechGraceUntil = 0L
        ownerVoiceGate.authorizeFor(durationMs)
        handler.removeCallbacks(commandWindowTimeout)
        handler.postDelayed(commandWindowTimeout, durationMs)
    }

    private fun closeCommandWindow(playFeedback: Boolean) {
        commandWindowDeadlineAt = 0L
        idleAndroidWakeListening = false
        currentListeningAllowsCommandWithoutWake = false
        forceLocalCommandOnce = false
        forceAndroidCommandOnce = false
        androidListenAfterLocal = false
        partialCommandHandled = false
        partialCommandKeepsWindowOpen = false
        commandFeedbackEnabled = false
        commandReadyFeedbackPending = false
        partialActivationHandled = false
        commandWindowSpeechGraceUntil = 0L
        speechStartedInCurrentListen = false
        ownerVoiceGate.clearAuthorization()
        handler.removeCallbacks(startListeningRunnable)
        handler.removeCallbacks(partialCommandFinalize)
        handler.removeCallbacks(commandWindowTimeout)
        localActivationSession.stop()
        idleWakeAudioBuffer.stop()
        stopActivationOwnerVerification()
        notificationController.reset()
        if (playFeedback) {
            feedbackController.commandWindowClosed()
        } else {
            feedbackController.showWakeWaiting()
        }
    }

    private fun stopAfterCommandWindow(reason: String) {
        if (destroyed) return

        Log.d(TAG, "Stopping JarvisVoiceService after command window: reason=$reason")
        notificationController.update("Jarvis 명령 대기가 종료되었습니다.")
        handler.removeCallbacks(nextCaptureRunnable)
        handler.removeCallbacks(startListeningRunnable)
        handler.postDelayed({ if (!destroyed) stopSelf() }, SERVICE_STOP_DELAY_MS)
    }

    private fun extendCommandWindowWithinDeadline(durationMs: Long) {
        if (!shouldUseOwnerGate()) return

        val remainingMs = remainingCommandWindowMs()
        if (remainingMs <= 0L) {
            closeCommandWindow(playFeedback = false)
            return
        }

        ownerVoiceGate.extendAuthorization(durationMs.coerceAtMost(remainingMs))
    }

    private fun handleSpeech(bundle: Bundle?): SpeechOutcome {
        val results = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        if (results.isNotEmpty()) Log.d(TAG, "Speech results: $results")

        for (candidate in results) {
            val command = CommandInterpreter.parse(
                text = candidate,
                requireWakeWord = true,
            ) ?: continue
            markLatency("command_parsed", "source=final command=$command text=$candidate")
            Log.d(TAG, "Parsed command: $command from '$candidate'")
            partialActivationHandled = false
            return if (runCommand(command, "final").keepsCommandWindowOpen) {
                SpeechOutcome.COMMAND_RUN_KEEP_WINDOW
            } else {
                SpeechOutcome.COMMAND_RUN
            }
        }

        if (!currentListeningAllowsCommandWithoutWake && results.any(CommandInterpreter::isActivationWake)) {
            Log.d(TAG, "Activation phrase recognized; keeping command window open")
            markLatency("activation")
            openCommandWindow(COMMAND_SESSION_AUTH_WINDOW_MS)
            commandFeedbackEnabled = true
            signalCommandReady()
            return SpeechOutcome.ACTIVATION
        }

        markLatency("parse_no_command", "count=${results.size}")
        return SpeechOutcome.NO_COMMAND
    }

    private fun completeCommandRun(keepCommandWindowOpen: Boolean) {
        if (destroyed) return
        if (keepCommandWindowOpen) {
            openCommandWindow(COMMAND_SESSION_AUTH_WINDOW_MS)
            commandFeedbackEnabled = true
            commandReadyFeedbackPending = false
            notificationController.update("명령 처리됨. 다음 명령을 말하세요.")
            feedbackController.commandHandled()
            finishLatency("command_complete", "keepWindow=true nextWindowMs=$COMMAND_SESSION_AUTH_WINDOW_MS")
            scheduleListening(COMMAND_CHAIN_LISTEN_DELAY_MS)
        } else {
            closeCommandWindow(playFeedback = true)
            finishLatency("command_complete", "keepWindow=false")
            stopAfterCommandWindow("command_complete")
        }
    }

    private fun completePartialCommandRun(reason: String): Boolean {
        if (!partialCommandHandled) return false

        val keepCommandWindowOpen = partialCommandKeepsWindowOpen
        partialCommandHandled = false
        partialCommandKeepsWindowOpen = false
        currentListeningAllowsCommandWithoutWake = false
        handler.removeCallbacks(partialCommandFinalize)
        markLatency("partial_command_complete", "reason=$reason keepWindow=$keepCommandWindowOpen")
        Log.d(TAG, "Completing partial command run: $reason")
        partialActivationHandled = false
        completeCommandRun(keepCommandWindowOpen)
        return true
    }

    private fun signalCommandReady() {
        commandReadyFeedbackPending = true
        notificationController.update("JARVIS 실행 중. 명령을 말하세요.")
        feedbackController.commandListening()
    }

    override fun onReadyForSpeech(params: Bundle?) {
        if (shouldSuppressCancelledRecognizerCallback()) return

        markLatency("ready_for_speech")
        if (idleAndroidWakeListening) {
            idleAndroidWakeConsecutiveStartErrors = 0
            if (!idleWakeAudioBuffer.isActive) {
                if (idleWakeAudioBuffer.start()) {
                    markLatency("android_activation_audio_buffer_start")
                } else {
                    markLatency("android_activation_audio_unavailable")
                }
            }
        }
        if (commandFeedbackEnabled && (currentListeningAllowsCommandWithoutWake || isCommandWindowOpen())) {
            if (commandReadyFeedbackPending) {
                commandReadyFeedbackPending = false
                feedbackController.commandReady()
            } else {
                feedbackController.commandListening()
            }
        }
        Log.d(TAG, "Ready for speech")
    }

    override fun onBeginningOfSpeech() {
        if (shouldSuppressCancelledRecognizerCallback()) return

        speechStartedInCurrentListen = true
        markLatency("speech_begin")
        Log.d(TAG, "Beginning of speech")
    }

    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() {
        if (shouldSuppressCancelledRecognizerCallback()) return

        listening = false
        handler.removeCallbacks(listeningTimeout)
        markLatency("speech_end")
        Log.d(TAG, "End of speech")
    }

    override fun onError(error: Int) {
        if (shouldSuppressCancelledRecognizerCallback(clear = true)) return

        val wasListeningForCommand = currentListeningAllowsCommandWithoutWake
        val wasIdleAndroidWake = idleAndroidWakeListening
        val wasAndroidFallbackAfterLocal = androidListenAfterLocal
        val hadSpeechInCurrentListen = speechStartedInCurrentListen
        val idleWakeSnapshot = if (wasIdleAndroidWake) idleWakeAudioBuffer.stopAndSnapshot() else null
        listening = false
        idleAndroidWakeListening = false
        androidListenAfterLocal = false
        speechStartedInCurrentListen = false
        handler.removeCallbacks(listeningTimeout)
        markLatency(
            "speech_error",
            "code=$error commandWindow=$wasListeningForCommand " +
                "idleAndroidWake=$wasIdleAndroidWake " +
                "fallbackAfterLocal=$wasAndroidFallbackAfterLocal " +
                "speechDetected=$hadSpeechInCurrentListen",
        )
        Log.w(TAG, "Speech error: $error")
        if (completePartialCommandRun("speech error $error")) return
        if (completePartialActivation("speech error $error")) return

        currentListeningAllowsCommandWithoutWake = false
        if (shouldResetRecognizerAfterError(error)) {
            markLatency("recognizer_reset", "code=$error")
            resetRecognizer()
        }
        if (wasIdleAndroidWake) {
            val failedBeforeAudioStarted = idleWakeSnapshot?.samples?.isEmpty() == true && !hadSpeechInCurrentListen
            if (shouldResetRecognizerAfterError(error) && failedBeforeAudioStarted) {
                idleAndroidWakeConsecutiveStartErrors += 1
            } else {
                idleAndroidWakeConsecutiveStartErrors = 0
            }
            val disableAndroidWake = idleAndroidWakeConsecutiveStartErrors >=
                ANDROID_WAKE_START_ERROR_FALLBACK_THRESHOLD
            if (disableAndroidWake) {
                idleAndroidWakeDisabledUntil = System.currentTimeMillis() + ANDROID_WAKE_FALLBACK_DISABLE_MS
                markLatency(
                    "android_activation_disabled",
                    "reason=start_error count=$idleAndroidWakeConsecutiveStartErrors " +
                        "durationMs=$ANDROID_WAKE_FALLBACK_DISABLE_MS",
                )
                idleAndroidWakeConsecutiveStartErrors = 0
            }
            val retryDelay = when {
                disableAndroidWake -> OWNER_VERIFY_RETRY_MS
                shouldResetRecognizerAfterError(error) -> RECOGNIZER_RESET_RETRY_DELAY_MS
                else -> OWNER_VERIFY_RETRY_MS
            }
            idleWakeSnapshot?.let { snapshot ->
                markLatency(
                    "android_activation_audio_snapshot",
                    "source=${snapshot.source} samplesMs=${snapshot.durationMs} " +
                        "peakRms=${snapshot.peakRms} meanRms=${snapshot.meanRms}",
                )
                saveAndroidActivationSnapshot(
                    snapshot = snapshot,
                    endpoint = "android_stt_wake_error_$error",
                )
            }
            if (
                shouldFallbackAndroidWakeToLocal(error, hadSpeechInCurrentListen) &&
                idleWakeSnapshot != null &&
                startAndroidActivationReplay(
                    snapshot = idleWakeSnapshot,
                    endpoint = "android_stt_wake_error_$error",
                    reason = "speech_error_$error",
                )
            ) {
                return
            }
            finishLatency(
                if (hadSpeechInCurrentListen) "android_activation_error_retry" else "android_activation_idle_retry",
                "code=$error delayMs=$retryDelay",
            )
            scheduleNextCapture(retryDelay)
            return
        }
        if (wasListeningForCommand && isCommandWindowExpired()) {
            closeCommandWindow(playFeedback = false)
            finishLatency("command_window_expired_after_speech_error", "code=$error")
            stopAfterCommandWindow("speech_error_expired")
            return
        }
        if (
            wasListeningForCommand &&
            !wasAndroidFallbackAfterLocal &&
            shouldFallbackToLocalCommand(error, hadSpeechInCurrentListen) &&
            localCommandSession.canStart()
        ) {
            Log.w(TAG, "Speech recognizer failed in command window; falling back to local command recognizer")
            markLatency("fallback_to_local", "reason=speech_error code=$error")
            if (shouldUseOwnerGate()) {
                extendCommandWindowWithinDeadline(LOCAL_FALLBACK_AUTH_EXTENSION_MS)
                if (!isCommandWindowOpen()) {
                    finishLatency("fallback_blocked_by_deadline", "code=$error")
                    stopAfterCommandWindow("fallback_deadline_expired")
                    return
                }
            }
            forceLocalCommandOnce = true
            scheduleListening(FALLBACK_LISTEN_DELAY_MS)
            return
        }

        val delay = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                if ((wasListeningForCommand || isCommandWindowOpen()) && hadSpeechInCurrentListen) {
                    feedbackController.commandFailed()
                }
                if (wasListeningForCommand && hadSpeechInCurrentListen && shouldUseOwnerGate()) {
                    extendCommandWindowWithinDeadline(COMMAND_RETRY_GRACE_MS)
                }
                if (!forceLocalCommandOnce) {
                    finishLatency(
                        if (hadSpeechInCurrentListen) "speech_error_retry" else "speech_idle_retry",
                        "code=$error delayMs=$COMMAND_RETRY_DELAY_MS",
                    )
                }
                if (wasListeningForCommand || isCommandWindowOpen()) COMMAND_RETRY_DELAY_MS else OWNER_VERIFY_RETRY_MS
            }
            else -> {
                finishLatency("speech_error_retry", "code=$error delayMs=$RECOGNIZER_RESET_RETRY_DELAY_MS")
                RECOGNIZER_RESET_RETRY_DELAY_MS
            }
        }
        scheduleNextCapture(delay)
    }

    private fun shouldResetRecognizerAfterError(error: Int): Boolean {
        return error == SpeechRecognizer.ERROR_CLIENT ||
            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
    }

    private fun shouldFallbackToLocalCommand(error: Int, hadSpeechInCurrentListen: Boolean): Boolean {
        if (!hadSpeechInCurrentListen) return false

        return error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
    }

    override fun onResults(results: Bundle?) {
        if (shouldSuppressCancelledRecognizerCallback(clear = true)) return

        val finalResults = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
        markLatency("final_results", "count=${finalResults.size} first=${finalResults.firstOrNull().orEmpty()}")
        Log.d(TAG, "Final speech results received")
        val wasIdleAndroidWake = idleAndroidWakeListening
        if (wasIdleAndroidWake) {
            val activationText = finalResults.firstOrNull(CommandInterpreter::isActivationWake)
            if (activationText != null && handleAndroidActivationRecognized(activationText, "final")) {
                return
            }

            val snapshot = idleWakeAudioBuffer.stopAndSnapshot()
            listening = false
            idleAndroidWakeListening = false
            androidListenAfterLocal = false
            speechStartedInCurrentListen = false
            currentListeningAllowsCommandWithoutWake = false
            handler.removeCallbacks(listeningTimeout)
            markLatency(
                "android_activation_audio_snapshot",
                "source=${snapshot.source} samplesMs=${snapshot.durationMs} " +
                    "peakRms=${snapshot.peakRms} meanRms=${snapshot.meanRms}",
            )
            saveAndroidActivationSnapshot(
                snapshot = snapshot,
                endpoint = "android_stt_wake_no_wake",
                text = finalResults.firstOrNull().orEmpty(),
            )
            if (
                startAndroidActivationReplay(
                    snapshot = snapshot,
                    endpoint = "android_stt_wake_no_wake",
                    reason = "final_no_wake",
                    text = finalResults.firstOrNull().orEmpty(),
                )
            ) {
                return
            }
            finishLatency(
                "android_activation_no_wake",
                "count=${finalResults.size} first=${finalResults.firstOrNull().orEmpty()}",
            )
            scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
            return
        }

        listening = false
        androidListenAfterLocal = false
        speechStartedInCurrentListen = false
        handler.removeCallbacks(listeningTimeout)
        val wasListeningForCommand = currentListeningAllowsCommandWithoutWake
        if (completePartialCommandRun("final result")) return

        val outcome = handleSpeech(results)
        currentListeningAllowsCommandWithoutWake = false
        if (outcome == SpeechOutcome.COMMAND_RUN) {
            completeCommandRun(keepCommandWindowOpen = false)
        } else if (outcome == SpeechOutcome.COMMAND_RUN_KEEP_WINDOW) {
            completeCommandRun(keepCommandWindowOpen = true)
        } else if (outcome == SpeechOutcome.ACTIVATION) {
            partialActivationHandled = false
            finishLatency("activation_complete")
            scheduleListening(OWNER_READY_LISTEN_DELAY_MS)
        } else {
            if (completePartialActivation("final no command")) return
            if (wasListeningForCommand && isCommandWindowExpired()) {
                closeCommandWindow(playFeedback = false)
                finishLatency("command_window_expired_after_final_no_command")
                stopAfterCommandWindow("final_no_command_expired")
                return
            }
            if (wasListeningForCommand || isCommandWindowOpen()) {
                feedbackController.commandFailed()
            }
            if (wasListeningForCommand && shouldUseOwnerGate()) {
                extendCommandWindowWithinDeadline(COMMAND_RETRY_GRACE_MS)
            }
            finishLatency("no_command_retry", "commandWindow=$wasListeningForCommand")
            scheduleNextCapture(if (wasListeningForCommand || isCommandWindowOpen()) COMMAND_RETRY_DELAY_MS else 250L)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (shouldSuppressCancelledRecognizerCallback()) return

        Log.d(TAG, "Partial speech results received")
        val results = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
        if (results.isNotEmpty()) speechStartedInCurrentListen = true
        if (results.isNotEmpty()) {
            markLatency("partial_results", "count=${results.size} first=${results.first()}")
        }
        if (results.isNotEmpty()) Log.d(TAG, "Partial speech results: $results")
        if (runFastPartialCommand(results)) return
        runFastPartialActivation(results)
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun shouldSuppressCancelledRecognizerCallback(clear: Boolean = false): Boolean {
        if (!suppressCancelledRecognizerCallbacks || listening) return false
        if (clear) suppressCancelledRecognizerCallbacks = false
        Log.d(TAG, "Suppressed recognizer callback after owner audio command")
        return true
    }

    private fun runFastPartialCommand(results: List<String>): Boolean {
        if (partialCommandHandled || results.isEmpty()) return false

        for (candidate in results) {
            val command = CommandInterpreter.parse(
                text = candidate,
                requireWakeWord = true,
            ) ?: continue
            if (command !in JarvisCommandExecutor.FAST_PARTIAL_COMMANDS) continue

            markLatency("command_parsed", "source=partial command=$command text=$candidate")
            Log.d(TAG, "Parsed fast partial command: $command from '$candidate'")
            partialCommandHandled = true
            partialCommandKeepsWindowOpen = runCommand(command, "partial").keepsCommandWindowOpen
            notificationController.update("명령 처리 중입니다.")
            feedbackController.commandProcessing()
            handler.removeCallbacks(listeningTimeout)
            handler.removeCallbacks(partialCommandFinalize)
            handler.postDelayed(partialCommandFinalize, PARTIAL_COMMAND_FINALIZE_TIMEOUT_MS)
            runCatching { recognizer?.cancel() }
            return true
        }
        return false
    }

    private fun runFastPartialActivation(results: List<String>): Boolean {
        if (partialActivationHandled || partialCommandHandled || results.isEmpty()) return false
        if (idleAndroidWakeListening) {
            val activationText = results.firstOrNull(CommandInterpreter::isActivationWake) ?: return false
            return handleAndroidActivationRecognized(activationText, "partial")
        }
        return false
    }

    private fun completePartialActivation(reason: String): Boolean {
        if (!partialActivationHandled) return false

        partialActivationHandled = false
        currentListeningAllowsCommandWithoutWake = false
        markLatency("partial_activation_complete", "reason=$reason")
        Log.d(TAG, "Completing partial activation: $reason")
        finishLatency("activation_complete", "source=partial reason=$reason")
        scheduleListening(OWNER_READY_LISTEN_DELAY_MS)
        return true
    }

    private fun listeningTimeoutMs(): Long {
        return if (currentListeningAllowsCommandWithoutWake) {
            COMMAND_LISTENING_TIMEOUT_MS
        } else {
            DEFAULT_LISTENING_TIMEOUT_MS
        }
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val TAG = "JarvisVoiceService"
        private const val DEFAULT_LISTENING_TIMEOUT_MS = 7000L
        private const val IDLE_ANDROID_WAKE_TIMEOUT_MS = 12000L
        private const val IDLE_WAKE_AUDIO_BUFFER_MS = 4500L
        private const val ANDROID_WAKE_START_ERROR_FALLBACK_THRESHOLD = 3
        private const val ANDROID_WAKE_FALLBACK_DISABLE_MS = 60000L
        private const val COMMAND_LISTENING_TIMEOUT_MS = 12000L
        private const val COMMAND_SESSION_AUTH_WINDOW_MS = 30000L
        private const val COMMAND_RETRY_GRACE_MS = 2000L
        private const val LOCAL_COMMAND_TIMEOUT_MS = 6000L
        private const val LOCAL_ACTIVATION_TIMEOUT_MS = 60000L
        private const val LOCAL_FALLBACK_AUTH_EXTENSION_MS = 6000L
        private const val LOCAL_ANDROID_FALLBACK_MIN_SPEECH_MS = 360L
        private const val OWNER_VERIFY_AUDIO_MS = 1800L
        private const val OWNER_ACTIVATION_AUDIO_MS = 3200L
        private const val OWNER_VERIFY_INTERVAL_MS = 60L
        private const val OWNER_POST_ACCEPT_AUDIO_MS = 900L
        private const val OWNER_VERIFY_RETRY_MS = 200L
        private const val ACTIVATION_CAPTURE_DIR = "jarvis-activation-attempts"
        private const val MAX_ACTIVATION_CAPTURE_FILES = 240
        private const val DEFAULT_RETRY_DELAY_MS = 300L
        private const val RECOGNIZER_RESET_RETRY_DELAY_MS = 1000L
        private const val OWNER_READY_LISTEN_DELAY_MS = 0L
        private const val COMMAND_CHAIN_LISTEN_DELAY_MS = 80L
        private const val FALLBACK_LISTEN_DELAY_MS = 0L
        private const val DEBUG_COMMAND_WINDOW_LISTEN_DELAY_MS = 300L
        private const val COMMAND_RETRY_DELAY_MS = 25L
        private const val ACTIVE_SPEECH_DEADLINE_RECHECK_MS = 500L
        private const val ACTIVE_SPEECH_DEADLINE_GRACE_MS = 3500L
        private const val PARTIAL_COMMAND_FINALIZE_TIMEOUT_MS = 100L
        private const val SERVICE_STOP_DELAY_MS = 200L
        private const val COMMAND_WINDOW_LISTEN_DELAY_MS = 80L
        const val EXTRA_DEBUG_COMMAND_WINDOW_MS = "debug_command_window_ms"
        const val EXTRA_DEBUG_REQUEST_ID = "debug_request_id"
        const val EXTRA_DEBUG_COMMAND = "debug_command"
        private const val MIN_COMMAND_WINDOW_MS = 1000L
        private const val MAX_COMMAND_WINDOW_MS = 60000L
        private const val DEBUG_MIN_COMMAND_WINDOW_MS = MIN_COMMAND_WINDOW_MS
        private const val DEBUG_MAX_COMMAND_WINDOW_MS = MAX_COMMAND_WINDOW_MS
        private const val DEFAULT_NOTIFICATION_TEXT = "Jarvis 명령을 듣는 중입니다."
        const val ACTION_STOP_SERVICE = "com.personal.jarvis.action.STOP_SERVICE"
        private val AIAI_RECOGNITION_SERVICE = ComponentName(
            "com.google.android.as",
            "com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService",
        )
    }

    private enum class SpeechOutcome {
        COMMAND_RUN,
        COMMAND_RUN_KEEP_WINDOW,
        ACTIVATION,
        NO_COMMAND,
    }
}
