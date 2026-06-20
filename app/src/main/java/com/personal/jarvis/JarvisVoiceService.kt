package com.personal.jarvis

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.util.Log

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
            onAuthorized = {
                if (!destroyed) {
                    openCommandWindow(OWNER_AUTH_WINDOW_MS)
                    signalCommandReady()
                    scheduleListening(COMMAND_READY_LISTEN_DELAY_MS)
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
    private val feedbackController by lazy {
        JarvisFeedbackController(applicationContext, handler)
    }
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var destroyed = false
    private var currentListeningAllowsCommandWithoutWake = false
    private var partialCommandHandled = false
    private var partialCommandKeepsWindowOpen = false
    private var forceLocalCommandOnce = false
    private var commandWindowDeadlineAt = 0L
    private var speechStartedInCurrentListen = false
    private val listeningTimeout: Runnable = Runnable {
        if (!listening || destroyed) return@Runnable
        Log.w(TAG, "Listening timed out; restarting recognizer")
        val wasListeningForCommand = currentListeningAllowsCommandWithoutWake
        listening = false
        currentListeningAllowsCommandWithoutWake = false
        speechStartedInCurrentListen = false
        if (localCommandSession.isActive) {
            localCommandSession.stop()
        } else {
            runCatching { recognizer?.cancel() }
        }
        if (wasListeningForCommand && isCommandWindowExpired()) {
            closeCommandWindow(playFeedback = false)
            scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
            return@Runnable
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
            Log.d(TAG, "Command window deadline reached while speech is active; waiting for recognizer result")
            handler.postDelayed(commandWindowTimeout, ACTIVE_SPEECH_DEADLINE_RECHECK_MS)
            return@Runnable
        }

        Log.d(TAG, "Command window deadline reached; returning to owner gate")
        listening = false
        currentListeningAllowsCommandWithoutWake = false
        partialCommandHandled = false
        partialCommandKeepsWindowOpen = false
        speechStartedInCurrentListen = false
        handler.removeCallbacks(listeningTimeout)
        handler.removeCallbacks(partialCommandFinalize)
        if (localCommandSession.isActive) {
            localCommandSession.stop()
        } else {
            runCatching { recognizer?.cancel() }
        }
        closeCommandWindow(playFeedback = false)
        scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
    }
    private val partialCommandFinalize: Runnable = Runnable {
        if (destroyed || !partialCommandHandled) return@Runnable
        Log.d(TAG, "Finalizing partial command without recognizer callback")
        listening = false
        speechStartedInCurrentListen = false
        handler.removeCallbacks(listeningTimeout)
        resetRecognizer()
        completePartialCommandRun("partial finalize timeout")
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        notificationController.createChannel()
        notificationController.startForeground()
        createRecognizer()
        LocalCommandRecognizer.warmUp(applicationContext)
        scheduleNextCapture(300)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!listening && !ownerVoiceGate.isVerifying) scheduleNextCapture(150)
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        ownerVoiceGate.stop()
        localCommandSession.stop()
        recognizer?.destroy()
        recognizer = null
        JarvisStateBus.send(applicationContext, JarvisVoiceState.IDLE)
        feedbackController.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "Speech recognition is not available; keeping service alive for owner gate/local fallback")
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
        }
        Log.d(TAG, "Speech recognizer created")
    }

    private fun resetRecognizer() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        createRecognizer()
    }

    private fun scheduleListening(delayMs: Long) {
        if (destroyed) return
        handler.postDelayed({ startListening() }, delayMs)
    }

    private fun scheduleNextCapture(delayMs: Long) {
        if (destroyed) return
        handler.postDelayed({
            if (shouldUseOwnerGate() && !isCommandWindowOpen()) {
                notificationController.reset()
                feedbackController.showOwnerVerifying()
                startOwnerVerification()
            } else {
                startListening()
            }
        }, delayMs)
    }

    private fun startListening() {
        if (destroyed || listening || ownerVoiceGate.isVerifying) return

        val commandWindowOpen = isCommandWindowOpen()
        if (commandWindowOpen && forceLocalCommandOnce && localCommandSession.canStart()) {
            forceLocalCommandOnce = false
            startLocalCommandListening()
            return
        }
        if (recognizer == null) {
            if (commandWindowOpen && localCommandSession.canStart()) {
                startLocalCommandListening()
            }
            return
        }

        forceLocalCommandOnce = false
        val intent = SpeechRecognitionIntentFactory.create(this, commandWindowOpen)

        try {
            listening = true
            currentListeningAllowsCommandWithoutWake = commandWindowOpen
            partialCommandHandled = false
            partialCommandKeepsWindowOpen = false
            speechStartedInCurrentListen = false
            recognizer?.startListening(intent)
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
            partialCommandHandled = false
            partialCommandKeepsWindowOpen = false
            speechStartedInCurrentListen = false
            handler.removeCallbacks(listeningTimeout)
            Log.w(TAG, "Failed to start listening")
            if (commandWindowOpen) feedbackController.commandFailed()
            scheduleNextCapture(1000)
        }
    }

    private fun startLocalCommandListening() {
        if (destroyed || listening || ownerVoiceGate.isVerifying || localCommandSession.isActive) return

        listening = true
        currentListeningAllowsCommandWithoutWake = true
        partialCommandHandled = false
        partialCommandKeepsWindowOpen = false
        speechStartedInCurrentListen = false
        feedbackController.commandListening()
        handler.removeCallbacks(listeningTimeout)
        handler.postDelayed(listeningTimeout, LOCAL_COMMAND_TIMEOUT_MS + 500L)

        localCommandSession.start(
            timeoutMs = LOCAL_COMMAND_TIMEOUT_MS,
            onText = { text ->
                if (text.isNotBlank()) speechStartedInCurrentListen = true
                Log.d(TAG, "Local command partial text: $text")
            },
            onComplete = ::handleLocalCommandOutcome,
        )
        Log.d(TAG, "Local command listening started")
    }

    private fun handleLocalCommandOutcome(outcome: LocalCommandSession.Outcome) {
        if (destroyed) return

        val wasListeningForCommand = currentListeningAllowsCommandWithoutWake
        listening = false
        currentListeningAllowsCommandWithoutWake = false
        speechStartedInCurrentListen = false
        handler.removeCallbacks(listeningTimeout)

        val result = outcome.result
        val command = result?.command
        when {
            command != null -> {
                Log.d(
                    TAG,
                    "Parsed local command: $command from '${result.text}' in ${result.elapsedMs}ms",
                )
                completeCommandRun(commandExecutor.run(command).keepsCommandWindowOpen)
            }
            outcome.unavailable -> {
                Log.w(TAG, "Local command recognizer unavailable")
                feedbackController.commandFailed()
                scheduleNextCapture(COMMAND_RETRY_DELAY_MS)
            }
            else -> {
                Log.d(
                    TAG,
                    "Local command finished without command: " +
                        "text='${result?.text.orEmpty()}', elapsed=${result?.elapsedMs ?: 0L}ms",
                )
                if (wasListeningForCommand && isCommandWindowExpired()) {
                    closeCommandWindow(playFeedback = false)
                    scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
                    return
                }
                feedbackController.commandFailed()
                if (wasListeningForCommand && shouldUseOwnerGate()) {
                    extendCommandWindowWithinDeadline(COMMAND_RETRY_GRACE_MS)
                }
                scheduleNextCapture(COMMAND_RETRY_DELAY_MS)
            }
        }
    }

    private fun startOwnerVerification() {
        if (destroyed || ownerVoiceGate.isVerifying || listening) return
        ownerVoiceGate.startVerification(
            audioWindowMs = OWNER_VERIFY_AUDIO_MS,
            verificationIntervalMs = OWNER_VERIFY_INTERVAL_MS,
            authorizationWindowMs = OWNER_AUTH_WINDOW_MS,
        )
    }

    private fun shouldUseOwnerGate(): Boolean = ownerVoiceGate.isConfigured()

    private fun isOwnerAuthorized(): Boolean {
        return ownerVoiceGate.isAuthorized()
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
        ownerVoiceGate.authorizeFor(durationMs)
        handler.removeCallbacks(commandWindowTimeout)
        handler.postDelayed(commandWindowTimeout, durationMs)
    }

    private fun closeCommandWindow(playFeedback: Boolean) {
        commandWindowDeadlineAt = 0L
        forceLocalCommandOnce = false
        ownerVoiceGate.clearAuthorization()
        handler.removeCallbacks(commandWindowTimeout)
        notificationController.reset()
        if (playFeedback) {
            feedbackController.commandWindowClosed()
        } else {
            feedbackController.showOwnerVerifying()
        }
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

        val allowCommandWithoutWake = currentListeningAllowsCommandWithoutWake ||
            isCommandWindowOpen()
        for (candidate in results) {
            val command = CommandInterpreter.parse(
                text = candidate,
                requireWakeWord = !allowCommandWithoutWake,
            ) ?: continue
            Log.d(TAG, "Parsed command: $command from '$candidate'")
            return if (commandExecutor.run(command).keepsCommandWindowOpen) {
                SpeechOutcome.COMMAND_RUN_KEEP_WINDOW
            } else {
                SpeechOutcome.COMMAND_RUN
            }
        }

        if (results.any(CommandInterpreter::isWakeOnly)) {
            Log.d(TAG, "Wake phrase recognized; keeping command window open")
            openCommandWindow(OWNER_AUTH_WINDOW_MS)
            signalCommandReady()
            return SpeechOutcome.WAKE_ONLY
        }

        return SpeechOutcome.NO_COMMAND
    }

    private fun completeCommandRun(keepCommandWindowOpen: Boolean) {
        if (destroyed) return
        if (keepCommandWindowOpen) {
            openCommandWindow(CAMERA_SESSION_AUTH_WINDOW_MS)
            notificationController.update("명령 처리됨. 다음 명령을 말하세요.")
            feedbackController.commandHandled()
            scheduleListening(COMMAND_READY_LISTEN_DELAY_MS)
        } else {
            closeCommandWindow(playFeedback = true)
            scheduleNextCapture(250)
        }
    }

    private fun completePartialCommandRun(reason: String): Boolean {
        if (!partialCommandHandled) return false

        val keepCommandWindowOpen = partialCommandKeepsWindowOpen
        partialCommandHandled = false
        partialCommandKeepsWindowOpen = false
        currentListeningAllowsCommandWithoutWake = false
        handler.removeCallbacks(partialCommandFinalize)
        Log.d(TAG, "Completing partial command run: $reason")
        completeCommandRun(keepCommandWindowOpen)
        return true
    }

    private fun signalCommandReady() {
        notificationController.update("소유자 확인됨. 명령을 말하세요.")
        feedbackController.commandReady()
    }

    override fun onReadyForSpeech(params: Bundle?) {
        Log.d(TAG, "Ready for speech")
    }

    override fun onBeginningOfSpeech() {
        speechStartedInCurrentListen = true
        Log.d(TAG, "Beginning of speech")
    }

    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() {
        listening = false
        handler.removeCallbacks(listeningTimeout)
        Log.d(TAG, "End of speech")
    }

    override fun onError(error: Int) {
        val wasListeningForCommand = currentListeningAllowsCommandWithoutWake
        listening = false
        speechStartedInCurrentListen = false
        handler.removeCallbacks(listeningTimeout)
        Log.w(TAG, "Speech error: $error")
        if (completePartialCommandRun("speech error $error")) return

        currentListeningAllowsCommandWithoutWake = false
        if (wasListeningForCommand && isCommandWindowExpired()) {
            closeCommandWindow(playFeedback = false)
            scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
            return
        }
        if (wasListeningForCommand && localCommandSession.canStart()) {
            Log.w(TAG, "Speech recognizer failed in command window; falling back to local command recognizer")
            if (shouldUseOwnerGate()) {
                extendCommandWindowWithinDeadline(LOCAL_FALLBACK_AUTH_EXTENSION_MS)
                if (!isCommandWindowOpen()) {
                    scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
                    return
                }
            }
            forceLocalCommandOnce = true
            scheduleListening(COMMAND_READY_LISTEN_DELAY_MS)
            return
        }

        val delay = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                if (wasListeningForCommand || isCommandWindowOpen()) {
                    feedbackController.commandFailed()
                }
                if (wasListeningForCommand && shouldUseOwnerGate()) {
                    extendCommandWindowWithinDeadline(COMMAND_RETRY_GRACE_MS)
                }
                if (wasListeningForCommand || isCommandWindowOpen()) COMMAND_RETRY_DELAY_MS else OWNER_VERIFY_RETRY_MS
            }
            else -> 1000L
        }
        scheduleNextCapture(delay)
    }

    override fun onResults(results: Bundle?) {
        listening = false
        speechStartedInCurrentListen = false
        handler.removeCallbacks(listeningTimeout)
        Log.d(TAG, "Final speech results received")
        val wasListeningForCommand = currentListeningAllowsCommandWithoutWake
        if (completePartialCommandRun("final result")) return

        val outcome = handleSpeech(results)
        currentListeningAllowsCommandWithoutWake = false
        if (outcome == SpeechOutcome.COMMAND_RUN) {
            completeCommandRun(keepCommandWindowOpen = false)
        } else if (outcome == SpeechOutcome.COMMAND_RUN_KEEP_WINDOW) {
            completeCommandRun(keepCommandWindowOpen = true)
        } else if (outcome == SpeechOutcome.WAKE_ONLY) {
            scheduleListening(COMMAND_READY_LISTEN_DELAY_MS)
        } else {
            if (wasListeningForCommand && isCommandWindowExpired()) {
                closeCommandWindow(playFeedback = false)
                scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
                return
            }
            if (wasListeningForCommand || isCommandWindowOpen()) {
                feedbackController.commandFailed()
            }
            if (wasListeningForCommand && shouldUseOwnerGate()) {
                extendCommandWindowWithinDeadline(COMMAND_RETRY_GRACE_MS)
            }
            scheduleNextCapture(if (wasListeningForCommand || isCommandWindowOpen()) COMMAND_RETRY_DELAY_MS else 250L)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        Log.d(TAG, "Partial speech results received")
        val results = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
        if (results.isNotEmpty()) speechStartedInCurrentListen = true
        if (results.isNotEmpty()) Log.d(TAG, "Partial speech results: $results")
        runFastPartialCommand(results)
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun runFastPartialCommand(results: List<String>): Boolean {
        if (partialCommandHandled || results.isEmpty()) return false

        val allowCommandWithoutWake = currentListeningAllowsCommandWithoutWake ||
            isCommandWindowOpen()
        for (candidate in results) {
            val command = CommandInterpreter.parse(
                text = candidate,
                requireWakeWord = !allowCommandWithoutWake,
            ) ?: continue
            if (command !in JarvisCommandExecutor.FAST_PARTIAL_COMMANDS) continue

            Log.d(TAG, "Parsed fast partial command: $command from '$candidate'")
            partialCommandHandled = true
            partialCommandKeepsWindowOpen = commandExecutor.run(command).keepsCommandWindowOpen
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
        private const val COMMAND_LISTENING_TIMEOUT_MS = 12000L
        private const val OWNER_AUTH_WINDOW_MS = 12000L
        private const val CAMERA_SESSION_AUTH_WINDOW_MS = 30000L
        private const val COMMAND_RETRY_GRACE_MS = 2000L
        private const val LOCAL_COMMAND_TIMEOUT_MS = 1600L
        private const val LOCAL_FALLBACK_AUTH_EXTENSION_MS = 6000L
        private const val OWNER_VERIFY_AUDIO_MS = 1600L
        private const val OWNER_VERIFY_INTERVAL_MS = 180L
        private const val OWNER_VERIFY_RETRY_MS = 200L
        private const val DEFAULT_RETRY_DELAY_MS = 300L
        private const val COMMAND_READY_LISTEN_DELAY_MS = 0L
        private const val COMMAND_RETRY_DELAY_MS = 25L
        private const val ACTIVE_SPEECH_DEADLINE_RECHECK_MS = 500L
        private const val PARTIAL_COMMAND_FINALIZE_TIMEOUT_MS = 100L
        private const val DEFAULT_NOTIFICATION_TEXT = "소유자 목소리 확인 후 음성 명령을 듣습니다."
    }

    private enum class SpeechOutcome {
        COMMAND_RUN,
        COMMAND_RUN_KEEP_WINDOW,
        WAKE_ONLY,
        NO_COMMAND,
    }
}
