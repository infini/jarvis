package com.personal.jarvis.debug

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.personal.jarvis.CommandCatalog
import com.personal.jarvis.CommandRecognitionCaptureStore
import com.personal.jarvis.CommandVoiceSampleMatcher
import com.personal.jarvis.CommandVoiceSampleStore
import com.personal.jarvis.LocalCommandRecognizer
import com.personal.jarvis.PcmWavFile

class JarvisDebugCommandReplayService : Service() {
    @Volatile private var replaying = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestId = intent
            ?.getStringExtra(JarvisDebugCommandReplayActivity.EXTRA_REQUEST_ID)
            .orEmpty()
        if (replaying) {
            Log.e(TAG, "request_id=$requestId status=failed reason=replay_already_running")
            return START_NOT_STICKY
        }

        replaying = true
        Thread({
            try {
                replayCommandData(requestId)
            } finally {
                replaying = false
                stopSelf(startId)
            }
        }, "JarvisCommandReplay").start()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun replayCommandData(requestId: String) {
        if (!LocalCommandRecognizer.isAvailable(this)) {
            Log.e(TAG, "request_id=$requestId status=failed reason=local_asr_unavailable")
            return
        }

        val sampleSummary = diagnoseSavedSamples(requestId)
        val captureSummary = replayCommandCaptures(requestId)
        Log.i(
            TAG,
            "request_id=$requestId status=completed " +
                "captures=${captureSummary.total} captureLocalParsed=${captureSummary.localParsed} " +
                "captureSampleAccepted=${captureSummary.sampleAccepted} " +
                "captureSampleRejected=${captureSummary.sampleRejected} " +
                "samples=${sampleSummary.total} sampleAccepted=${sampleSummary.accepted} " +
                "sampleCorrect=${sampleSummary.correct} sampleMismatch=${sampleSummary.mismatch} " +
                "sampleRejected=${sampleSummary.rejected}",
        )
    }

    private fun replayCommandCaptures(requestId: String): CaptureSummary {
        val wavFiles = CommandRecognitionCaptureStore.captureFiles(this)
        if (wavFiles.isEmpty()) {
            Log.w(TAG, "request_id=$requestId status=no_command_captures")
            return CaptureSummary()
        }

        var localParsed = 0
        var sampleAccepted = 0
        wavFiles.forEach { wavFile ->
            runCatching {
                val samples = PcmWavFile.readMono16(wavFile)
                val localResult = LocalCommandRecognizer.recognizeBufferedCommand(
                    context = this,
                    samples = samples,
                    endpoint = "debug_command_capture_replay",
                )
                val sampleResult = CommandVoiceSampleMatcher.match(this, samples)
                if (localResult.command != null) localParsed += 1
                if (sampleResult.accepted) sampleAccepted += 1
                Log.i(
                    TAG,
                    "request_id=$requestId status=capture file=${wavFile.name} " +
                        "localCommand=${localResult.command.orEmpty()} localEndpoint=${localResult.endpoint} " +
                        "localText=${sanitize(localResult.text)} localPeakRms=${localResult.peakRms} " +
                        "localMeanRms=${localResult.meanRms} localAsrGain=${localResult.asrGain} " +
                        "sampleAccepted=${sampleResult.accepted} sampleCommand=${sampleResult.commandId.orEmpty()} " +
                        "sampleDistance=${sampleResult.distance} sampleNext=${sampleResult.nextCommandDistance} " +
                        "sampleRatio=${sampleResult.durationRatio} sampleReason=${sampleResult.reason}",
                )
            }.onFailure {
                Log.e(
                    TAG,
                    "request_id=$requestId status=capture_failed file=${wavFile.name} " +
                        "reason=${it.javaClass.simpleName} message=${sanitize(it.message.orEmpty())}",
                )
            }
        }
        return CaptureSummary(
            total = wavFiles.size,
            localParsed = localParsed,
            sampleAccepted = sampleAccepted,
        )
    }

    private fun diagnoseSavedSamples(requestId: String): SampleSummary {
        var total = 0
        var accepted = 0
        var correct = 0
        var mismatch = 0
        CommandCatalog.entries.forEach { entry ->
            val files = CommandVoiceSampleStore.sampleFiles(this, entry.commandId)
            Log.i(
                TAG,
                "request_id=$requestId status=sample_count command=${entry.commandId} count=${files.size}",
            )
            files.forEach { file ->
                total += 1
                runCatching {
                    val samples = PcmWavFile.readMono16(file)
                    val result = CommandVoiceSampleMatcher.match(this, samples)
                    val isCorrect = result.accepted && result.commandId == entry.commandId
                    if (result.accepted) accepted += 1
                    if (isCorrect) correct += 1
                    if (result.accepted && !isCorrect) mismatch += 1
                    Log.i(
                        TAG,
                        "request_id=$requestId status=sample file=${file.name} expected=${entry.commandId} " +
                            "accepted=${result.accepted} command=${result.commandId.orEmpty()} correct=$isCorrect " +
                            "distance=${result.distance} next=${result.nextCommandDistance} " +
                            "ratio=${result.durationRatio} reason=${result.reason}",
                    )
                }.onFailure {
                    Log.e(
                        TAG,
                        "request_id=$requestId status=sample_failed file=${file.name} expected=${entry.commandId} " +
                            "reason=${it.javaClass.simpleName} message=${sanitize(it.message.orEmpty())}",
                    )
                }
            }
        }
        return SampleSummary(
            total = total,
            accepted = accepted,
            correct = correct,
            mismatch = mismatch,
            rejected = total - accepted,
        )
    }

    private fun sanitize(value: String): String {
        return value
            .replace(Regex("\\s+"), "_")
            .replace("|", "/")
            .take(MAX_LOG_TEXT_CHARS)
    }

    private data class CaptureSummary(
        val total: Int = 0,
        val localParsed: Int = 0,
        val sampleAccepted: Int = 0,
    ) {
        val sampleRejected: Int
            get() = total - sampleAccepted
    }

    private data class SampleSummary(
        val total: Int = 0,
        val accepted: Int = 0,
        val correct: Int = 0,
        val mismatch: Int = 0,
        val rejected: Int = 0,
    )

    companion object {
        private const val TAG = "JarvisDebugCommandReplay"
        private const val MAX_LOG_TEXT_CHARS = 80
    }
}
