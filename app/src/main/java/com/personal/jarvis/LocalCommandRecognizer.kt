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
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlin.math.sqrt

object LocalCommandRecognizer {
    private const val TAG = "JarvisLocalCommand"
    private const val SAMPLE_RATE_HZ = 16000
    private const val READ_INTERVAL_MS = 40L
    private const val LOCAL_SPEECH_RMS_THRESHOLD = 0.012f
    private const val LOCAL_MIN_ACTIVE_SPEECH_MS = 160L
    private const val LOCAL_TRAILING_SILENCE_MS = 320L
    private const val LOCAL_EARLY_ENDPOINT_MIN_LISTEN_MS = 720L
    private const val MODEL_DIR = "sherpa-korean-streaming"
    private const val ENCODER = "$MODEL_DIR/encoder-epoch-99-avg-1.int8.onnx"
    private const val DECODER = "$MODEL_DIR/decoder-epoch-99-avg-1.onnx"
    private const val JOINER = "$MODEL_DIR/joiner-epoch-99-avg-1.int8.onnx"
    private const val TOKENS = "$MODEL_DIR/tokens.txt"
    private val REQUIRED_ASSETS = listOf(ENCODER, DECODER, JOINER, TOKENS)

    private val initLock = Any()
    @Volatile private var recognizer: OnlineRecognizer? = null
    @Volatile private var warmUpStarted = false

    data class Result(
        val command: String?,
        val text: String,
        val elapsedMs: Long,
        val unavailable: Boolean = false,
        val endpoint: String = "",
        val activeSpeechMs: Long = 0L,
        val trailingSilenceMs: Long = 0L,
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
                Log.d(TAG, "Local command recognizer warmed up")
            }.onFailure {
                Log.w(TAG, "Local command recognizer warm-up failed: ${it.message}")
            }
        }, "JarvisLocalCommandWarmup").start()
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
        val buffer = ShortArray(readSize)
        var lastText = ""
        var speechSeen = false
        var activeSpeechMs = 0L
        var lastSpeechAtMs = 0L
        var endpoint = "timeout"
        var finalTrailingSilenceMs = 0L

        try {
            recorder.startRecording()
            while (shouldContinue() && SystemClock.elapsedRealtime() - startedAt < timeoutMs) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                val now = SystemClock.elapsedRealtime()

                if (frameRms(buffer, read) >= LOCAL_SPEECH_RMS_THRESHOLD) {
                    speechSeen = true
                    activeSpeechMs += READ_INTERVAL_MS
                    lastSpeechAtMs = now
                }

                val samples = FloatArray(read) { index -> buffer[index] / 32768.0f }
                stream.acceptWaveform(samples, SAMPLE_RATE_HZ)
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
            return Result(
                command = finalCommand,
                text = finalText,
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                endpoint = endpoint,
                activeSpeechMs = activeSpeechMs,
                trailingSilenceMs = trailing,
            )
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            stream.release()
        }
    }

    private fun commandFromText(text: String): String? {
        if (text.isBlank()) return null
        return CommandInterpreter.parse(text = text, requireWakeWord = false)
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

            val transducer = OnlineTransducerModelConfig().apply {
                encoder = ENCODER
                decoder = DECODER
                joiner = JOINER
            }
            val onlineModel = OnlineModelConfig().apply {
                this.transducer = transducer
                tokens = TOKENS
                numThreads = 4
                debug = false
                provider = "cpu"
                modelType = "zipformer"
            }
            val config = OnlineRecognizerConfig().apply {
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE_HZ, featureDim = 80)
                modelConfig = onlineModel
                decodingMethod = "greedy_search"
                maxActivePaths = 4
                enableEndpoint = false
            }

            return OnlineRecognizer(context.assets, config).also {
                recognizer = it
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(): AudioRecord {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, channelConfig, audioFormat)
        require(minBufferBytes > 0) { "AudioRecord buffer size is not available: $minBufferBytes" }

        val readSize = (SAMPLE_RATE_HZ * READ_INTERVAL_MS / 1000L).toInt()
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE_HZ,
            channelConfig,
            audioFormat,
            maxOf(minBufferBytes, readSize * Short.SIZE_BYTES * 2),
        ).also {
            require(it.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord was not initialized" }
        }
    }

    private fun assetExists(context: Context, assetPath: String): Boolean {
        return runCatching {
            context.assets.open(assetPath).use { true }
        }.getOrDefault(false)
    }
}
