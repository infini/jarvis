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
    private const val MIN_AUDIO_MS = 1200L
    private const val READ_INTERVAL_MS = 100L

    private val initLock = Any()
    private val computeLock = Any()
    @Volatile private var extractor: SpeakerEmbeddingExtractor? = null

    data class Match(
        val score: Float,
        val accepted: Boolean,
    )

    fun createEmbedding(context: Context, samples: FloatArray): FloatArray? {
        if (samples.size < SAMPLE_RATE_HZ * MIN_AUDIO_MS / 1000L) return null

        synchronized(computeLock) {
            val speakerExtractor = getExtractor(context.applicationContext)
            val stream = speakerExtractor.createStream()
            return try {
                stream.acceptWaveform(samples, SAMPLE_RATE_HZ)
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
    ): Match {
        val ownerEmbedding = OwnerVoiceStore.getEmbedding(context) ?: return Match(0f, accepted = false)
        val candidateEmbedding = createEmbedding(context, samples) ?: return Match(0f, accepted = false)
        val score = cosineSimilarity(ownerEmbedding, candidateEmbedding)
        return Match(score = score, accepted = score >= threshold)
    }

    @SuppressLint("MissingPermission")
    fun waitForOwnerMatch(
        context: Context,
        windowMs: Long,
        verificationIntervalMs: Long,
        shouldContinue: () -> Boolean,
        onMatch: (Match) -> Unit = {},
    ): Match? {
        val recorder = createRecorder()
        val readSize = (SAMPLE_RATE_HZ * READ_INTERVAL_MS / 1000L).toInt()
        val buffer = ShortArray(readSize)
        val maxWindowSamples = (SAMPLE_RATE_HZ * windowMs / 1000L).toInt()
        val chunks = ArrayDeque<FloatArray>()
        var totalSamples = 0
        var lastVerificationAt = 0L

        try {
            recorder.startRecording()
            while (shouldContinue()) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                val samples = FloatArray(read) { index -> buffer[index] / 32768.0f }
                chunks.addLast(samples)
                totalSamples += read

                while (chunks.isNotEmpty() && totalSamples - chunks.first.size >= maxWindowSamples) {
                    totalSamples -= chunks.removeFirst().size
                }

                val now = System.currentTimeMillis()
                if (totalSamples >= maxWindowSamples && now - lastVerificationAt >= verificationIntervalMs) {
                    lastVerificationAt = now
                    val match = verifyOwner(context, flattenLastSamples(chunks, maxWindowSamples, totalSamples))
                    onMatch(match)
                    if (match.accepted) return match
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }

        return null
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
