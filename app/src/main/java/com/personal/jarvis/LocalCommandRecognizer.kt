package com.personal.jarvis

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.ArrayDeque
import kotlin.math.sqrt

object LocalCommandRecognizer {
    private const val TAG = "JarvisLocalCommand"
    private const val SAMPLE_RATE_HZ = 16000
    private const val READ_INTERVAL_MS = 40L
    private const val LOCAL_SPEECH_RMS_THRESHOLD = 0.0012f
    private const val LOCAL_MIN_ACTIVE_SPEECH_MS = 160L
    private const val LOCAL_TRAILING_SILENCE_MS = 240L
    private const val LOCAL_EARLY_ENDPOINT_MIN_LISTEN_MS = 560L
    private const val ACTIVATION_SPEECH_RMS_THRESHOLD = 0.00022f
    private const val ACTIVATION_MIN_ACTIVE_SPEECH_MS = 560L
    private const val ACTIVATION_TRAILING_SILENCE_MS = 600L
    private const val ACTIVATION_EARLY_ENDPOINT_MIN_LISTEN_MS = 760L
    private const val ACTIVATION_BUFFERED_CHECK_INTERVAL_MS = 800L
    private const val ACTIVATION_MAX_SEGMENT_MS = 8000L
    private const val ACTIVATION_ROLLING_AUDIO_MS = 3600L
    private const val COMMAND_SAMPLE_FALLBACK_AUDIO_MS = 6000L
    private const val LOCAL_ASR_TARGET_RMS = 0.04f
    private const val LOCAL_ASR_GAIN_MIN_RMS = 0.00008f
    private const val LOCAL_ASR_MAX_GAIN = 30f
    private const val BUFFERED_TAIL_PADDING_SAMPLES = SAMPLE_RATE_HZ / 2
    private const val MODEL_DIR = "sherpa-korean-streaming"
    private const val ENCODER = "$MODEL_DIR/encoder-epoch-99-avg-1.int8.onnx"
    private const val DECODER = "$MODEL_DIR/decoder-epoch-99-avg-1.onnx"
    private const val JOINER = "$MODEL_DIR/joiner-epoch-99-avg-1.int8.onnx"
    private const val TOKENS = "$MODEL_DIR/tokens.txt"
    private const val ACTIVATION_HOTWORDS = "jarvis-activation-hotwords.txt"
    private const val ACOUSTIC_WAKE_ENDPOINT_SUFFIX = "_acoustic_wake"
    private const val ACOUSTIC_WAKE_TEMPLATE_MAX_DISTANCE = 0.22f
    private const val ACOUSTIC_WAKE_TEMPLATE_MIN_PEAK_RMS = 0.006f
    private val REQUIRED_ASSETS = listOf(ENCODER, DECODER, JOINER, TOKENS, ACTIVATION_HOTWORDS)

    private val initLock = Any()
    @Volatile private var recognizer: OnlineRecognizer? = null
    @Volatile private var activationRecognizer: OnlineRecognizer? = null
    @Volatile private var warmUpStarted = false

    data class Result(
        val command: String?,
        val text: String,
        val elapsedMs: Long,
        val unavailable: Boolean = false,
        val endpoint: String = "",
        val activeSpeechMs: Long = 0L,
        val trailingSilenceMs: Long = 0L,
        val peakRms: Float = 0f,
        val meanRms: Float = 0f,
        val asrGain: Float = 1f,
        val candidateStartMs: Long = 0L,
        val candidateDurationMs: Long = 0L,
        val samples: FloatArray = FloatArray(0),
        val sampleMatchCommandId: String? = null,
        val sampleMatchAccepted: Boolean = false,
        val sampleMatchReason: String = "",
        val sampleMatchDistance: Float = Float.POSITIVE_INFINITY,
        val sampleMatchNextDistance: Float = Float.POSITIVE_INFINITY,
        val sampleMatchDurationRatio: Float = 0f,
    )

