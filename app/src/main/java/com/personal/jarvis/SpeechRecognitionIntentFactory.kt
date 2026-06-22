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
            .plus(ADDITIONAL_COMMAND_BIASING_STRINGS)
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
    private val ADDITIONAL_COMMAND_BIASING_STRINGS = listOf(
        "자비스 사진 찍",
        "자비스 사진 찌",
        "자비스 찍",
        "자비스 찌",
        "자베스 찍",
        "자베스 찌",
        "쟈비스 찍",
        "쟈비스 찌",
        "제비스 찍",
        "제비스 찌",
        "차비스 찍",
        "차비스 찌",
        "잡비스 찍",
        "잡비스 찌",
        "잡스 찍",
        "잡스 찌",
        "자비스 사진 찍어 줘",
        "자비스 사진 찌거",
        "자비스 사진 찌꺼",
        "자비스 사진 지거",
        "자비스 사진 지꺼",
        "자비스 사진 치거",
        "자비스 사진 치꺼",
        "자비스 사진 지켜",
        "자비스 사진 치켜",
        "자비스 찌거",
        "자비스 찌꺼",
        "자비스 지거",
        "자비스 지꺼",
        "자비스 치거",
        "자비스 치꺼",
        "자 비서 사진 찍어",
        "자 비서 사진 찍",
        "자 비서 찍",
        "자비서 사진 찍어",
        "자비서 사진 찍",
        "자비서 찍",
        "자비서 찌",
        "제이비스 사진 찍어",
        "제이비스 사진 찍",
        "제이비스 사진 찌",
        "제이비스 찍",
        "제이비스 찌",
        "자비써 사진 찍어",
        "자비써 사진 찍",
        "자비써 찍",
        "자비써 찌",
        "자비쓰 사진 찍어",
        "자비쓰 사진 찍",
        "자비쓰 찍",
        "자비쓰 찌",
        "자비수 찍",
        "자비수 찌",
        "잡이스 찍",
        "잡이스 찌",
        "서비스 사진 찍어",
        "서비스 사진 찍",
        "서비스 사진 찌",
        "서비스 찍",
        "서비스 찌",
    )
}
