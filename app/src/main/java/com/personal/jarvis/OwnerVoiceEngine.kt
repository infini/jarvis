package com.personal.jarvis

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.util.ArrayDeque
import kotlin.math.min
import kotlin.math.sqrt

object OwnerVoiceEngine {
    const val SAMPLE_RATE_HZ = 16000
    private const val EMBEDDING_MIN_AUDIO_MS = 1200L
    private const val MIN_ACTIVE_SPEECH_MS = 240L
    private const val READ_INTERVAL_MS = 80L
    private const val ENERGY_FRAME_MS = 25L
    private const val SPEECH_EDGE_MARGIN_MS = 180L
    private const val MIN_PEAK_RMS = 0.0012f
    private const val MIN_ACTIVE_RMS = 0.00085f
    private const val VERIFY_MIN_PEAK_RMS = 0.00075f
    private const val VERIFY_MIN_ACTIVE_RMS = 0.00050f
    private const val ACTIVE_RMS_RATIO = 0.18f
    private const val VERIFY_NOISE_FLOOR_PERCENTILE = 0.20f
    private const val VERIFY_MIN_PEAK_TO_FLOOR_RATIO = 1.35f
    private const val VERIFY_MIN_PEAK_ABOVE_FLOOR_RMS = 0.00022f
    private const val VERIFY_ACTIVE_RMS_RANGE_RATIO = 0.28f
    private const val ENROLLMENT_SEGMENT_MS = 1400L
    private const val ENROLLMENT_SEGMENT_STEP_MS = 700L
    private const val ENROLLMENT_DUPLICATE_SIMILARITY = 0.995f
    const val MIN_OWNER_EMBEDDINGS = 2
    const val MAX_OWNER_EMBEDDINGS = 8
    const val HIGH_CONFIDENCE_ACCEPT_THRESHOLD = 0.36f
    private const val HIGH_CONFIDENCE_ACCEPT_MIN_SPEECH_MS = 450L
    const val NEAR_ACCEPT_THRESHOLD = 0.28f
    private const val NEAR_ACCEPT_REQUIRED_COUNT = 2
    private const val NEAR_ACCEPT_MIN_SPEECH_MS = 450L
    private const val RELAXED_ACCEPT_MIN_PEAK_RMS = 0.0035f
    const val SOFT_WAKE_SINGLE_ACCEPT_THRESHOLD = 0.16f
    private const val SOFT_WAKE_SINGLE_ACCEPT_MIN_SPEECH_MS = 850L
    const val SOFT_WAKE_ACCEPT_THRESHOLD = 0.14f
    private const val SOFT_WAKE_ACCEPT_REQUIRED_COUNT = 4
    private const val SOFT_WAKE_ACCEPT_MIN_SPEECH_MS = 400L
    private const val SOFT_WAKE_BRIDGE_THRESHOLD = 0.10f
    private const val ACTIVATION_PHRASE_ACCEPT_THRESHOLD = NEAR_ACCEPT_THRESHOLD
    private const val ACTIVATION_PHRASE_ACCEPT_MIN_SPEECH_MS = NEAR_ACCEPT_MIN_SPEECH_MS

    private val initLock = Any()
    private val computeLock = Any()
    @Volatile private var extractor: SpeakerEmbeddingExtractor? = null

    enum class Acceptance {
        REJECTED,
        STRICT,
        HIGH_CONFIDENCE_SINGLE,
        NEAR_CONSECUTIVE,
        SOFT_WAKE_SINGLE,
        SOFT_WAKE_CONSECUTIVE,
    }

    enum class RejectReason {
        MISSING_PROFILE,
        INPUT_TOO_SHORT,
        PEAK_BELOW_MIN,
        PEAK_BELOW_FLOOR,
        NO_ACTIVE_SPEECH,
        ACTIVE_SPEECH_TOO_SHORT,
        EMBEDDING_NOT_READY,
        LOW_SCORE,
    }