    data class ActivationResult(
        val text: String,
        val samples: FloatArray,
        val alternateSamples: FloatArray? = null,
        val elapsedMs: Long,
        val unavailable: Boolean = false,
        val endpoint: String = "",
        val activeSpeechMs: Long = 0L,
        val trailingSilenceMs: Long = 0L,
        val peakRms: Float = 0f,
        val meanRms: Float = 0f,
        val asrGain: Float = 1f,
    )

    private data class AudioLevelStats(
        val peakRms: Float,
        val meanRms: Float,
    )

    private data class RecorderSource(
        val source: Int,
        val label: String,
    )

    fun isAvailable(context: Context): Boolean {
        return REQUIRED_ASSETS.all { assetExists(context, it) }
    }

    fun warmUp(context: Context) {
        if (warmUpStarted || !isAvailable(context)) return
        warmUpStarted = true
        Thread({
            runCatching {
                getRecognizer(context.applicationContext)
                getActivationRecognizer(context.applicationContext)
                Log.d(TAG, "Local command recognizer warmed up")
            }.onFailure {
                Log.w(TAG, "Local command recognizer warm-up failed: ${it.message}")
            }
        }, "JarvisLocalCommandWarmup").start()
    }

    fun recognizeBufferedCommand(
        context: Context,
        samples: FloatArray,
        endpoint: String = "buffered_audio",
    ): Result {
        if (!isAvailable(context)) {
            return Result(command = null, text = "", elapsedMs = 0L, unavailable = true)
        }

        return decodeBufferedSamples(
            recognizer = getRecognizer(context.applicationContext),
            samples = samples,
            endpoint = endpoint,
            parseCommand = true,
            logLabel = "Buffered local command",
        )
    }

    fun recognizeBufferedActivation(
        context: Context,
        samples: FloatArray,
        endpoint: String = "buffered_activation",
    ): Result {
        if (!isAvailable(context)) {
            return Result(command = null, text = "", elapsedMs = 0L, unavailable = true)
        }

        val applicationContext = context.applicationContext
        val hotwordResult = decodeBufferedSamples(
            recognizer = getActivationRecognizer(applicationContext),
            samples = samples,
            endpoint = "${endpoint}_hotword",
            parseCommand = false,
            logLabel = "Buffered activation hotword",
        )
        if (CommandInterpreter.isActivationWakeAsrEquivalent(hotwordResult.text)) return hotwordResult

        val greedyResult = decodeBufferedSamples(
            recognizer = getRecognizer(applicationContext),
            samples = samples,
            endpoint = "${endpoint}_greedy_after_hotword",
            parseCommand = false,
            logLabel = "Buffered activation greedy fallback",
        )
        val templateResult = WakePhraseTemplateMatcher.match(applicationContext, samples)
        if (isStrictAcousticWakeTemplate(templateResult)) {
            return Result(
                command = null,
                text = OwnerVoiceStore.OWNER_ENROLLMENT_PHRASE,
                elapsedMs = maxOf(hotwordResult.elapsedMs, greedyResult.elapsedMs),
                endpoint = "${endpoint}$ACOUSTIC_WAKE_ENDPOINT_SUFFIX",
                activeSpeechMs = templateResult.candidateDurationMs,
                trailingSilenceMs = 0L,
                peakRms = templateResult.peakRms,
                meanRms = maxOf(hotwordResult.meanRms, greedyResult.meanRms),
                asrGain = maxOf(hotwordResult.asrGain, greedyResult.asrGain),
                candidateStartMs = templateResult.candidateStartMs,
                candidateDurationMs = templateResult.candidateDurationMs,
                samples = samples,
            )
        }

        return when {
            CommandInterpreter.isActivationWakeAsrEquivalent(greedyResult.text) -> greedyResult
            hotwordResult.text.isNotBlank() -> hotwordResult
            else -> greedyResult
        }
    }

    fun isAcousticWakeFallback(result: Result): Boolean {
        return result.endpoint.endsWith(ACOUSTIC_WAKE_ENDPOINT_SUFFIX)
    }

    fun isAcousticWakeFallback(result: ActivationResult): Boolean {
        return result.endpoint.endsWith(ACOUSTIC_WAKE_ENDPOINT_SUFFIX)
    }

