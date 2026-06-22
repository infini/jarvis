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
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxResultsFor(commandWindowOpen))
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
        return if (commandWindowOpen) COMMAND_BIASING_STRINGS else emptyList()
    }

    fun timingFor(commandWindowOpen: Boolean): TimingOptions {
        return if (commandWindowOpen) COMMAND_TIMING else IDLE_WAKE_TIMING
    }

    fun maxResultsFor(commandWindowOpen: Boolean): Int {
        return if (commandWindowOpen) COMMAND_MAX_RESULTS else IDLE_WAKE_MAX_RESULTS
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
    private const val COMMAND_MAX_RESULTS = 8
    private const val IDLE_WAKE_MAX_RESULTS = 5

    private val COMMAND_TIMING = TimingOptions(
        minimumLengthMs = COMMAND_INPUT_MINIMUM_LENGTH_MS,
        possiblyCompleteSilenceMs = COMMAND_POSSIBLY_COMPLETE_SILENCE_MS,
        completeSilenceMs = COMMAND_COMPLETE_SILENCE_MS,
    )

    private val IDLE_WAKE_TIMING = TimingOptions(
        minimumLengthMs = IDLE_WAKE_INPUT_MINIMUM_LENGTH_MS,
        possiblyCompleteSilenceMs = IDLE_WAKE_POSSIBLY_COMPLETE_SILENCE_MS,
        completeSilenceMs = IDLE_WAKE_COMPLETE_SILENCE_MS,
    )

    private val PHOTO_BIAS_WAKE_WORDS = listOf(
        "자비스",
        "자베스",
        "쟈비스",
        "제비스",
        "차비스",
        "잡비스",
        "잡스",
        "자 비서",
        "자비서",
        "제이비스",
        "자비써",
        "자비쓰",
        "자비수",
        "잡이스",
        "서비스",
    )

    private val PHOTO_FULL_ENDINGS = listOf(
        "사진 찍어",
        "사진 찍어줘",
        "사진 찍어 줘",
        "사진 찍어주세요",
        "사진 찍어 주세요",
    )

    private val PHOTO_PREFIX_ENDINGS = listOf(
        "사진 찍",
        "사진 찌",
        "사진 지",
        "사진 치",
    )

    private val PHOTO_JOINED_BIAS_WAKE_WORDS = listOf(
        "자비스",
        "자베스",
        "쟈비스",
        "제이비스",
        "자비서",
        "서비스",
    )

    private val PHOTO_JOINED_ENDINGS = listOf(
        "사진찍어",
        "사진찍어줘",
        "사진찍어주세요",
        "사진찍",
        "사진찌",
        "사진지",
        "사진치",
    )

    private val DIRECT_SHORT_SHOT_ENDINGS = listOf("찍", "찌")

    private val GENERATED_PHOTO_BIASING_STRINGS = PHOTO_BIAS_WAKE_WORDS.flatMap { wakeWord ->
        PHOTO_FULL_ENDINGS.map { ending -> "$wakeWord $ending" } +
            PHOTO_PREFIX_ENDINGS.map { ending -> "$wakeWord $ending" } +
            DIRECT_SHORT_SHOT_ENDINGS.map { ending -> "$wakeWord $ending" }
    } + PHOTO_JOINED_BIAS_WAKE_WORDS.flatMap { wakeWord ->
        PHOTO_JOINED_ENDINGS.map { ending -> "$wakeWord $ending" }
    }

    private val PHOTO_ASR_VARIANT_BIAS_WAKE_WORDS = listOf(
        "자비스",
        "자비서",
        "제이비스",
        "서비스",
    )

    private val PHOTO_ASR_VARIANT_ENDINGS = listOf(
        "사진 찌거",
        "사진 찌꺼",
        "사진 지거",
        "사진 지꺼",
        "사진 치거",
        "사진 치꺼",
        "사진 찌겨",
        "사진 지겨",
        "사진 치겨",
        "사진 찍혀",
        "사진 지켜",
        "사진 치켜",
    )

    private val DIRECT_ASR_VARIANT_ENDINGS = listOf(
        "찌거",
        "찌꺼",
        "지거",
        "지꺼",
        "치거",
        "치꺼",
    )

    private val GENERATED_PHOTO_ASR_VARIANT_BIASING_STRINGS =
        PHOTO_ASR_VARIANT_BIAS_WAKE_WORDS.flatMap { wakeWord ->
            PHOTO_ASR_VARIANT_ENDINGS.map { ending -> "$wakeWord $ending" } +
                DIRECT_ASR_VARIANT_ENDINGS.map { ending -> "$wakeWord $ending" }
        }

    private val COMMAND_BIASING_STRINGS = CommandCatalog.entries
        .flatMap { it.phrases }
        .plus(GENERATED_PHOTO_BIASING_STRINGS)
        .plus(GENERATED_PHOTO_ASR_VARIANT_BIASING_STRINGS)
        .distinct()
}