    data class Match(
        val score: Float,
        val accepted: Boolean,
        val activeSpeechMs: Long = 0L,
        val acceptance: Acceptance = Acceptance.REJECTED,
        val commandSamples: FloatArray? = null,
        val ownerEmbeddingCount: Int = 0,
        val peakRms: Float = 0f,
        val noiseFloorRms: Float = 0f,
        val activeThresholdRms: Float = 0f,
        val rejectReason: RejectReason? = null,
        val verificationElapsedMs: Long = 0L,
        val verificationAttempts: Int = 0,
    )

    internal data class PreparedAudio(
        val samples: FloatArray,
        val activeSampleCount: Int,
        val peakRms: Float,
        val noiseFloorRms: Float,
        val activeThresholdRms: Float,
    ) {
        val activeSpeechMs: Long
            get() = activeSampleCount * 1000L / SAMPLE_RATE_HZ
    }

    data class AudioSummary(
        val durationMs: Long,
        val peakFrameRms: Float,
        val meanRms: Float,
    )

    private data class SpeechBounds(
        val start: Int,
        val end: Int,
        val activeSampleCount: Int,
    )

    private data class SpeechBoundsResult(
        val bounds: SpeechBounds? = null,
        val rejectReason: RejectReason? = null,
        val peakRms: Float = 0f,
        val noiseFloorRms: Float = 0f,
        val activeThresholdRms: Float = 0f,
    ) {
        val activeSpeechMs: Long
            get() = bounds?.activeSampleCount?.times(1000L)?.div(SAMPLE_RATE_HZ) ?: 0L
    }

    private data class PreparedAudioResult(
        val audio: PreparedAudio? = null,
        val rejectReason: RejectReason? = null,
        val activeSpeechMs: Long = 0L,
        val peakRms: Float = 0f,
        val noiseFloorRms: Float = 0f,
        val activeThresholdRms: Float = 0f,
    )

    internal data class ConsecutiveAcceptState(
        val nearCount: Int = 0,
        val softWakeCount: Int = 0,
        val softWakeBridgeUsed: Boolean = false,
    )

    fun createEmbedding(context: Context, samples: FloatArray): FloatArray? {
        val preparedAudio = prepareSamplesForEmbedding(samples) ?: return null
        return createEmbedding(context, preparedAudio)
    }

    fun createEnrollmentEmbeddings(context: Context, samples: FloatArray): List<FloatArray> {
        val embeddings = mutableListOf<FloatArray>()

        createEmbedding(context, samples)?.let { embeddings += it }
        for (segment in enrollmentSegments(samples)) {
            if (embeddings.size >= MAX_OWNER_EMBEDDINGS) break

            val embedding = createEmbedding(context, segment) ?: continue
            if (embeddings.any { cosineSimilarity(it, embedding) >= ENROLLMENT_DUPLICATE_SIMILARITY }) {
                continue
            }

            embeddings += embedding
        }

        return embeddings
    }

    fun summarizeAudio(samples: FloatArray): AudioSummary {
        if (samples.isEmpty()) {
            return AudioSummary(durationMs = 0L, peakFrameRms = 0f, meanRms = 0f)
        }

        val frameSamples = (SAMPLE_RATE_HZ * ENERGY_FRAME_MS / 1000L).toInt()
        var start = 0
        var peakFrameRms = 0f
        while (start < samples.size) {
            val end = min(start + frameSamples, samples.size)
            peakFrameRms = maxOf(peakFrameRms, rms(samples, start, end))
            start += frameSamples
        }

        return AudioSummary(
            durationMs = samples.size * 1000L / SAMPLE_RATE_HZ,
            peakFrameRms = peakFrameRms,
            meanRms = rms(samples, 0, samples.size),
        )
    }

