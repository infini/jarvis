package com.personal.jarvis

import android.app.Service
import android.content.ComponentName
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
            onAuthorized = { match ->
                if (!destroyed) {
                    startLatencyTrace(
                        "owner_authorized",
                        "windowMs=$OWNER_AUTH_WINDOW_MS acceptance=${match.acceptance} " +
                            "speechMs=${match.activeSpeechMs} score=${match.score} " +
                            "ownerElapsedMs=${match.verificationElapsedMs} " +
                            "ownerAttempts=${match.verificationAttempts} " +
                            "peakRms=${match.peakRms} reason=${match.rejectReason ?: "none"}",
                    )
                    openCommandWindow(OWNER_AUTH_WINDOW_MS)
                    commandWindowOpenedByNonStrictOwnerGate =
                        match.acceptance != OwnerVoiceEngine.Acceptance.STRICT
                    commandFeedbackEnabled = shouldPlayCommandReadyFeedback(match)
                    signalCommandReady()
                    if (!startOwnerAudioCommandRecognition(match)) {
                        scheduleListening(OWNER_READY_LISTEN_DELAY_MS)
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
    private val feedbackController by lazy {
        JarvisFeedbackController(applicationContext, handler)
    }
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var destroyed = false
    private var currentListeningAllowsCommandWithoutWake = false
    private var partialCommandHandled = false
    private var partialCommandKeepsWindowOpen = false
    private var partialWakeHandled = false
    private var commandWindowOpenedByNonStrictOwnerGate = false
    private var commandFeedbackEnabled = false
    private var forceLocalCommandOnce = false
    private var forceAndroidCommandOnce = false
    private var androidListenAfterLocal = false
    private var commandWindowDeadlineAt = 0L
    private var commandWindowSpeechGraceUntil = 0L
    private var speechStartedInCurrentListen = false
    private var latencyTrace: JarvisLatencyTrace? = null
    @Volatile private var ownerAudioCommandActive = false
    private var ownerAudioCommandThread: Thread? = null
    private val listeningTimeout: Runnable = Runnable {
        if (!listening || destroyed) return@Runnable
        markLatency("listen_timeout", "commandWindow=$currentListeningAllowsCommandWithoutWake")
        Log.w(TAG, "Listening timed out; restarting recognizer")
        val wasListeningForCommand = currentListeningAllowsCommandWithoutWake
        if (wasListeningForCommand && speechStartedInCurrentListen) {
            markLatency("listen_timeout_waiting_for_speech")
            handler.postDelayed(listeningTimeout, ACTIVE_SPEECH_DEADLINE_RECHECK_MS)
            return@Runnable
        }
        listening = false
        currentListeningAllowsCommandWithoutWake = false
        androidListenAfterLocal = false
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
            listening = false
            currentListeningAllowsCommandWithoutWake = false
            androidListenAfterLocal = false
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
            finishLatency("command_window_expired_after_speech_grace")
            scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
            return@Runnable
        }

        Log.d(TAG, "Command window deadline reached; returning to owner gate")
        finishLatency("command_window_timeout")
        listening = false
        currentListeningAllowsCommandWithoutWake = false
        androidListenAfterLocal = false
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
        Log.d(TAG, "JarvisVoiceService created")
        notificationController.createChannel()
        notificationController.startForeground()
        createRecognizer()
        LocalCommandRecognizer.warmUp(applicationContext)
        scheduleNextCapture(300)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val source = intent?.getStringExtra(JarvisVoiceServiceStarter.EXTRA_START_SOURCE).orEmpty()
        Log.d(TAG, "JarvisVoiceService start command: source=$source flags=$flags startId=$startId")
        if (!listening && !ownerVoiceGate.isVerifying) scheduleNextCapture(150)
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "JarvisVoiceService destroyed")
        destroyed = true
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        ownerVoiceGate.stop()
        stopOwnerAudioCommandRecognition()
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
        val recognitionService = AIAI_RECOGNITION_SERVICE
        var recognitionServiceName = recognitionService.flattenToShortString()
        recognizer = runCatching {
            SpeechRecognizer.createSpeechRecognizer(this, recognitionService)
        }.getOrElse { error ->
            Log.w(
                TAG,
                "AiAi speech recognizer unavailable: " +
                    "${recognitionService.flattenToShortString()} ${error.message}",
            )
            recognitionServiceName = "default"
            SpeechRecognizer.createSpeechRecognizer(this)
        }.also {
            it.setRecognitionListener(this)
        }
        Log.d(TAG, "Speech recognizer created: $recognitionServiceName")
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
        if (destroyed || listening || ownerVoiceGate.isVerifying || ownerAudioCommandActive) return

        val commandWindowOpen = isCommandWindowOpen()
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
            partialWakeHandled = false
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
            partialWakeHandled = false
            speechStartedInCurrentListen = false
            handler.removeCallbacks(listeningTimeout)
            Log.w(TAG, "Failed to start listening")
            if (commandWindowOpen) feedbackController.commandFailed()
            if (commandWindowOpen) finishLatency("listen_start_failed", "engine=android_stt")
            scheduleNextCapture(1000)
        }
    }

    private fun startLocalCommandListening(mode: String) {
        if (destroyed || listening || ownerVoiceGate.isVerifying || localCommandSession.isActive) return

        listening = true
        currentListeningAllowsCommandWithoutWake = true
        androidListenAfterLocal = false
        partialCommandHandled = false
        partialCommandKeepsWindowOpen = false
        partialWakeHandled = false
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
                    scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
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

    private fun startOwnerAudioCommandRecognition(match: OwnerVoiceEngine.Match): Boolean {
        val samples = match.commandSamples ?: return false
        if (!LocalCommandRecognizer.isAvailable(applicationContext)) return false
        if (ownerAudioCommandActive) return false

        ownerAudioCommandActive = true
        val samplesMs = samples.size * 1000L / OwnerVoiceEngine.SAMPLE_RATE_HZ
        markLatency(
            "owner_audio_asr_start",
            "engine=owner_audio_asr samplesMs=$samplesMs acceptance=${match.acceptance}",
        )
        ownerAudioCommandThread = Thread({
            var failed = false
            val result = runCatching {
                LocalCommandRecognizer.recognizeBufferedCommand(
                    context = applicationContext,
                    samples = samples,
                    endpoint = "owner_audio",
                )
            }.onFailure {
                failed = true
                Log.w(TAG, "Owner audio command recognition failed: ${it.message}")
            }.getOrNull()

            handler.post {
                if (!ownerAudioCommandActive) return@post

                ownerAudioCommandActive = false
                ownerAudioCommandThread = null
                handleOwnerAudioCommandOutcome(result, failed)
            }
        }, "JarvisOwnerAudioCommand").also { it.start() }
        return true
    }

    private fun handleOwnerAudioCommandOutcome(
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

        val command = result?.command
        when {
            command != null -> {
                markLatency("command_parsed", "source=owner_audio command=$command text=${result.text}")
                Log.d(
                    TAG,
                    "Parsed owner audio command: $command from '${result.text}' in ${result.elapsedMs}ms",
                )
                completeCommandRun(runCommand(command, "owner_audio").keepsCommandWindowOpen)
            }
            result != null && CommandInterpreter.isWakeOnly(result.text) -> {
                markLatency("owner_audio_wake_only", "text=${result.text}")
                Log.d(TAG, "Parsed owner audio wake phrase from '${result.text}'")
                commandWindowOpenedByNonStrictOwnerGate = false
                commandFeedbackEnabled = true
                signalCommandReady()
                finishLatency("wake_only_complete", "source=owner_audio")
                scheduleListening(ownerReadyDelayAfter(result))
            }
            failed || result?.unavailable == true -> {
                markLatency("owner_audio_asr_unavailable")
                scheduleListening(ownerReadyDelayAfter(result))
            }
            else -> {
                markLatency(
                    "owner_audio_no_command",
                    "text=${result?.text.orEmpty()} elapsedMs=${result?.elapsedMs ?: 0L}",
                )
                scheduleListening(ownerReadyDelayAfter(result))
            }
        }
    }

    private fun ownerReadyDelayAfter(result: LocalCommandRecognizer.Result?): Long {
        val elapsedMs = result?.elapsedMs ?: 0L
        return (OWNER_READY_LISTEN_DELAY_MS - elapsedMs).coerceAtLeast(0L)
    }

    private fun stopOwnerAudioCommandRecognition() {
        ownerAudioCommandActive = false
        ownerAudioCommandThread?.interrupt()
        ownerAudioCommandThread = null
    }

    private fun startAndroidFallbackAfterLocal(reason: String): Boolean {
        if (recognizer == null || isCommandWindowExpired()) return false

        markLatency("fallback_to_android", "reason=$reason")
        forceAndroidCommandOnce = true
        scheduleListening(FALLBACK_LISTEN_DELAY_MS)
        return true
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
        commandWindowSpeechGraceUntil = 0L
        ownerVoiceGate.authorizeFor(durationMs)
        handler.removeCallbacks(commandWindowTimeout)
        handler.postDelayed(commandWindowTimeout, durationMs)
    }

    private fun closeCommandWindow(playFeedback: Boolean) {
        commandWindowDeadlineAt = 0L
        forceLocalCommandOnce = false
        forceAndroidCommandOnce = false
        androidListenAfterLocal = false
        commandWindowOpenedByNonStrictOwnerGate = false
        commandFeedbackEnabled = false
        partialWakeHandled = false
        commandWindowSpeechGraceUntil = 0L
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
            commandWindowOpenedByNonStrictOwnerGate = false
            commandFeedbackEnabled = true
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
            commandWindowOpenedByNonStrictOwnerGate = false
            commandFeedbackEnabled = true
            notificationController.update("명령 처리됨. 다음 명령을 말하세요.")
            feedbackController.commandHandled()
            finishLatency("command_complete", "keepWindow=true nextWindowMs=$CAMERA_SESSION_AUTH_WINDOW_MS")
            scheduleListening(COMMAND_CHAIN_LISTEN_DELAY_MS)
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
        feedbackController.commandListening()
    }

    private fun shouldPlayCommandReadyFeedback(match: OwnerVoiceEngine.Match): Boolean {
        return match.acceptance == OwnerVoiceEngine.Acceptance.STRICT ||
            match.acceptance == OwnerVoiceEngine.Acceptance.NEAR_CONSECUTIVE ||
            match.acceptance == OwnerVoiceEngine.Acceptance.SOFT_WAKE_SINGLE
    }

    override fun onReadyForSpeech(params: Bundle?) {
        markLatency("ready_for_speech")
        if (commandFeedbackEnabled && (currentListeningAllowsCommandWithoutWake || isCommandWindowOpen())) {
            feedbackController.commandReady()
        }
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
        val wasAndroidFallbackAfterLocal = androidListenAfterLocal
        val hadSpeechInCurrentListen = speechStartedInCurrentListen
        listening = false
        androidListenAfterLocal = false
        speechStartedInCurrentListen = false
        handler.removeCallbacks(listeningTimeout)
        markLatency(
            "speech_error",
            "code=$error commandWindow=$wasListeningForCommand " +
                "fallbackAfterLocal=$wasAndroidFallbackAfterLocal " +
                "speechDetected=$hadSpeechInCurrentListen",
        )
        Log.w(TAG, "Speech error: $error")
        if (completePartialCommandRun("speech error $error")) return
        if (completePartialWake("speech error $error")) return

        currentListeningAllowsCommandWithoutWake = false
        if (wasListeningForCommand && isCommandWindowExpired()) {
            closeCommandWindow(playFeedback = false)
            finishLatency("command_window_expired_after_speech_error", "code=$error")
            scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
            return
        }
        if (shouldSuppressIdleNonStrictWake(wasListeningForCommand, error, hadSpeechInCurrentListen)) {
            ownerVoiceGate.suppressNonStrictFor(NON_STRICT_IDLE_SUPPRESS_MS)
            closeCommandWindow(playFeedback = false)
            finishLatency(
                "non_strict_wake_idle_suppressed",
                "code=$error suppressMs=$NON_STRICT_IDLE_SUPPRESS_MS",
            )
            scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
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
                    scheduleNextCapture(OWNER_VERIFY_RETRY_MS)
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
                finishLatency("speech_error_retry", "code=$error delayMs=1000")
                1000L
            }
        }
        scheduleNextCapture(delay)
    }

    private fun shouldFallbackToLocalCommand(error: Int, hadSpeechInCurrentListen: Boolean): Boolean {
        if (!hadSpeechInCurrentListen) return false

        return error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
    }

    private fun shouldSuppressIdleNonStrictWake(
        wasListeningForCommand: Boolean,
        error: Int,
        hadSpeechInCurrentListen: Boolean,
    ): Boolean {
        if (!wasListeningForCommand || hadSpeechInCurrentListen || !commandWindowOpenedByNonStrictOwnerGate) {
            return false
        }

        return error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
    }

    override fun onResults(results: Bundle?) {
        listening = false
        androidListenAfterLocal = false
        speechStartedInCurrentListen = false
        handler.removeCallbacks(listeningTimeout)
        val finalResults = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
        markLatency("final_results", "count=${finalResults.size} first=${finalResults.firstOrNull().orEmpty()}")
        Log.d(TAG, "Final speech results received")
        val wasListeningForCommand = currentListeningAllowsCommandWithoutWake
        if (completePartialCommandRun("final result")) return
        if (completePartialWake("final result")) return

        val outcome = handleSpeech(results)
        currentListeningAllowsCommandWithoutWake = false
        if (outcome == SpeechOutcome.COMMAND_RUN) {
            completeCommandRun(keepCommandWindowOpen = false)
        } else if (outcome == SpeechOutcome.COMMAND_RUN_KEEP_WINDOW) {
            completeCommandRun(keepCommandWindowOpen = true)
        } else if (outcome == SpeechOutcome.WAKE_ONLY) {
            finishLatency("wake_only_complete")
            scheduleListening(OWNER_READY_LISTEN_DELAY_MS)
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
        if (runFastPartialWake(results)) return
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

    private fun runFastPartialWake(results: List<String>): Boolean {
        if (partialWakeHandled || partialCommandHandled || results.isEmpty()) return false
        if (!currentListeningAllowsCommandWithoutWake && !isCommandWindowOpen()) return false
        val wakeText = results.firstOrNull(CommandInterpreter::isWakeOnly) ?: return false

        markLatency("wake_only_partial", "text=$wakeText")
        Log.d(TAG, "Parsed fast partial wake phrase from '$wakeText'")
        partialWakeHandled = true
        commandWindowOpenedByNonStrictOwnerGate = false
        commandFeedbackEnabled = true
        openCommandWindow(OWNER_AUTH_WINDOW_MS)
        signalCommandReady()
        handler.removeCallbacks(listeningTimeout)
        runCatching { recognizer?.cancel() }
        return true
    }

    private fun completePartialWake(reason: String): Boolean {
        if (!partialWakeHandled) return false

        partialWakeHandled = false
        currentListeningAllowsCommandWithoutWake = false
        markLatency("partial_wake_complete", "reason=$reason")
        Log.d(TAG, "Completing partial wake: $reason")
        finishLatency("wake_only_complete", "source=partial reason=$reason")
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
        private const val COMMAND_LISTENING_TIMEOUT_MS = 12000L
        private const val OWNER_AUTH_WINDOW_MS = 12000L
        private const val CAMERA_SESSION_AUTH_WINDOW_MS = 30000L
        private const val COMMAND_RETRY_GRACE_MS = 2000L
        private const val LOCAL_COMMAND_TIMEOUT_MS = 6000L
        private const val LOCAL_FALLBACK_AUTH_EXTENSION_MS = 6000L
        private const val LOCAL_ANDROID_FALLBACK_MIN_SPEECH_MS = 360L
        private const val OWNER_VERIFY_AUDIO_MS = 1200L
        private const val OWNER_VERIFY_INTERVAL_MS = 80L
        private const val OWNER_VERIFY_RETRY_MS = 200L
        private const val DEFAULT_RETRY_DELAY_MS = 300L
        private const val OWNER_READY_LISTEN_DELAY_MS = 0L
        private const val COMMAND_CHAIN_LISTEN_DELAY_MS = 80L
        private const val FALLBACK_LISTEN_DELAY_MS = 0L
        private const val COMMAND_RETRY_DELAY_MS = 25L
        private const val NON_STRICT_IDLE_SUPPRESS_MS = 8000L
        private const val ACTIVE_SPEECH_DEADLINE_RECHECK_MS = 500L
        private const val ACTIVE_SPEECH_DEADLINE_GRACE_MS = 3500L
        private const val PARTIAL_COMMAND_FINALIZE_TIMEOUT_MS = 100L
        private const val DEFAULT_NOTIFICATION_TEXT = "소유자 목소리 확인 후 음성 명령을 듣습니다."
        private val AIAI_RECOGNITION_SERVICE = ComponentName(
            "com.google.android.as",
            "com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService",
        )
    }

    private enum class SpeechOutcome {
        COMMAND_RUN,
        COMMAND_RUN_KEEP_WINDOW,
        WAKE_ONLY,
        NO_COMMAND,
    }
}
