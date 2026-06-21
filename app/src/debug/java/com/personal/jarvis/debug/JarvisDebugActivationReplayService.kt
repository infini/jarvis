package com.personal.jarvis.debug

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.personal.jarvis.CommandInterpreter
import com.personal.jarvis.LocalCommandRecognizer
import com.personal.jarvis.OwnerVoiceEngine
import com.personal.jarvis.PcmWavFile
import java.io.File

class JarvisDebugActivationReplayService : Service() {
    @Volatile private var replaying = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestId = intent
            ?.getStringExtra(JarvisDebugActivationReplayActivity.EXTRA_REQUEST_ID)
            .orEmpty()
        if (replaying) {
            Log.e(TAG, "request_id=$requestId status=failed reason=replay_already_running")
            return START_NOT_STICKY
        }

        replaying = true
        Thread({
            try {
                replayCaptures(requestId)
            } finally {
                replaying = false
                stopSelf(startId)
            }
        }, "JarvisActivationReplay").start()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun replayCaptures(requestId: String) {
        if (!LocalCommandRecognizer.isAvailable(this)) {
            Log.e(TAG, "request_id=$requestId status=failed reason=local_asr_unavailable")
            return
        }

        val dir = File(cacheDir, CAPTURE_DIR)
        val wavFiles = dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (wavFiles.isEmpty()) {
            Log.e(TAG, "request_id=$requestId status=failed reason=no_activation_captures")
            return
        }

        var phraseAcceptedCount = 0
        var ownerAcceptedCount = 0
        wavFiles.forEach { wavFile ->
            runCatching {
                val samples = PcmWavFile.readMono16(wavFile)
                val result = LocalCommandRecognizer.recognizeBufferedActivation(
                    context = this,
                    samples = samples,
                    endpoint = "debug_activation_replay",
                )
                val accepted = CommandInterpreter.isActivationWakeAsrEquivalent(result.text)
                val activationSamples = LocalCommandRecognizer.activationSamplesForResult(samples, result)
                val fullOwnerWindowedMatch = OwnerVoiceEngine.verifyOwnerBestWindow(this, samples)
                val candidateOwnerWindowedMatch = if (activationSamples !== samples) {
                    OwnerVoiceEngine.verifyOwnerBestWindow(this, activationSamples)
                } else {
                    null
                }
                val ownerWindowedMatch = if (
                    candidateOwnerWindowedMatch != null &&
                    isBetterOwnerWindow(candidateOwnerWindowedMatch, fullOwnerWindowedMatch)
                ) {
                    candidateOwnerWindowedMatch
                } else {
                    fullOwnerWindowedMatch
                }
                val ownerMatch = OwnerVoiceEngine.acceptActivationPhraseMatch(
                    ownerWindowedMatch.match,
                )
                if (accepted) phraseAcceptedCount += 1
                if (accepted && ownerMatch.accepted) ownerAcceptedCount += 1
                Log.i(
                    TAG,
                    "request_id=$requestId status=replay file=${wavFile.name} " +
                        "accepted=$accepted endpoint=${result.endpoint} text=${result.text} " +
                        "peakRms=${result.peakRms} meanRms=${result.meanRms} asrGain=${result.asrGain} " +
                        "ownerAccepted=${ownerMatch.accepted} ownerAcceptance=${ownerMatch.acceptance} " +
                        "ownerScore=${ownerMatch.score} ownerFullScore=${ownerWindowedMatch.fullMatch.score} " +
                        "ownerSpeechMs=${ownerMatch.activeSpeechMs} " +
                        "ownerWindowStartMs=${ownerWindowedMatch.windowStartMs} " +
                        "ownerWindowMs=${ownerWindowedMatch.windowDurationMs} " +
                        "ownerWindows=${ownerWindowedMatch.evaluatedWindows} " +
                        "ownerReason=${ownerMatch.rejectReason ?: "none"}",
                )
            }.onFailure {
                Log.e(
                    TAG,
                    "request_id=$requestId status=replay_failed file=${wavFile.name} " +
                        "reason=${it.javaClass.simpleName} message=${it.message}",
                )
            }
        }

        Log.i(
            TAG,
            "request_id=$requestId status=completed total=${wavFiles.size} " +
                "accepted=$ownerAcceptedCount phraseAccepted=$phraseAcceptedCount",
        )
    }

    companion object {
        private const val TAG = "JarvisDebugReplay"
        private const val CAPTURE_DIR = "jarvis-activation-attempts"
    }

    private fun isBetterOwnerWindow(
        candidate: OwnerVoiceEngine.WindowedMatch,
        current: OwnerVoiceEngine.WindowedMatch,
    ): Boolean {
        return when {
            candidate.match.accepted && !current.match.accepted -> true
            !candidate.match.accepted && current.match.accepted -> false
            candidate.match.score != current.match.score -> candidate.match.score > current.match.score
            else -> candidate.match.activeSpeechMs > current.match.activeSpeechMs
        }
    }
}
