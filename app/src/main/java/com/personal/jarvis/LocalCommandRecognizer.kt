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

object LocalCommandRecognizer {
    private const val TAG = "JarvisLocalCommand"
    private const val SAMPLE_RATE_HZ = 16000
    private const val READ_INTERVAL_MS = 60L
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

        try {
            recorder.startRecording()
            while (shouldContinue() && SystemClock.elapsedRealtime() - startedAt < timeoutMs) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue

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
                    return Result(command, text, SystemClock.elapsedRealtime() - startedAt)
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
            return Result(finalCommand, finalText, SystemClock.elapsedRealtime() - startedAt)
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
                numThreads = 2
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
