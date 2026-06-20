package com.personal.jarvis

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent

object SpeechRecognitionIntentFactory {
    fun create(context: Context, commandWindowOpen: Boolean): Intent {
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
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                if (commandWindowOpen) COMMAND_INPUT_MINIMUM_LENGTH_MS else DEFAULT_INPUT_MINIMUM_LENGTH_MS,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                if (commandWindowOpen) COMMAND_POSSIBLY_COMPLETE_SILENCE_MS else DEFAULT_POSSIBLY_COMPLETE_SILENCE_MS,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                if (commandWindowOpen) COMMAND_COMPLETE_SILENCE_MS else DEFAULT_COMPLETE_SILENCE_MS,
            )
        }
    }

    private const val DEFAULT_INPUT_MINIMUM_LENGTH_MS = 1200L
    private const val DEFAULT_POSSIBLY_COMPLETE_SILENCE_MS = 700L
    private const val DEFAULT_COMPLETE_SILENCE_MS = 1000L
    private const val COMMAND_INPUT_MINIMUM_LENGTH_MS = 700L
    private const val COMMAND_POSSIBLY_COMPLETE_SILENCE_MS = 450L
    private const val COMMAND_COMPLETE_SILENCE_MS = 900L
}
