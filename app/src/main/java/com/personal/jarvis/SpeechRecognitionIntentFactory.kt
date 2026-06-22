package com.personal.jarvis

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent

object SpeechRecognitionIntentFactory {
    fun create(context: Context, commandWindowOpen: Boolean): Intent {
        val timing = timingFor(commandWindowOpen)
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                if (commandWindowOpen) {
                    RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH
                } else {
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                },
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            val biasingStrings = biasingStringsFor(commandWindowOpen)
            if (biasingStrings.isNotEmpty()) {
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_BIASING_STRINGS,
                    ArrayList(biasingStrings),
                )
            }
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                timing.minimumLengthMs,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                timing.possiblyCompleteSilenceMs,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                timing.completeSilenceMs,
            )
        }
    }

    fun biasingStringsFor(commandWindowOpen: Boolean): List<String> {
        if (!commandWindowOpen) return emptyList()

        return CommandCatalog.entries
            .flatMap { it.phrases }
            .distinct()
    }

    fun timingFor(commandWindowOpen: Boolean): TimingOptions {
        return if (commandWindowOpen) {
            TimingOptions(
                minimumLengthMs = COMMAND_INPUT_MINIMUM_LENGTH_MS,
                possiblyCompleteSilenceMs = COMMAND_POSSIBLY_COMPLETE_SILENCE_MS,
                completeSilenceMs = COMMAND_COMPLETE_SILENCE_MS,
            )
        } else {
            TimingOptions(
                minimumLengthMs = IDLE_WAKE_INPUT_MINIMUM_LENGTH_MS,
                possiblyCompleteSilenceMs = IDLE_WAKE_POSSIBLY_COMPLETE_SILENCE_MS,
                completeSilenceMs = IDLE_WAKE_COMPLETE_SILENCE_MS,
            )
        }
    }

    data class TimingOptions(
        val minimumLengthMs: Long,
        val possiblyCompleteSilenceMs: Long,
        val completeSilenceMs: Long,
    )

    private const val IDLE_WAKE_INPUT_MINIMUM_LENGTH_MS = 600L
    private const val IDLE_WAKE_POSSIBLY_COMPLETE_SILENCE_MS = 250L
    private const val IDLE_WAKE_COMPLETE_SILENCE_MS = 600L
    private const val COMMAND_INPUT_MINIMUM_LENGTH_MS = 180L
    private const val COMMAND_POSSIBLY_COMPLETE_SILENCE_MS = 90L
    private const val COMMAND_COMPLETE_SILENCE_MS = 180L
}
