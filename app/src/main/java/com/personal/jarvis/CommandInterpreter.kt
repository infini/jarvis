package com.personal.jarvis

import java.util.Locale

object CommandInterpreter {
    fun parse(text: String, requireWakeWord: Boolean = true): String? {
        val normalized = normalize(text)

        if (normalized.isBlank()) return null
        if (requireWakeWord && !hasWakeWord(normalized)) return null

        val wantsFrontCamera = FRONT_CAMERA_WORDS.any(normalized::contains)
        val wantsRearCamera = REAR_CAMERA_WORDS.any(normalized::contains)
        val wantsSwitchCamera = listOf(
            "카메라전환",
            "렌즈전환",
            "전후면전환",
            "전면후면전환",
            "후면전면전환",
            "반전",
        ).any(normalized::contains)
        val mentionsCamera = normalized.contains("카메라") ||
            normalized.contains("셀피") ||
            normalized.contains("셀카") ||
            normalized.contains("사진")

        val wantsShot = listOf("찍어", "찍기", "촬영", "찰칵", "셔터", "찍자").any(normalized::contains)
        val wantsCameraOpen = mentionsCamera &&
            listOf("열어", "켜", "시작", "실행").any(normalized::contains)
        val wantsSpecificCameraMode = !wantsSwitchCamera &&
            wantsFrontCamera.xor(wantsRearCamera) &&
            (mentionsCamera ||
                listOf("모드", "전환", "바꿔", "변경", "열어", "켜", "시작", "실행").any(normalized::contains) ||
                FRONT_CAMERA_WORDS.any(normalized::endsWith) ||
                REAR_CAMERA_WORDS.any(normalized::endsWith))
        val wantsFilter = listOf("필터", "효과", "색감").any(normalized::contains)
        val wantsBack = listOf("뒤로", "백").any(normalized::contains)
        val wantsHome = listOf("홈", "홈으로").any(normalized::contains)
        val mentionsScreen = listOf("화면", "디스플레이").any(normalized::contains)
        val mentionsPhone = listOf("폰", "휴대폰").any(normalized::contains)
        val wantsWakeScreen = (mentionsScreen || mentionsPhone) &&
            listOf("켜", "깨워", "켜줘", "켜라", "온").any(normalized::contains)
        val wantsSleepScreen = (mentionsScreen && listOf("꺼", "끄", "오프").any(normalized::contains)) ||
            (mentionsPhone && listOf("잠가", "잠궈", "잠금", "락").any(normalized::contains))
        val wantsStop = listOf("잠들어", "잠들어라", "멈춰", "중지", "꺼", "그만").any(normalized::contains)
        val wantsCloseApp = listOf("종료", "닫아", "닫어", "꺼", "나가", "끝내").any(normalized::contains)

        return when {
            mentionsCamera && wantsCloseApp -> CommandBus.COMMAND_HOME
            wantsSleepScreen -> CommandBus.COMMAND_SLEEP_SCREEN
            wantsCloseApp && !wantsStop -> CommandBus.COMMAND_HOME
            wantsStop -> CommandBus.COMMAND_STOP_LISTENING
            wantsShot && mentionsCamera -> CommandBus.COMMAND_OPEN_CAMERA_AND_TAKE_PHOTO
            wantsShot -> CommandBus.COMMAND_TAKE_PHOTO
            wantsRearCamera && wantsSpecificCameraMode -> CommandBus.COMMAND_OPEN_REAR_CAMERA
            wantsFrontCamera && wantsSpecificCameraMode -> CommandBus.COMMAND_OPEN_FRONT_CAMERA
            wantsCameraOpen && wantsFrontCamera -> CommandBus.COMMAND_OPEN_FRONT_CAMERA
            wantsCameraOpen && wantsRearCamera -> CommandBus.COMMAND_OPEN_REAR_CAMERA
            wantsCameraOpen -> CommandBus.COMMAND_OPEN_CAMERA
            wantsFilter -> CommandBus.COMMAND_OPEN_FILTERS
            wantsSwitchCamera -> CommandBus.COMMAND_SWITCH_CAMERA
            wantsBack -> CommandBus.COMMAND_BACK
            wantsHome -> CommandBus.COMMAND_HOME
            wantsWakeScreen -> CommandBus.COMMAND_WAKE_SCREEN
            else -> null
        }
    }

    fun isActivationWake(text: String): Boolean {
        return isActivationWakeWithWords(text, WAKE_WORDS, ACTIVATION_WORDS)
    }

    fun isActivationWakeAsrEquivalent(text: String): Boolean {
        return isActivationWakeWithWords(
            text = text,
            wakeWords = ACTIVATION_ASR_EQUIVALENT_WAKE_WORDS,
            activationWords = ACTIVATION_ASR_EQUIVALENT_WORDS,
        )
    }

    private fun isActivationWakeWithWords(
        text: String,
        wakeWords: List<String>,
        activationWords: List<String>,
    ): Boolean {
        val normalized = normalize(text)
        return wakeWords.any { wakeWord ->
            activationWords.any { activationWord ->
                normalized == wakeWord + activationWord
            }
        }
    }

    fun containsActivationWake(text: String): Boolean {
        val normalized = normalize(text)
        return ACTIVATION_ASR_EQUIVALENT_WAKE_WORDS.any { wakeWord ->
            ACTIVATION_ASR_EQUIVALENT_WORDS.any { activationWord ->
                normalized.contains(wakeWord + activationWord)
            }
        }
    }

    private fun normalize(text: String): String {
        return text
            .lowercase(Locale.KOREAN)
            .replace("[^\\p{L}\\p{N}]+".toRegex(), "")
    }

    private fun hasWakeWord(normalized: String): Boolean {
        return WAKE_WORDS.any(normalized::contains)
    }

    private val FRONT_CAMERA_WORDS = listOf("셀피", "셀카", "전면", "앞카메라", "프론트카메라")
    private val REAR_CAMERA_WORDS = listOf("후면", "후방", "뒷카메라", "뒤카메라", "백카메라", "리어카메라")
    private val ACTIVATION_WORDS = listOf("깨어나")
    private val ACTIVATION_ASR_EQUIVALENT_WORDS = ACTIVATION_WORDS + listOf("게임", "때어나")

    private val WAKE_WORDS = listOf(
        "자비스",
        "자베스",
        "쟈비스",
        "제비스",
        "차비스",
        "잡비스",
        "잡스",
        "jarvis",
    )
    private val ACTIVATION_ASR_EQUIVALENT_WAKE_WORDS = WAKE_WORDS + listOf("다비스")
}
