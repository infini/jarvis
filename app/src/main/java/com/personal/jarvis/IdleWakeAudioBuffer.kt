package com.personal.jarvis

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.sqrt

class IdleWakeAudioBuffer(
    private val windowMs: Long,
) {
    private val lock = Any()
    private val maxSamples = (OwnerVoiceEngine.SAMPLE_RATE_HZ * windowMs / 1000L).toInt()
    private val samples = FloatArray(maxSamples)
    @Volatile private var active = false
    @Volatile private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    private var writeIndex = 0
    private var sampleCount = 0
    private var peakRms = 0f
    private var rmsSum = 0.0
    private var rmsFrameCount = 0
    private var sourceLabel = "none"

    val isActive: Boolean
        get() = active

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (active) return true

        reset()
        val audioRecord = runCatching { createRecorder() }
            .onFailure { Log.w(TAG, "Idle wake audio buffer recorder unavailable: ${it.message}") }
            .getOrNull()
            ?: return false

        recorder = audioRecord
        active = true
        thread = Thread({
            val readSize = (OwnerVoiceEngine.SAMPLE_RATE_HZ * READ_INTERVAL_MS / 1000L).toInt()
            val buffer = ShortArray(readSize)
            try {
                audioRecord.startRecording()
                while (active && !Thread.currentThread().isInterrupted) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    append(buffer, read)
                }
            } catch (error: Exception) {
                if (active && recorder === audioRecord) {
                    Log.w(TAG, "Idle wake audio buffer stopped: ${error.message}")
                }
            } finally {
                runCatching { audioRecord.stop() }
                audioRecord.release()
                if (recorder === audioRecord) {
                    active = false
                    recorder = null
                }
            }
        }, "JarvisIdleWakeAudioBuffer").also { it.start() }
        Log.d(TAG, "Idle wake audio buffer started source=$sourceLabel windowMs=$windowMs")
        return true
    }

    fun stopAndSnapshot(): Snapshot {
        val snapshot = snapshot()
        active = false
        runCatching { recorder?.stop() }
        thread?.interrupt()
        thread = null
        return snapshot
    }

    fun stop() {
        stopAndSnapshot()
    }

    fun snapshot(): Snapshot {
        synchronized(lock) {
            val ordered = FloatArray(sampleCount)
            if (sampleCount > 0) {
                val start = if (sampleCount < samples.size) 0 else writeIndex
                for (index in 0 until sampleCount) {
                    ordered[index] = samples[(start + index) % samples.size]
                }
            }
            return Snapshot(
                samples = ordered,
                source = sourceLabel,
                durationMs = ordered.size * 1000L / OwnerVoiceEngine.SAMPLE_RATE_HZ,
                peakRms = peakRms,
                meanRms = if (rmsFrameCount > 0) (rmsSum / rmsFrameCount).toFloat() else 0f,
            )
        }
    }

    private fun append(buffer: ShortArray, read: Int) {
        val rms = frameRms(buffer, read)
        synchronized(lock) {
            peakRms = maxOf(peakRms, rms)
            rmsSum += rms
            rmsFrameCount += 1
            for (index in 0 until read) {
                samples[writeIndex] = buffer[index] / 32768.0f
                writeIndex = (writeIndex + 1) % samples.size
                sampleCount = minOf(sampleCount + 1, samples.size)
            }
        }
    }

    private fun reset() {
        synchronized(lock) {
            samples.fill(0f)
            writeIndex = 0
            sampleCount = 0
            peakRms = 0f
            rmsSum = 0.0
            rmsFrameCount = 0
            sourceLabel = "none"
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(): AudioRecord {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferBytes = AudioRecord.getMinBufferSize(
            OwnerVoiceEngine.SAMPLE_RATE_HZ,
            channelConfig,
            audioFormat,
        )
        require(minBufferBytes > 0) { "AudioRecord min buffer unavailable: $minBufferBytes" }

        val readSize = (OwnerVoiceEngine.SAMPLE_RATE_HZ * READ_INTERVAL_MS / 1000L).toInt()
        val bufferBytes = maxOf(minBufferBytes, readSize * BYTES_PER_SAMPLE * 4)
        for (source in RECORDER_SOURCES) {
            val candidate = AudioRecord(
                source.source,
                OwnerVoiceEngine.SAMPLE_RATE_HZ,
                channelConfig,
                audioFormat,
                bufferBytes,
            )
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                sourceLabel = source.label
                Log.d(TAG, "Idle wake AudioRecord initialized source=${source.label}")
                return candidate
            }
            candidate.release()
        }
        error("AudioRecord failed to initialize")
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

    data class Snapshot(
        val samples: FloatArray,
        val source: String,
        val durationMs: Long,
        val peakRms: Float,
        val meanRms: Float,
    )

    private data class RecorderSource(
        val source: Int,
        val label: String,
    )

    companion object {
        private const val TAG = "JarvisIdleWakeAudio"
        private const val READ_INTERVAL_MS = 40L
        private const val BYTES_PER_SAMPLE = 2
        private val RECORDER_SOURCES = listOf(
            RecorderSource(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION"),
            RecorderSource(MediaRecorder.AudioSource.MIC, "MIC"),
        )
    }
}