    private fun createEmbedding(context: Context, preparedAudio: PreparedAudio): FloatArray? {
        synchronized(computeLock) {
            val speakerExtractor = getExtractor(context.applicationContext)
            val stream = speakerExtractor.createStream()
            return try {
                stream.acceptWaveform(preparedAudio.samples, SAMPLE_RATE_HZ)
                stream.inputFinished()
                if (speakerExtractor.isReady(stream)) {
                    speakerExtractor.compute(stream)
                } else {
                    null
                }
            } finally {
                stream.release()
            }
        }
    }

    fun verifyOwner(
        context: Context,
        samples: FloatArray,
        threshold: Float = OwnerVoiceStore.DEFAULT_ACCEPT_THRESHOLD,
        ownerEmbeddings: List<FloatArray> = OwnerVoiceStore.getEmbeddings(context),
    ): Match {
        val validOwnerEmbeddings = ownerEmbeddings.filter { it.isNotEmpty() }
        if (validOwnerEmbeddings.isEmpty()) {
            return Match(0f, accepted = false, rejectReason = RejectReason.MISSING_PROFILE)
        }

        val ownerEmbeddingCount = validOwnerEmbeddings.size
        val preparedAudioResult = prepareSamplesForEmbeddingResult(
            samples = samples,
            requireSpeechContrast = true,
        )
        val preparedAudio = preparedAudioResult.audio ?: return Match(
            score = 0f,
            accepted = false,
            activeSpeechMs = preparedAudioResult.activeSpeechMs,
            peakRms = preparedAudioResult.peakRms,
            noiseFloorRms = preparedAudioResult.noiseFloorRms,
            activeThresholdRms = preparedAudioResult.activeThresholdRms,
            ownerEmbeddingCount = ownerEmbeddingCount,
            rejectReason = preparedAudioResult.rejectReason,
        )
        val candidateEmbedding = createEmbedding(context, preparedAudio)
            ?: return Match(
                score = 0f,
                accepted = false,
                activeSpeechMs = preparedAudio.activeSpeechMs,
                peakRms = preparedAudio.peakRms,
                noiseFloorRms = preparedAudio.noiseFloorRms,
                activeThresholdRms = preparedAudio.activeThresholdRms,
                ownerEmbeddingCount = ownerEmbeddingCount,
                rejectReason = RejectReason.EMBEDDING_NOT_READY,
            )
        val score = validOwnerEmbeddings
            .maxOf { ownerEmbedding -> cosineSimilarity(ownerEmbedding, candidateEmbedding) }
        val accepted = score >= threshold
        return Match(
            score = score,
            accepted = accepted,
            activeSpeechMs = preparedAudio.activeSpeechMs,
            acceptance = if (accepted) Acceptance.STRICT else Acceptance.REJECTED,
            commandSamples = preparedAudio.samples,
            ownerEmbeddingCount = ownerEmbeddingCount,
            peakRms = preparedAudio.peakRms,
            noiseFloorRms = preparedAudio.noiseFloorRms,
            activeThresholdRms = preparedAudio.activeThresholdRms,
            rejectReason = if (accepted) null else RejectReason.LOW_SCORE,
        )
    }

