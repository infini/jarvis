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
                    startLatencyTrace("owner_authorized", "windowMs=$OWNER_AUTH_WINDOW_MS")
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
    private var latencyTrace: JarvisLatencyTrace? = null
    private val listeningTimeout: Runnable = Runnable {
        if (!listening || destroyed) return@Runnable
        markLatency("listen_timeout", "commandWindow=$currentListeningAllowsCommandWithoutWake")
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
            finishLatency("command_window_expired_on_listen_timeout")
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
            markLatency("command_window_deadline_waiting_for_speech")
            handler.postDelayed(commandWindowTimeout, ACTIVE_SPEECH_DEADLINE_RECHECK_MS)
            return@Runnable
        }

        Log.d(TAG, "Command window deadline reached; returning to owner gate")
        finishLatency("command_window_timeout")
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
            ensureLatencyTrace("listen_cycle_start", "engine=local_asr forced=true")
                .mark("fallback_listen_requested")
            forceLocalCommandOnce = false
            startLocalCommandListening()
            return
        }
        if (recognizer == null) {
            if (commandWindowOpen && localCommandSession.canStart()) {
                ensureLatencyTrace("listen_cycle_start", "engine=local_asr recognizer=null")
                    .mark("fallback_listen_requested")
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
                ensureLatencyTrace("listen_cycle_start", "engine=android_stt")
                    .mark("listen_start", "engine=android_stt timeoutMs=${listeningTimeoutMs()}")
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
            partialCommandHandled = false
            partialCommandKeepsWindowOpen = false
            speechStartedInCurrentListen = false
            handler.removeCallbacks(listeningTimeout)
            Log.w(TAG, "Failed to start listening")
            if (commandWindowOpen) feedbackController.commandFailed()
            if (commandWindowOpen) finishLatency("listen_start_failed", "engine=android_stt")
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
        ensureLatencyTrace("listen_cycle_start", "engine=local_asr")
            .mark("listen_start", "engine=local_asr timeoutMs=$LOCAL_COMMAND_TIMEOUT_MS")
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
                markLatency("command_parsed", "source=local command=$command text=${result.text}")
                Log.d(
                    TAG,
                    "Parsed local command: $command from '${result.text}' in ${result.elapsedMs}ms",
                )
                completeCommandRun(runCommand(command, "local").keepsCommandWindowOpen)
            }
            outcome.unavailable -> {
                Log.w(TAG, "Local command recognizer unavailable")
                feedbackController.commandFailed()
                finishLatency("local_asr_unavailable")
                scheduleNextCapture(COMMAND_RETRY_DELAY_MS)
            }
            else -> {
                markLatency(
                    "local_no_command",
                    "text=${result?.text.orEmpty()} elapsedMs=${result?.elapsedMs ?: 0L}",
                )
                Log.d(
                    TAG,
                    "Local command finished without command: " +
                        "text='${result?.text.orEmpty()}', elapsed=${result?.elapsedMs ?: 0L}ms",
                )
                if (wasListeningForCommand && isCommandWindowExpired()) {
                    closeCommandWindow(playFeedback = false)
                    finishLatency("command_window_expired_after_local_no_command")
                    scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
                    return
                }
                feedbackController.commandFailed()
                if (wasListeningForCommand && shouldUseOwnerGate()) {
                    extendCommandWindowWithinDeadline(COMMAND_RETRY_GRACE_MS)
                }
                finishLatency("local_no_command_retry")
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
            markLatency("command_parsed", "source=final command=$command text=$candidate")
            Log.d(TAG, "Parsed command: $command from '$candidate'")
            return if (runCommand(command, "final").keepsCommandWindowOpen) {
                SpeechOutcome.COMMAND_RUN_KEEP_WINDOW
            } else {
                SpeechOutcome.COMMAND_RUN
            }
        }

        if (results.any(CommandInterpreter::isWakeOnly)) {
            Log.d(TAG, "Wake phrase recognized; keeping command window open")
            markLatency("wake_only")
            openCommandWindow(OWNER_AUTH_WINDOW_MS)
            signalCommandReady()
            return SpeechOutcome.WAKE_ONLY
        }

        markLatency("parse_no_command", "count=${results.size}")
        return SpeechOutcome.NO_COMMAND
    }

    private fun completeCommandRun(keepCommandWindowOpen: Boolean) {
        if (destroyed) return
        if (keepCommandWindowOpen) {
            openCommandWindow(CAMERA_SESSION_AUTH_WINDOW_MS)
            notificationController.update("명령 처리됨. 다음 명령을 말하세요.")
            feedbackController.commandHandled()
            finishLatency("command_complete", "keepWindow=true nextWindowMs=$CAMERA_SESSION_AUTH_WINDOW_MS")
            scheduleListening(COMMAND_READY_LISTEN_DELAY_MS)
        } else {
            closeCommandWindow(playFeedback = true)
            finishLatency("command_complete", "keepWindow=false")
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
        markLatency("partial_command_complete", "reason=$reason keepWindow=$keepCommandWindowOpen")
        Log.d(TAG, "Completing partial command run: $reason")
        completeCommandRun(keepCommandWindowOpen)
        return true
    }

    private fun signalCommandReady() {
        notificationController.update("소유자 확인됨. 명령을 말하세요.")
        feedbackController.commandReady()
    }

    override fun onReadyForSpeech(params: Bundle?) {
        markLatency("ready_for_speech")
        Log.d(TAG, "Ready for speech")
    }

    override fun onBeginningOfSpeech() {
        speechStartedInCurrentListen = true
        markLatency("speech_begin")
        Log.d(TAG, "Beginning of speech")
    }

    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() {
        listening = false
        handler.removeCallbacks(listeningTimeout)
        markLatency("speech_end")
        Log.d(TAG, "End of speech")
    }

    override fun onError(error: Int) {
        val wasListeningForCommand = currentListeningAllowsCommandWithoutWake
        listening = false
        speechStartedInCurrentListen = false
        handler.removeCallbacks(listeningTimeout)
        markLatency("speech_error", "code=$error commandWindow=$wasListeningForCommand")
        Log.w(TAG, "Speech error: $error")
        if (completePartialCommandRun("speech error $error")) return

        currentListeningAllowsCommandWithoutWake = false
        if (wasListeningForCommand && isCommandWindowExpired()) {
            closeCommandWindow(playFeedback = false)
            finishLatency("command_window_expired_after_speech_error", "code=$error")
            scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
            return
        }
        if (wasListeningForCommand && localCommandSession.canStart()) {
            Log.w(TAG, "Speech recognizer failed in command window; falling back to local command recognizer")
            markLatency("fallback_to_local", "reason=speech_error code=$error")
            if (shouldUseOwnerGate()) {
                extendCommandWindowWithinDeadline(LOCAL_FALLBACK_AUTH_EXTENSION_MS)
                if (!isCommandWindowOpen()) {
                    finishLatency("fallback_blocked_by_deadline", "code=$error")
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
                if (!forceLocalCommandOnce) {
                    finishLatency("speech_error_retry", "code=$error delayMs=$COMMAND_RETRY_DELAY_MS")
                }
                if (wasListeningForCommand || isCommandWindowOpen()) COMMAND_RETRY_DELAY_MS else OWNER_VERIFY_RETRY_MS
            }
            else -> {
                finishLatency("speech_error_retry", "code=$error delayMs=1000")
                1000L
            }
        }
        scheduleNextCapture(delay)
    }

    override fun onResults(results: Bundle?) {
        listening = false
        speechStartedInCurrentListen = false
        handler.removeCallbacks(listeningTimeout)
        val finalResults = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
        markLatency("final_results", "count=${finalResults.size} first=${finalResults.firstOrNull().orEmpty()}")
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
            finishLatency("wake_only_complete")
            scheduleListening(COMMAND_READY_LISTEN_DELAY_MS)
        } else {
            if (wasListeningForCommand && isCommandWindowExpired()) {
                closeCommandWindow(playFeedback = false)
                finishLatency("command_window_expired_after_final_no_command")
                scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
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
        Log.d(TAG, "Partial speech results received")
        val results = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
        if (results.isNotEmpty()) speechStartedInCurrentListen = true
        if (results.isNotEmpty()) {
            markLatency("partial_results", "count=${results.size} first=${results.first()}")
        }
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