    fun activationSamplesForResult(samples: FloatArray, result: Result): FloatArray {
        if (!isAcousticWakeFallback(result) || result.candidateDurationMs <= 0L) {
            return samples
        }

        val startSample = (SAMPLE_RATE_HZ * result.candidateStartMs / 1000L)
            .toInt()
            .coerceIn(0, samples.size)
        val durationSamples = (SAMPLE_RATE_HZ * result.candidateDurationMs / 1000L)
            .toInt()
            .coerceAtLeast(0)
        val endSample = (startSample + durationSamples).coerceIn(startSample, samples.size)
        return if (endSample > startSample) {
            samples.copyOfRange(startSample, endSample)
        } else {
            samples
        }
    }

    private fun isStrictAcousticWakeTemplate(result: WakePhraseTemplateMatcher.Result): Boolean {
        return result.accepted &&
            result.distance <= ACOUSTIC_WAKE_TEMPLATE_MAX_DISTANCE &&
            result.peakRms >= ACOUSTIC_WAKE_TEMPLATE_MIN_PEAK_RMS
    }

    @SuppressLint("MissingPermission")
    fun listenForActivation(
        context: Context,
        timeoutMs: Long,
        shouldContinue: () -> Boolean,
        onText: (String) -> Unit = {},
        onRejected: (ActivationResult) -> Unit = {},
    ): ActivationResult {
        if (!isAvailable(context)) {
            return ActivationResult(text = "", samples = FloatArray(0), elapsedMs = 0L, unavailable = true)
        }

        val applicationContext = context.applicationContext
        val startedAt = SystemClock.elapsedRealtime()
        val localRecognizer = getActivationRecognizer(applicationContext)
        var stream = localRecognizer.createStream()
        val recorder = createRecorder()
        val readSize = (SAMPLE_RATE_HZ * READ_INTERVAL_MS / 1000L).toInt()
        val rollingSamples = (SAMPLE_RATE_HZ * ACTIVATION_ROLLING_AUDIO_MS / 1000L).toInt()
        val chunks = ArrayDeque<FloatArray>()
        val buffer = ShortArray(readSize)
        var totalSamples = 0
        var segmentStartedAt = startedAt
        var lastText = ""
        var speechSeen = false
        var activeSpeechMs = 0L
        var lastSpeechAtMs = 0L
        var endpoint = "timeout"
        var finalTrailingSilenceMs = 0L
        var peakRms = 0f
        var rmsSum = 0.0
        var rmsFrameCount = 0
        var maxAsrGain = 1f
        var lastBufferedCheckAtMs = 0L

        fun currentSamples(): FloatArray {
            return flattenLastSamples(
                chunks = chunks,
                sampleCount = minOf(totalSamples, rollingSamples),
                totalSamples = totalSamples,
            )
        }

        fun resetSegment(now: Long = SystemClock.elapsedRealtime()) {
            stream.release()
            stream = localRecognizer.createStream()
            segmentStartedAt = now
            lastText = ""
            speechSeen = false
            activeSpeechMs = 0L
            lastSpeechAtMs = 0L
            endpoint = "timeout"
            finalTrailingSilenceMs = 0L
            peakRms = 0f
            rmsSum = 0.0
            rmsFrameCount = 0
            maxAsrGain = 1f
            lastBufferedCheckAtMs = 0L
        }

        fun result(
            text: String,
            endpoint: String,
            elapsedMs: Long = SystemClock.elapsedRealtime() - segmentStartedAt,
        ): ActivationResult {
            val trailing = if (finalTrailingSilenceMs == 0L) {
                trailingSilenceMs(lastSpeechAtMs)
            } else {
                finalTrailingSilenceMs
            }
            return ActivationResult(
                text = text,
                samples = currentSamples(),
                alternateSamples = null,
                elapsedMs = elapsedMs,
                endpoint = endpoint,
                activeSpeechMs = activeSpeechMs,
                trailingSilenceMs = trailing,
                peakRms = peakRms,
                meanRms = meanRms(rmsSum, rmsFrameCount),
                asrGain = maxAsrGain,
            )
        }

        fun bufferedActivationResult(streamingResult: ActivationResult): ActivationResult? {
            if (!speechSeen && streamingResult.text.isBlank()) return null

            val buffered = recognizeBufferedActivation(
                context = applicationContext,
                samples = streamingResult.samples,
                endpoint = "${streamingResult.endpoint}_live_buffered",
            )
            if (!CommandInterpreter.isActivationWakeAsrEquivalent(buffered.text)) return null

            val activationSamples = activationSamplesForResult(streamingResult.samples, buffered)
            return ActivationResult(
                text = buffered.text,
                samples = streamingResult.samples,
                alternateSamples = activationSamples.takeUnless { it === streamingResult.samples },
                elapsedMs = SystemClock.elapsedRealtime() - segmentStartedAt,
                endpoint = buffered.endpoint,
                activeSpeechMs = activeSpeechMs,
                trailingSilenceMs = streamingResult.trailingSilenceMs,
                peakRms = peakRms,
                meanRms = meanRms(rmsSum, rmsFrameCount),
                asrGain = maxOf(maxAsrGain, buffered.asrGain),
            )
        }

        fun finishSegment(finalEndpoint: String): ActivationResult {
            acceptTailPadding(stream)
            stream.inputFinished()
            while (localRecognizer.isReady(stream)) {
                localRecognizer.decode(stream)
            }
            val finalText = localRecognizer.getResult(stream).text.trim()
            if (finalText.isNotBlank() && finalText != lastText) {
                Log.d(TAG, "Local activation final text='$finalText'")
                onText(finalText)
            }
            val endpointForResult = when {
                CommandInterpreter.isActivationWakeAsrEquivalent(finalText) -> "${finalEndpoint}_activation"
                !speechSeen && finalText.isBlank() -> "no_speech_timeout"
                else -> finalEndpoint
            }
            val streamingResult = result(text = finalText, endpoint = endpointForResult)
            return if (CommandInterpreter.isActivationWakeAsrEquivalent(streamingResult.text)) {
                streamingResult
            } else {
                bufferedActivationResult(streamingResult) ?: streamingResult
            }
        }

        try {
            recorder.startRecording()
            while (shouldContinue() && SystemClock.elapsedRealtime() - startedAt < timeoutMs) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                val now = SystemClock.elapsedRealtime()
                val rms = frameRms(buffer, read)
                peakRms = maxOf(peakRms, rms)
                rmsSum += rms
                rmsFrameCount += 1

                if (rms >= ACTIVATION_SPEECH_RMS_THRESHOLD) {
                    speechSeen = true
                    activeSpeechMs += READ_INTERVAL_MS
                    lastSpeechAtMs = now
                }

                val samples = FloatArray(read) { index -> buffer[index] / 32768.0f }
                chunks.addLast(samples)
                totalSamples += read
                while (chunks.isNotEmpty() && totalSamples - chunks.first.size >= rollingSamples) {
                    totalSamples -= chunks.removeFirst().size
                }

                val asrGain = asrGainForRms(rms)
                maxAsrGain = maxOf(maxAsrGain, asrGain)
                stream.acceptWaveform(applyAsrGain(samples, asrGain), SAMPLE_RATE_HZ)
                while (localRecognizer.isReady(stream)) {
                    localRecognizer.decode(stream)
                }

                val text = localRecognizer.getResult(stream).text.trim()
                if (text.isNotBlank() && text != lastText) {
                    lastText = text
                    Log.d(TAG, "Local activation text='$text'")
                    onText(text)
                }
                if (CommandInterpreter.isActivationWakeAsrEquivalent(text)) {
                    return result(text = text, endpoint = "partial_activation", elapsedMs = now - segmentStartedAt)
                }

                val elapsedMs = now - segmentStartedAt
                if (
                    speechSeen &&
                    activeSpeechMs >= ACTIVATION_MIN_ACTIVE_SPEECH_MS &&
                    now - lastBufferedCheckAtMs >= ACTIVATION_BUFFERED_CHECK_INTERVAL_MS
                ) {
                    lastBufferedCheckAtMs = now
                    bufferedActivationResult(
                        result(
                            text = text,
                            endpoint = "rolling_buffer",
                            elapsedMs = elapsedMs,
                        ),
                    )?.let { return it }
                }

                val trailingSilenceMs = trailingSilenceMs(lastSpeechAtMs, now)
                if (
                    speechSeen &&
                    activeSpeechMs >= ACTIVATION_MIN_ACTIVE_SPEECH_MS &&
                    elapsedMs >= ACTIVATION_EARLY_ENDPOINT_MIN_LISTEN_MS &&
                    (trailingSilenceMs >= ACTIVATION_TRAILING_SILENCE_MS || elapsedMs >= ACTIVATION_MAX_SEGMENT_MS)
                ) {
                    endpoint = if (trailingSilenceMs >= ACTIVATION_TRAILING_SILENCE_MS) {
                        "trailing_silence"
                    } else {
                        "segment_timeout"
                    }
                    finalTrailingSilenceMs = trailingSilenceMs
                    Log.d(
                        TAG,
                        "Local activation endpoint: elapsed=${elapsedMs}ms, " +
                            "speech=${activeSpeechMs}ms, silence=${trailingSilenceMs}ms",
                    )
                    val segmentResult = finishSegment(endpoint)
                    if (CommandInterpreter.isActivationWakeAsrEquivalent(segmentResult.text)) {
                        return segmentResult
                    }

                    onRejected(segmentResult)
                    resetSegment(now)
                }
            }

            return finishSegment(endpoint)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            stream.release()
        }
    }

    @SuppressLint("MissingPermission")
    fun listenForCommand(
        context: Context,
        timeoutMs: Long,
        shouldContinue: () -> Boolean,
        onText: (String) -> Unit = {},
    ): Result {
        if (!isAvailable(context)) {
            return Result(command = null, text = "", elapsedMs = 0L, unavailable = true)
        }

        val startedAt = SystemClock.elapsedRealtime()
        val localRecognizer = getRecognizer(context.applicationContext)
        val stream = localRecognizer.createStream()
        val recorder = createRecorder()
        val readSize = (SAMPLE_RATE_HZ * READ_INTERVAL_MS / 1000L).toInt()
        val fallbackSamples = (SAMPLE_RATE_HZ * COMMAND_SAMPLE_FALLBACK_AUDIO_MS / 1000L).toInt()
        val buffer = ShortArray(readSize)
        var lastText = ""
        var speechSeen = false
        var activeSpeechMs = 0L
        var lastSpeechAtMs = 0L
        var endpoint = "timeout"
        var finalTrailingSilenceMs = 0L
        var peakRms = 0f
        var rmsSum = 0.0
        var rmsFrameCount = 0
        var maxAsrGain = 1f
        val chunks = ArrayDeque<FloatArray>()
        var totalSamples = 0

        try {
            recorder.startRecording()
            while (shouldContinue() && SystemClock.elapsedRealtime() - startedAt < timeoutMs) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                val now = SystemClock.elapsedRealtime()
                val rms = frameRms(buffer, read)
                peakRms = maxOf(peakRms, rms)
                rmsSum += rms
                rmsFrameCount += 1

                if (rms >= LOCAL_SPEECH_RMS_THRESHOLD) {
                    speechSeen = true
                    activeSpeechMs += READ_INTERVAL_MS
                    lastSpeechAtMs = now
                }

                val samples = FloatArray(read) { index -> buffer[index] / 32768.0f }
                chunks.addLast(samples)
                totalSamples += read
                while (chunks.isNotEmpty() && totalSamples - chunks.first.size >= fallbackSamples) {
                    totalSamples -= chunks.removeFirst().size
                }
                val asrGain = asrGainForRms(rms)
                maxAsrGain = maxOf(maxAsrGain, asrGain)
                stream.acceptWaveform(applyAsrGain(samples, asrGain), SAMPLE_RATE_HZ)
                while (localRecognizer.isReady(stream)) {
                    localRecognizer.decode(stream)
                }

                val text = localRecognizer.getResult(stream).text.trim()
                val command = commandFromText(text)
                if (text.isNotBlank() && text != lastText) {
                    lastText = text
                    Log.d(TAG, "Local command text='$text' command=$command")
                    onText(text)
                }
                if (command != null) {
                    return Result(
                        command = command,
                        text = text,
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                        endpoint = "partial_command",
                        activeSpeechMs = activeSpeechMs,
                        trailingSilenceMs = trailingSilenceMs(lastSpeechAtMs),
                        peakRms = peakRms,
                        meanRms = meanRms(rmsSum, rmsFrameCount),
                        asrGain = maxAsrGain,
                        samples = flattenLastSamples(
                            chunks = chunks,
                            sampleCount = totalSamples,
                            totalSamples = totalSamples,
                        ),
                    )
                }

                val elapsedMs = now - startedAt
                val trailingSilenceMs = trailingSilenceMs(lastSpeechAtMs, now)
                if (
                    speechSeen &&
                    activeSpeechMs >= LOCAL_MIN_ACTIVE_SPEECH_MS &&
                    elapsedMs >= LOCAL_EARLY_ENDPOINT_MIN_LISTEN_MS &&
                    trailingSilenceMs >= LOCAL_TRAILING_SILENCE_MS
                ) {
                    endpoint = "trailing_silence"
                    finalTrailingSilenceMs = trailingSilenceMs
                    Log.d(
                        TAG,
                        "Local command endpoint: elapsed=${elapsedMs}ms, " +
                            "speech=${activeSpeechMs}ms, silence=${trailingSilenceMs}ms",
                    )
                    break
                }
            }

            acceptTailPadding(stream)
            stream.inputFinished()
            while (localRecognizer.isReady(stream)) {
                localRecognizer.decode(stream)
            }
            val finalText = localRecognizer.getResult(stream).text.trim()
            val finalCommand = commandFromText(finalText)
            if (finalText.isNotBlank() && finalText != lastText) {
                Log.d(TAG, "Local command final text='$finalText' command=$finalCommand")
                onText(finalText)
            }
            val trailing = if (finalTrailingSilenceMs == 0L) {
                trailingSilenceMs(lastSpeechAtMs)
            } else {
                finalTrailingSilenceMs
            }
            val finalEndpoint = if (!speechSeen && finalText.isBlank()) {
                "no_speech_timeout"
            } else {
                endpoint
            }
            val bufferedSamples = if (speechSeen && totalSamples > 0) {
                flattenLastSamples(
                    chunks = chunks,
                    sampleCount = totalSamples,
                    totalSamples = totalSamples,
                )
            } else {
                FloatArray(0)
            }
            val sampleFallbackAllowed = finalText.isBlank() || CommandInterpreter.containsCommandWakeWord(finalText)
            val sampleMatch = if (finalCommand == null && bufferedSamples.isNotEmpty() && sampleFallbackAllowed) {
                CommandVoiceSampleMatcher.match(context.applicationContext, bufferedSamples)
            } else {
                if (finalCommand == null && bufferedSamples.isNotEmpty() && !sampleFallbackAllowed) {
                    Log.d(TAG, "Skipping command voice sample fallback because final text has no wake word: '$finalText'")
                }
                null
            }
            val matchedCommand = finalCommand ?: sampleMatch?.commandId
            val matchedEndpoint = if (finalCommand == null && sampleMatch?.accepted == true) {
                "voice_sample_match"
            } else {
                finalEndpoint
            }
            if (sampleMatch != null) {
                Log.d(
                    TAG,
                    "Command voice sample fallback accepted=${sampleMatch.accepted} " +
                        "command=${sampleMatch.commandId.orEmpty()} distance=${sampleMatch.distance} " +
                        "next=${sampleMatch.nextCommandDistance} reason=${sampleMatch.reason}",
                )
            }
            return Result(
                command = matchedCommand,
                text = finalText,
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                endpoint = matchedEndpoint,
                activeSpeechMs = activeSpeechMs,
                trailingSilenceMs = trailing,
                peakRms = peakRms,
                meanRms = meanRms(rmsSum, rmsFrameCount),
                asrGain = maxAsrGain,
                samples = bufferedSamples,
                sampleMatchCommandId = sampleMatch?.commandId,
                sampleMatchAccepted = sampleMatch?.accepted == true,
                sampleMatchReason = sampleMatch?.reason.orEmpty(),
                sampleMatchDistance = sampleMatch?.distance ?: Float.POSITIVE_INFINITY,
                sampleMatchNextDistance = sampleMatch?.nextCommandDistance ?: Float.POSITIVE_INFINITY,
                sampleMatchDurationRatio = sampleMatch?.durationRatio ?: 0f,
            )
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            stream.release()
        }
    }

    private fun commandFromText(text: String): String? {
        if (text.isBlank()) return null
        return CommandInterpreter.parse(text = text, requireWakeWord = true)
    }

    private fun decodeBufferedSamples(
        recognizer: OnlineRecognizer,
        samples: FloatArray,
        endpoint: String,
        parseCommand: Boolean,
        logLabel: String,
    ): Result {
        val startedAt = SystemClock.elapsedRealtime()
        val stream = recognizer.createStream()
        val audioLevelStats = audioLevelStats(samples)
        val gainReferenceRms = audioLevelStats.meanRms.coerceAtLeast(LOCAL_ASR_GAIN_MIN_RMS)
        val asrGain = asrGainForRms(gainReferenceRms)
        val asrSamples = applyAsrGain(samples, asrGain)
        return try {
            stream.acceptWaveform(asrSamples, SAMPLE_RATE_HZ)
            acceptTailPadding(stream)
            stream.inputFinished()
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
            }

            val text = recognizer.getResult(stream).text.trim()
            val command = if (parseCommand) commandFromText(text) else null
            Log.d(TAG, "$logLabel text='$text' command=$command endpoint=$endpoint")
            Result(
                command = command,
                text = text,
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                endpoint = endpoint,
                activeSpeechMs = samples.size * 1000L / SAMPLE_RATE_HZ,
                peakRms = audioLevelStats.peakRms,
                meanRms = audioLevelStats.meanRms,
                asrGain = asrGain,
                samples = samples,
            )
        } finally {
            stream.release()
        }
    }

    private fun acceptTailPadding(stream: OnlineStream) {
        stream.acceptWaveform(FloatArray(BUFFERED_TAIL_PADDING_SAMPLES), SAMPLE_RATE_HZ)
    }

    private fun frameRms(buffer: ShortArray, read: Int): Float {
        if (read <= 0) return 0f

        var sumSquares = 0.0
        for (index in 0 until read) {
            val sample = buffer[index] / 32768.0
            sumSquares += sample * sample
        }
        return sqrt(sumSquares / read).toFloat()
    }

    private fun audioLevelStats(samples: FloatArray): AudioLevelStats {
        val frameSamples = (SAMPLE_RATE_HZ * READ_INTERVAL_MS / 1000L).toInt()
        if (samples.isEmpty() || frameSamples <= 0) return AudioLevelStats(0f, 0f)

        var start = 0
        var peakRms = 0f
        var rmsSum = 0.0
        var frameCount = 0
        while (start < samples.size) {
            val end = minOf(start + frameSamples, samples.size)
            val rms = frameRms(samples, start, end)
            peakRms = maxOf(peakRms, rms)
            rmsSum += rms
            frameCount += 1
            start += frameSamples
        }

        return AudioLevelStats(
            peakRms = peakRms,
            meanRms = meanRms(rmsSum, frameCount),
        )
    }

    private fun frameRms(samples: FloatArray, start: Int, end: Int): Float {
        if (end <= start) return 0f

        var sumSquares = 0.0
        for (index in start until end) {
            val sample = samples[index].toDouble()
            sumSquares += sample * sample
        }
        return sqrt(sumSquares / (end - start)).toFloat()
    }

    private fun meanRms(rmsSum: Double, frameCount: Int): Float {
        return if (frameCount > 0) (rmsSum / frameCount).toFloat() else 0f
    }

    private fun asrGainForRms(rms: Float): Float {
        if (rms < LOCAL_ASR_GAIN_MIN_RMS) return 1f

        return (LOCAL_ASR_TARGET_RMS / rms).coerceIn(1f, LOCAL_ASR_MAX_GAIN)
    }

    private fun applyAsrGain(samples: FloatArray, gain: Float): FloatArray {
        if (gain <= 1f) return samples

        return FloatArray(samples.size) { index ->
            (samples[index] * gain).coerceIn(-1f, 1f)
        }
    }

    private fun flattenLastSamples(
        chunks: ArrayDeque<FloatArray>,
        sampleCount: Int,
        totalSamples: Int,
    ): FloatArray {
        if (sampleCount <= 0 || chunks.isEmpty()) return FloatArray(0)

        val result = FloatArray(sampleCount)
        var remainingToSkip = totalSamples - sampleCount
        var writeOffset = 0
        for (chunk in chunks) {
            if (remainingToSkip >= chunk.size) {
                remainingToSkip -= chunk.size
                continue
            }

            val sourceStart = remainingToSkip.coerceAtLeast(0)
            val sourceCount = minOf(chunk.size - sourceStart, sampleCount - writeOffset)
            chunk.copyInto(
                destination = result,
                destinationOffset = writeOffset,
                startIndex = sourceStart,
                endIndex = sourceStart + sourceCount,
            )
            writeOffset += sourceCount
            remainingToSkip = 0
            if (writeOffset >= sampleCount) break
        }
        return if (writeOffset == sampleCount) result else result.copyOf(writeOffset)
    }

    private fun trailingSilenceMs(
        lastSpeechAtMs: Long,
        now: Long = SystemClock.elapsedRealtime(),
    ): Long {
        return if (lastSpeechAtMs > 0L) now - lastSpeechAtMs else 0L
    }

    private fun getRecognizer(context: Context): OnlineRecognizer {
        recognizer?.let { return it }

        synchronized(initLock) {
            recognizer?.let { return it }

            val config = OnlineRecognizerConfig().apply {
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE_HZ, featureDim = 80)
                modelConfig = onlineModelConfig()
                decodingMethod = "greedy_search"
                maxActivePaths = 4
                enableEndpoint = false
            }

            return OnlineRecognizer(context.assets, config).also {
                recognizer = it
            }
        }
    }

    private fun getActivationRecognizer(context: Context): OnlineRecognizer {
        activationRecognizer?.let { return it }

        synchronized(initLock) {
            activationRecognizer?.let { return it }

            val config = OnlineRecognizerConfig().apply {
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE_HZ, featureDim = 80)
                modelConfig = onlineModelConfig()
                decodingMethod = "modified_beam_search"
                maxActivePaths = 8
                hotwordsFile = ACTIVATION_HOTWORDS
                hotwordsScore = 8.0f
                enableEndpoint = false
            }

            return OnlineRecognizer(context.assets, config).also {
                activationRecognizer = it
            }
        }
    }

    private fun onlineModelConfig(): OnlineModelConfig {
        val transducer = OnlineTransducerModelConfig().apply {
            encoder = ENCODER
            decoder = DECODER
            joiner = JOINER
        }
        return OnlineModelConfig().apply {
            this.transducer = transducer
            tokens = TOKENS
            numThreads = 4
            debug = false
            provider = "cpu"
            modelingUnit = "cjkchar"
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(): AudioRecord {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, channelConfig, audioFormat)
        require(minBufferBytes > 0) { "AudioRecord buffer size is not available: $minBufferBytes" }

        val readSize = (SAMPLE_RATE_HZ * READ_INTERVAL_MS / 1000L).toInt()
        val bufferBytes = maxOf(minBufferBytes, readSize * Short.SIZE_BYTES * 2)
        val sources = listOf(
            RecorderSource(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION"),
            RecorderSource(MediaRecorder.AudioSource.MIC, "MIC"),
        )
        var lastSource: String? = null
        for (source in sources) {
            val recorder = AudioRecord(
                source.source,
                SAMPLE_RATE_HZ,
                channelConfig,
                audioFormat,
                bufferBytes,
            )
            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(TAG, "AudioRecord initialized source=${source.label} bufferBytes=$bufferBytes")
                return recorder
            }

            lastSource = source.label
            recorder.release()
        }
        error("AudioRecord was not initialized; lastSource=$lastSource")
    }

    private fun assetExists(context: Context, assetPath: String): Boolean {
        return runCatching {
            context.assets.open(assetPath).use { true }
        }.getOrDefault(false)
    }
}