    @SuppressLint("MissingPermission")
    fun waitForOwnerMatch(
        context: Context,
        windowMs: Long,
        verificationIntervalMs: Long,
        shouldContinue: () -> Boolean,
        onMatch: (Match) -> Unit = {},
        shouldAccept: (Match) -> Boolean = { true },
        postAcceptAudioMs: Long = 0L,
        captureWindowMs: Long = windowMs,
    ): Match? {
        val recorder = createRecorder()
        val readSize = (SAMPLE_RATE_HZ * READ_INTERVAL_MS / 1000L).toInt()
        val buffer = ShortArray(readSize)
        val verificationWindowSamples = (SAMPLE_RATE_HZ * windowMs / 1000L).toInt()
        val captureWindowSamples = (SAMPLE_RATE_HZ * maxOf(windowMs, captureWindowMs) / 1000L).toInt()
        val chunks = ArrayDeque<FloatArray>()
        var totalSamples = 0
        var lastVerificationAt = 0L
        val startedAt = System.currentTimeMillis()
        var verificationAttempts = 0
        var consecutiveAcceptState = ConsecutiveAcceptState()
        val ownerEmbeddings = OwnerVoiceStore.getEmbeddings(context)

        try {
            recorder.startRecording()
            while (shouldContinue()) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                val samples = FloatArray(read) { index -> buffer[index] / 32768.0f }
                chunks.addLast(samples)
                totalSamples += read

                while (chunks.isNotEmpty() && totalSamples - chunks.first.size >= captureWindowSamples) {
                    totalSamples -= chunks.removeFirst().size
                }

                val now = System.currentTimeMillis()
                if (totalSamples >= verificationWindowSamples && now - lastVerificationAt >= verificationIntervalMs) {
                    lastVerificationAt = now
                    verificationAttempts += 1
                    val match = verifyOwner(
                        context = context,
                        samples = flattenLastSamples(chunks, verificationWindowSamples, totalSamples),
                        ownerEmbeddings = ownerEmbeddings,
                    )
                    val adjustedMatch = applyConsecutiveAcceptPolicy(match, consecutiveAcceptState)
                    consecutiveAcceptState = adjustedMatch.second
                    val timedMatch = adjustedMatch.first.copy(
                        verificationElapsedMs = now - startedAt,
                        verificationAttempts = verificationAttempts,
                    )
                    onMatch(timedMatch)
                    if (timedMatch.accepted && shouldAccept(timedMatch)) {
                        return if (postAcceptAudioMs > 0L) {
                            capturePostAcceptAudio(
                                recorder = recorder,
                                buffer = buffer,
                                chunks = chunks,
                                totalSamples = totalSamples,
                                captureWindowSamples = captureWindowSamples,
                                postAcceptAudioMs = postAcceptAudioMs,
                                startedAt = startedAt,
                                match = timedMatch,
                                shouldContinue = shouldContinue,
                            )
                        } else {
                            timedMatch
                        }
                    }
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }

        return null
    }

    private fun capturePostAcceptAudio(
        recorder: AudioRecord,
        buffer: ShortArray,
        chunks: ArrayDeque<FloatArray>,
        totalSamples: Int,
        captureWindowSamples: Int,
        postAcceptAudioMs: Long,
        startedAt: Long,
        match: Match,
        shouldContinue: () -> Boolean,
    ): Match {
        var currentTotalSamples = totalSamples
        val captureUntil = System.currentTimeMillis() + postAcceptAudioMs
        while (shouldContinue() && System.currentTimeMillis() < captureUntil) {
            val read = recorder.read(buffer, 0, buffer.size)
            if (read <= 0) continue

            val samples = FloatArray(read) { index -> buffer[index] / 32768.0f }
            chunks.addLast(samples)
            currentTotalSamples += read

            while (chunks.isNotEmpty() && currentTotalSamples - chunks.first.size >= captureWindowSamples) {
                currentTotalSamples -= chunks.removeFirst().size
            }
        }

        val commandWindowSamples = flattenLastSamples(
            chunks = chunks,
            sampleCount = captureWindowSamples,
            totalSamples = currentTotalSamples,
        )
        return match.copy(
            commandSamples = commandWindowSamples,
            verificationElapsedMs = System.currentTimeMillis() - startedAt,
        )
    }

    internal fun applyConsecutiveAcceptPolicy(
        match: Match,
        previousState: ConsecutiveAcceptState,
    ): Pair<Match, ConsecutiveAcceptState> {
        if (match.accepted) return match to ConsecutiveAcceptState()

        if (
            match.ownerEmbeddingCount >= OwnerVoiceStore.MIN_CONFIGURED_EMBEDDINGS &&
            match.score >= HIGH_CONFIDENCE_ACCEPT_THRESHOLD &&
            match.activeSpeechMs >= HIGH_CONFIDENCE_ACCEPT_MIN_SPEECH_MS
        ) {
            return match.copy(
                accepted = true,
                acceptance = Acceptance.HIGH_CONFIDENCE_SINGLE,
                rejectReason = null,
            ) to ConsecutiveAcceptState()
        }

        if (match.score >= NEAR_ACCEPT_THRESHOLD && match.activeSpeechMs >= NEAR_ACCEPT_MIN_SPEECH_MS) {
            if (!hasRelaxedAcceptPeak(match)) return match to ConsecutiveAcceptState()
            val nearAcceptCount = previousState.nearCount + 1
            if (nearAcceptCount >= NEAR_ACCEPT_REQUIRED_COUNT) {
                return match.copy(
                    accepted = true,
                    acceptance = Acceptance.NEAR_CONSECUTIVE,
                    rejectReason = null,
                ) to ConsecutiveAcceptState()
            }

            return match to ConsecutiveAcceptState(nearCount = nearAcceptCount)
        }

        if (
            match.score >= SOFT_WAKE_SINGLE_ACCEPT_THRESHOLD &&
            match.activeSpeechMs >= SOFT_WAKE_SINGLE_ACCEPT_MIN_SPEECH_MS &&
            hasRelaxedAcceptPeak(match)
        ) {
            return match.copy(
                accepted = true,
                acceptance = Acceptance.SOFT_WAKE_SINGLE,
                rejectReason = null,
            ) to ConsecutiveAcceptState()
        }

        if (
            match.score >= SOFT_WAKE_ACCEPT_THRESHOLD &&
            match.activeSpeechMs >= SOFT_WAKE_ACCEPT_MIN_SPEECH_MS &&
            hasRelaxedAcceptPeak(match)
        ) {
            val softWakeCount = previousState.softWakeCount + 1
            if (softWakeCount >= SOFT_WAKE_ACCEPT_REQUIRED_COUNT) {
                return match.copy(
                    accepted = true,
                    acceptance = Acceptance.SOFT_WAKE_CONSECUTIVE,
                    rejectReason = null,
                ) to ConsecutiveAcceptState()
            }

            return match to ConsecutiveAcceptState(softWakeCount = softWakeCount)
        }

        if (
            previousState.softWakeCount > 0 &&
            !previousState.softWakeBridgeUsed &&
            match.score >= SOFT_WAKE_BRIDGE_THRESHOLD &&
            match.activeSpeechMs >= SOFT_WAKE_ACCEPT_MIN_SPEECH_MS &&
            hasRelaxedAcceptPeak(match)
        ) {
            return match to previousState.copy(softWakeBridgeUsed = true)
        }

        return match to ConsecutiveAcceptState()
    }

    fun acceptActivationPhraseMatch(match: Match): Match {
        if (match.accepted) return match

        if (
            match.ownerEmbeddingCount >= OwnerVoiceStore.MIN_CONFIGURED_EMBEDDINGS &&
            match.score >= HIGH_CONFIDENCE_ACCEPT_THRESHOLD &&
            match.activeSpeechMs >= HIGH_CONFIDENCE_ACCEPT_MIN_SPEECH_MS &&
            hasRelaxedAcceptPeak(match)
        ) {
            return match.copy(
                accepted = true,
                acceptance = Acceptance.HIGH_CONFIDENCE_SINGLE,
                rejectReason = null,
            )
        }

        if (
            match.ownerEmbeddingCount >= OwnerVoiceStore.MIN_CONFIGURED_EMBEDDINGS &&
            match.score >= ACTIVATION_PHRASE_ACCEPT_THRESHOLD &&
            match.activeSpeechMs >= ACTIVATION_PHRASE_ACCEPT_MIN_SPEECH_MS &&
            hasRelaxedAcceptPeak(match)
        ) {
            return match.copy(
                accepted = true,
                acceptance = Acceptance.NEAR_CONSECUTIVE,
                rejectReason = null,
            )
        }

        return match
    }

    private fun hasRelaxedAcceptPeak(match: Match): Boolean {
        return match.peakRms >= RELAXED_ACCEPT_MIN_PEAK_RMS
    }

    @SuppressLint("MissingPermission")
    fun recordSamples(
        durationMs: Long,
        shouldContinue: () -> Boolean = { true },
        onProgress: (Float) -> Unit = {},
    ): FloatArray {
        val recorder = createRecorder()
        val readSize = (SAMPLE_RATE_HZ * READ_INTERVAL_MS / 1000L).toInt()
        val chunks = ArrayList<FloatArray>()
        var totalSamples = 0
        val buffer = ShortArray(readSize)
        val startedAt = System.currentTimeMillis()
        val endsAt = startedAt + durationMs

        try {
            recorder.startRecording()
            while (shouldContinue() && System.currentTimeMillis() < endsAt) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val samples = FloatArray(read) { index -> buffer[index] / 32768.0f }
                    chunks += samples
                    totalSamples += read
                }

                val elapsed = System.currentTimeMillis() - startedAt
                onProgress((elapsed.toFloat() / durationMs).coerceIn(0f, 1f))
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }

        onProgress(1f)
        val result = FloatArray(totalSamples)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(result, destinationOffset = offset)
            offset += chunk.size
        }
        return result
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(): AudioRecord {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, channelConfig, audioFormat)
        require(minBufferBytes > 0) { "AudioRecord buffer size is not available: $minBufferBytes" }

