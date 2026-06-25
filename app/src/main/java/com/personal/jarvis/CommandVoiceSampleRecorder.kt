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
    @Volatile private var recording = false
    @Volatile private var activeCommandId: String? = null
    private var thread: Thread? = null

    val isRecording: Boolean
        get() = recording

    val recordingCommandId: String?
        get() = activeCommandId

    fun start(entry: CommandCatalog.Entry, durationMs: Long) {
        if (recording) return

        recording = true
        activeCommandId = entry.commandId
        onProgress(0)
        onStatus("녹음 중: '${entry.phrases.first()}'라고 또렷하게 말하세요.")

        thread = Thread({
            try {
                val samples = OwnerVoiceEngine.recordSamples(
                    durationMs = durationMs,
                    shouldContinue = { recording && activeCommandId == entry.commandId },
                    onProgress = { progress ->
                        postToMain {
                            if (recording && activeCommandId == entry.commandId) {
                                val percent = (progress * 100f).toInt().coerceIn(0, 100)
                                onProgress(percent)
                                onStatus("녹음 중: $percent%")
                            }
                        }
                    },
                )

                if (!recording || activeCommandId != entry.commandId) return@Thread

                val summary = OwnerVoiceEngine.summarizeAudio(samples)
                if (summary.durationMs < MIN_RECORDING_DURATION_MS || summary.peakFrameRms < MIN_PEAK_RMS) {
                    throw IllegalStateException("음성이 너무 짧거나 작습니다. 조용한 곳에서 조금 더 크게 다시 녹음하세요.")
                }

                val saved = CommandVoiceSampleStore.save(context, entry, samples)
                postToMain {
                    if (recording && activeCommandId == entry.commandId) {
                        recording = false
                        activeCommandId = null
                        thread = null
                        onProgress(100)
                        onCompleted(saved)
                    }
                }
            } catch (e: Exception) {
                if (recording && activeCommandId == entry.commandId) {
                    postToMain {
                        if (recording && activeCommandId == entry.commandId) {
                            recording = false
                            activeCommandId = null
                            thread = null
                            onFailed("음성 샘플 녹음 실패: ${e.message}")
                        }
                    }
                }
            }
        }, "JarvisCommandVoiceSampleRecorder").also { it.start() }
    }

    fun stop() {
        recording = false
        activeCommandId = null
        thread?.interrupt()
        thread = null
    }

    private companion object {
        private const val MIN_RECORDING_DURATION_MS = 700L
        private const val MIN_PEAK_RMS = 0.0012f
    }
}
