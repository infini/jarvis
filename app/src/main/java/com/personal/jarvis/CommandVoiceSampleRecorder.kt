package com.personal.jarvis

import android.content.Context

class CommandVoiceSampleRecorder(
    private val context: Context,
    private val postToMain: (() -> Unit) -> Unit,
    private val onProgress: (Int) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onCompleted: (CommandVoiceSampleStore.SampleInfo) -> Unit,
    private val onFailed: (String) -> Unit,
) {
    private val sessionLock = Any()
    private val sessionGeneration = SessionGeneration()
    @Volatile private var recording = false
    @Volatile private var activeCommandId: String? = null
    private var thread: Thread? = null

    val isRecording: Boolean
        get() = recording

    val recordingCommandId: String?
        get() = activeCommandId

    fun start(entry: CommandCatalog.Entry, durationMs: Long) {
        val token: Long
        val worker: Thread
        synchronized(sessionLock) {
            if (recording) return
            token = sessionGeneration.begin()
            recording = true
            activeCommandId = entry.commandId
            worker = Thread(
                { runRecording(token, entry, durationMs) },
                "JarvisCommandVoiceSampleRecorder",
            )
            thread = worker
        }

        onProgress(0)
        onStatus("녹음 중: '${entry.phrases.first()}'라고 또렷하게 말하세요.")
        worker.start()
    }

    fun stop() {
        val worker = synchronized(sessionLock) {
            sessionGeneration.invalidate()
            recording = false
            activeCommandId = null
            thread.also { thread = null }
        }
        worker?.interrupt()
    }

    private fun runRecording(token: Long, entry: CommandCatalog.Entry, durationMs: Long) {
        try {
            val samples = OwnerVoiceEngine.recordSamples(
                durationMs = durationMs,
                shouldContinue = { isActive(token, entry.commandId) },
                onProgress = { progress ->
                    postToMain {
                        if (isActive(token, entry.commandId)) {
                            val percent = (progress * 100f).toInt().coerceIn(0, 100)
                            onProgress(percent)
                            onStatus("녹음 중: $percent%")
                        }
                    }
                },
            )
            if (!isActive(token, entry.commandId)) return

            val summary = OwnerVoiceEngine.summarizeAudio(samples)
            if (summary.durationMs < MIN_RECORDING_DURATION_MS || summary.peakFrameRms < MIN_PEAK_RMS) {
                throw IllegalStateException("음성이 너무 짧거나 작습니다. 조용한 곳에서 조금 더 크게 다시 녹음하세요.")
            }

            val completion: Pair<Long, CommandVoiceSampleStore.SampleInfo> = synchronized(sessionLock) {
                if (!isActive(token, entry.commandId)) return
                val saved = CommandVoiceSampleStore.save(context, entry, samples)
                if (!sessionGeneration.tryComplete(token)) return
                recording = false
                activeCommandId = null
                if (thread === Thread.currentThread()) thread = null
                (token + 1L) to saved
            }
            postToMain {
                if (sessionGeneration.isCurrent(completion.first)) {
                    onProgress(100)
                    onCompleted(completion.second)
                }
            }
        } catch (error: Exception) {
            completeFailure(token, entry.commandId, error)
        }
    }

    private fun completeFailure(token: Long, commandId: String, error: Exception) {
        val completionToken = synchronized(sessionLock) {
            if (!isActive(token, commandId) || !sessionGeneration.tryComplete(token)) return
            recording = false
            activeCommandId = null
            if (thread === Thread.currentThread()) thread = null
            token + 1L
        }
        postToMain {
            if (sessionGeneration.isCurrent(completionToken)) {
                onFailed("음성 샘플 녹음 실패: ${error.message}")
            }
        }
    }

    private fun isActive(token: Long, commandId: String): Boolean =
        recording && activeCommandId == commandId && sessionGeneration.isCurrent(token)

    private companion object {
        private const val MIN_RECORDING_DURATION_MS = 700L
        private const val MIN_PEAK_RMS = 0.0012f
    }
}