        val readSize = (SAMPLE_RATE_HZ * READ_INTERVAL_MS / 1000L).toInt()
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE_HZ,
            channelConfig,
            audioFormat,
            maxOf(minBufferBytes, readSize * Short.SIZE_BYTES * 2),
        )
        require(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord was not initialized" }
        return recorder
    }

    private fun flattenLastSamples(
        chunks: ArrayDeque<FloatArray>,
        sampleCount: Int,
        totalSamples: Int,
    ): FloatArray {
        val result = FloatArray(sampleCount)
        var samplesToSkip = totalSamples - sampleCount
        var offset = 0
        chunks.forEach { chunk ->
            if (samplesToSkip >= chunk.size) {
                samplesToSkip -= chunk.size
                return@forEach
            }

            val startIndex = samplesToSkip.coerceAtLeast(0)
            val copyCount = min(chunk.size - startIndex, sampleCount - offset)
            chunk.copyInto(
                destination = result,
                destinationOffset = offset,
                startIndex = startIndex,
                endIndex = startIndex + copyCount,
            )
            offset += copyCount
            samplesToSkip = 0
            if (offset >= sampleCount) return@forEach
        }
        return result
    }

    internal fun prepareSamplesForEmbedding(
        samples: FloatArray,
        requireSpeechContrast: Boolean = false,
    ): PreparedAudio? = prepareSamplesForEmbeddingResult(samples, requireSpeechContrast).audio

    internal fun enrollmentSegments(samples: FloatArray): List<FloatArray> {
        val segmentSamples = (SAMPLE_RATE_HZ * ENROLLMENT_SEGMENT_MS / 1000L).toInt()
        val stepSamples = (SAMPLE_RATE_HZ * ENROLLMENT_SEGMENT_STEP_MS / 1000L).toInt()
        if (samples.size < segmentSamples || segmentSamples <= 0 || stepSamples <= 0) return emptyList()

        val segments = mutableListOf<FloatArray>()
        var start = 0
        while (start + segmentSamples <= samples.size) {
            segments += samples.copyOfRange(start, start + segmentSamples)
            start += stepSamples
        }
        return segments
    }

    private fun prepareSamplesForEmbeddingResult(
        samples: FloatArray,
        requireSpeechContrast: Boolean = false,
    ): PreparedAudioResult {
        val speechBoundsResult = findSpeechBounds(samples, requireSpeechContrast)
        val speechBounds = speechBoundsResult.bounds ?: return PreparedAudioResult(
            rejectReason = speechBoundsResult.rejectReason,
            activeSpeechMs = speechBoundsResult.activeSpeechMs,
            peakRms = speechBoundsResult.peakRms,
            noiseFloorRms = speechBoundsResult.noiseFloorRms,
            activeThresholdRms = speechBoundsResult.activeThresholdRms,
        )
        val activeSampleCount = speechBounds.activeSampleCount
        if (activeSampleCount < SAMPLE_RATE_HZ * MIN_ACTIVE_SPEECH_MS / 1000L) {
            return PreparedAudioResult(
                rejectReason = RejectReason.ACTIVE_SPEECH_TOO_SHORT,
                activeSpeechMs = speechBoundsResult.activeSpeechMs,
                peakRms = speechBoundsResult.peakRms,
                noiseFloorRms = speechBoundsResult.noiseFloorRms,
                activeThresholdRms = speechBoundsResult.activeThresholdRms,
            )
        }

        val trimmed = samples.copyOfRange(speechBounds.start, speechBounds.end)
        val minSamples = (SAMPLE_RATE_HZ * EMBEDDING_MIN_AUDIO_MS / 1000L).toInt()
        if (trimmed.size >= minSamples) {
            return PreparedAudioResult(
                audio = PreparedAudio(
                    samples = trimmed,
                    activeSampleCount = activeSampleCount,
                    peakRms = speechBoundsResult.peakRms,
                    noiseFloorRms = speechBoundsResult.noiseFloorRms,
                    activeThresholdRms = speechBoundsResult.activeThresholdRms,
                ),
                activeSpeechMs = speechBoundsResult.activeSpeechMs,
                peakRms = speechBoundsResult.peakRms,
                noiseFloorRms = speechBoundsResult.noiseFloorRms,
                activeThresholdRms = speechBoundsResult.activeThresholdRms,
            )
        }

        val padded = FloatArray(minSamples)
        val offset = (minSamples - trimmed.size) / 2
        trimmed.copyInto(padded, destinationOffset = offset)
        return PreparedAudioResult(
            audio = PreparedAudio(
                samples = padded,
                activeSampleCount = activeSampleCount,
                peakRms = speechBoundsResult.peakRms,
                noiseFloorRms = speechBoundsResult.noiseFloorRms,
                activeThresholdRms = speechBoundsResult.activeThresholdRms,
            ),
            activeSpeechMs = speechBoundsResult.activeSpeechMs,
            peakRms = speechBoundsResult.peakRms,
            noiseFloorRms = speechBoundsResult.noiseFloorRms,
            activeThresholdRms = speechBoundsResult.activeThresholdRms,
        )
    }

    private fun findSpeechBounds(
        samples: FloatArray,
        requireSpeechContrast: Boolean,
    ): SpeechBoundsResult {
        val frameSamples = (SAMPLE_RATE_HZ * ENERGY_FRAME_MS / 1000L).toInt()
        if (samples.size < frameSamples) {
            return SpeechBoundsResult(rejectReason = RejectReason.INPUT_TOO_SHORT)
        }

        val frameRms = mutableListOf<Pair<Int, Float>>()
        var start = 0
        var peakRms = 0f
        while (start < samples.size) {
            val end = min(start + frameSamples, samples.size)
            val rms = rms(samples, start, end)
            frameRms += start to rms
            peakRms = maxOf(peakRms, rms)
            start += frameSamples
        }
        val noiseFloorRms = noiseFloor(frameRms.map { it.second })
        val minPeakRms = if (requireSpeechContrast) VERIFY_MIN_PEAK_RMS else MIN_PEAK_RMS
        if (peakRms < minPeakRms) {
            return SpeechBoundsResult(
                rejectReason = RejectReason.PEAK_BELOW_MIN,
                peakRms = peakRms,
                noiseFloorRms = noiseFloorRms,
                activeThresholdRms = minPeakRms,
            )
        }

        val activeThreshold = if (requireSpeechContrast) {
            val minVerificationPeak = maxOf(
                VERIFY_MIN_PEAK_RMS,
                noiseFloorRms * VERIFY_MIN_PEAK_TO_FLOOR_RATIO,
                noiseFloorRms + VERIFY_MIN_PEAK_ABOVE_FLOOR_RMS,
            )
            if (peakRms < minVerificationPeak) {
                return SpeechBoundsResult(
                    rejectReason = RejectReason.PEAK_BELOW_FLOOR,
                    peakRms = peakRms,
                    noiseFloorRms = noiseFloorRms,
                    activeThresholdRms = minVerificationPeak,
                )
            }

            maxOf(
                VERIFY_MIN_ACTIVE_RMS,
                noiseFloorRms + (peakRms - noiseFloorRms) * VERIFY_ACTIVE_RMS_RANGE_RATIO,
            )
        } else {
            maxOf(MIN_ACTIVE_RMS, peakRms * ACTIVE_RMS_RATIO)
        }
        var firstActiveStart = -1
        var lastActiveEnd = -1
        frameRms.forEach { (frameStart, frameValue) ->
            if (frameValue >= activeThreshold) {
                if (firstActiveStart < 0) firstActiveStart = frameStart
                lastActiveEnd = min(frameStart + frameSamples, samples.size)
            }
        }
        if (firstActiveStart < 0 || lastActiveEnd <= firstActiveStart) {
            return SpeechBoundsResult(
                rejectReason = RejectReason.NO_ACTIVE_SPEECH,
                peakRms = peakRms,
                noiseFloorRms = noiseFloorRms,
                activeThresholdRms = activeThreshold,
            )
        }

        val marginSamples = (SAMPLE_RATE_HZ * SPEECH_EDGE_MARGIN_MS / 1000L).toInt()
        return SpeechBoundsResult(
            bounds = SpeechBounds(
                start = (firstActiveStart - marginSamples).coerceAtLeast(0),
                end = (lastActiveEnd + marginSamples).coerceAtMost(samples.size),
                activeSampleCount = lastActiveEnd - firstActiveStart,
            ),
            peakRms = peakRms,
            noiseFloorRms = noiseFloorRms,
            activeThresholdRms = activeThreshold,
        )
    }

    private fun noiseFloor(frameRms: List<Float>): Float {
        if (frameRms.isEmpty()) return 0f

        val sorted = frameRms.sorted()
        val index = (sorted.lastIndex * VERIFY_NOISE_FLOOR_PERCENTILE)
            .toInt()
            .coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun rms(samples: FloatArray, start: Int, end: Int): Float {
        if (end <= start) return 0f

        var sum = 0.0
        for (index in start until end) {
            val value = samples[index].toDouble()
            sum += value * value
        }
        return sqrt(sum / (end - start)).toFloat()
    }

    fun cosineSimilarity(left: FloatArray, right: FloatArray): Float {
        val count = min(left.size, right.size)
        if (count == 0) return 0f

        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in 0 until count) {
            val leftValue = left[index].toDouble()
            val rightValue = right[index].toDouble()
            dot += leftValue * rightValue
            leftNorm += leftValue * leftValue
            rightNorm += rightValue * rightValue
        }

        if (leftNorm == 0.0 || rightNorm == 0.0) return 0f
        return (dot / sqrt(leftNorm * rightNorm)).toFloat().coerceIn(-1f, 1f)
    }

    private fun getExtractor(context: Context): SpeakerEmbeddingExtractor {
        extractor?.let { return it }

        synchronized(initLock) {
            extractor?.let { return it }
            val created = SpeakerEmbeddingExtractor(
                assetManager = context.assets,
                config = SpeakerEmbeddingExtractorConfig(
                    model = OwnerVoiceStore.MODEL_ASSET_NAME,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
            )
            extractor = created
            return created
        }
    }
}
