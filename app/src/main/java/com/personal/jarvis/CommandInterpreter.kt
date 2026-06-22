package com.personal.jarvis

import java.util.Locale

object CommandInterpreter {
    data class PhotoCandidateDiagnostic(
        val normalized: String,
        val requireWakeWord: Boolean,
        val hasWakeWord: Boolean,
        val mentionsCamera: Boolean,
        val hasShotWord: Boolean,
        val hasPhotoShotAsrVariant: Boolean,
        val hasDirectShotAsrVariant: Boolean,
        val hasPhotoPartial: Boolean,
        val hasDirectPartial: Boolean,
        val parsedCommand: String?,
        val fastPartialCommand: String?,
    ) {
        val reason: String
            get() = when {
                normalized.isBlank() -> "blank"
                requireWakeWord && !hasWakeWord -> "missing_wake"
                parsedCommand == CommandBus.COMMAND_TAKE_PHOTO -> "take_photo_final"
                fastPartialCommand == CommandBus.COMMAND_TAKE_PHOTO -> "take_photo_partial"
                mentionsCamera && !hasAnyShotSignal -> "missing_shot"
                !mentionsCamera && !hasAnyDirectShotSignal -> "missing_photo_or_direct_shot"
                else -> "not_take_photo"
            }

        val hasAnyShotSignal: Boolean
            get() = hasShotWord ||
                hasPhotoShotAsrVariant ||
                hasDirectShotAsrVariant ||
                hasPhotoPartial ||
                hasDirectPartial

        private val hasAnyDirectShotSignal: Boolean
            get() = hasShotWord || hasDirectShotAsrVariant || hasDirectPartial
    }

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

        val hasPhotoShotAsrVariant = mentionsCamera && PHOTO_CONTEXT_SHOT_ASR_VARIANTS.any(normalized::contains)
        val hasDirectShotAsrVariant = !mentionsCamera && DIRECT_SHOT_ASR_VARIANTS.any(normalized::contains)
        val hasDirectShortShot = !mentionsCamera && normalized in DIRECT_SHORT_SHOT_PATTERNS
        val wantsShot = SHOT_WORDS.any(normalized::contains) ||
            PARTIAL_SHOT_PATTERNS.any(normalized::endsWith) ||
            hasPhotoShotAsrVariant ||
            hasDirectShotAsrVariant ||
            hasDirectShortShot
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
        val wantsStop = STOP_WORDS.any { word ->
            normalized.contains(word) && !(word == "꺼" && wantsShot)
        }
        val wantsFullStop = listOf(
            "자비스완전종료",
            "자비스서비스종료",
            "자비스앱종료",
            "자비스완전히꺼",
            "자비스완전히꺼줘",
            "jarvis완전종료",
        ).any(normalized::contains)
        val wantsCloseApp = CLOSE_APP_WORDS.any { word ->
            normalized.contains(word) && !(word == "꺼" && wantsShot)
        }

        return when {
            wantsFullStop -> CommandBus.COMMAND_STOP_SERVICE
            mentionsCamera && wantsCloseApp -> CommandBus.COMMAND_HOME
            wantsSleepScreen -> CommandBus.COMMAND_SLEEP_SCREEN
            wantsCloseApp && !wantsStop -> CommandBus.COMMAND_HOME
            wantsStop -> CommandBus.COMMAND_STOP_LISTENING
            wantsShot && wantsCameraOpen && !hasPhotoShotAsrVariant -> CommandBus.COMMAND_OPEN_CAMERA_AND_TAKE_PHOTO
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

    fun parseFastPartial(text: String, requireWakeWord: Boolean = true): String? {
        val command = parse(text, requireWakeWord)
        if (command != null) return command

        val normalized = normalize(text)
        if (normalized.isBlank()) return null
        if (requireWakeWord && !hasWakeWord(normalized)) return null

        return if (
            FAST_PARTIAL_PHOTO_SHOT_PATTERNS.any(normalized::endsWith) ||
            normalized in DIRECT_SHORT_SHOT_PATTERNS
        ) {
            CommandBus.COMMAND_TAKE_PHOTO
        } else {
            null
        }
    }

    fun photoCandidateDiagnostic(
        text: String,
        requireWakeWord: Boolean = true,
    ): PhotoCandidateDiagnostic {
        val normalized = normalize(text)
        val mentionsCamera = normalized.contains("카메라") ||
            normalized.contains("셀피") ||
            normalized.contains("셀카") ||
            normalized.contains("사진")
        val hasPhotoShotAsrVariant =
            mentionsCamera && PHOTO_CONTEXT_SHOT_ASR_VARIANTS.any(normalized::contains)
        val hasDirectShotAsrVariant =
            !mentionsCamera && DIRECT_SHOT_ASR_VARIANTS.any(normalized::contains)

        return PhotoCandidateDiagnostic(
            normalized = normalized,
            requireWakeWord = requireWakeWord,
            hasWakeWord = hasWakeWord(normalized),
            mentionsCamera = mentionsCamera,
            hasShotWord = SHOT_WORDS.any(normalized::contains),
            hasPhotoShotAsrVariant = hasPhotoShotAsrVariant,
            hasDirectShotAsrVariant = hasDirectShotAsrVariant,
            hasPhotoPartial = FAST_PARTIAL_PHOTO_SHOT_PATTERNS.any(normalized::endsWith),
            hasDirectPartial = normalized in DIRECT_SHORT_SHOT_PATTERNS,
            parsedCommand = parse(text, requireWakeWord),
            fastPartialCommand = parseFastPartial(text, requireWakeWord),
        )
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
        return WAKE_WORDS.any(normalized::contains) ||
            COMMAND_ASR_EQUIVALENT_WAKE_WORDS.any(normalized::startsWith)
    }

    private val FRONT_CAMERA_WORDS = listOf("셀피", "셀카", "전면", "앞카메라", "프론트카메라")
    private val REAR_CAMERA_WORDS = listOf("후면", "후방", "뒷카메라", "뒤카메라", "백카메라", "리어카메라")
    private val SHOT_WORDS = listOf(
        "찍어",
        "찍기",
        "촬영",
        "찰칵",
        "셔터",
        "찍자",
        "찍어줘",
        "찍어주세요",
        "촬영해줘",
        "촬영해주세요",
        "찰칵해줘",
        "셔터눌러",
        "셔터눌러줘",
    )
    private val DIRECT_SHOT_ASR_VARIANTS = listOf(
        "찌거",
        "찌꺼",
        "지거",
        "지꺼",
        "치거",
        "치꺼",
    )
    private val PHOTO_CONTEXT_SHOT_ASR_VARIANTS = DIRECT_SHOT_ASR_VARIANTS + listOf(
        "지켜",
        "치켜",
    )
    private val PARTIAL_SHOT_PATTERNS = listOf("사진찍", "사진찌")
    private val FAST_PARTIAL_PHOTO_SHOT_PATTERNS = PARTIAL_SHOT_PATTERNS + listOf("사진지", "사진치")
    private val STOP_WORDS = listOf("잠들어", "잠들어라", "멈춰", "중지", "꺼", "그만")
    private val CLOSE_APP_WORDS = listOf("종료", "닫아", "닫어", "꺼", "나가", "끝내")
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
    private val COMMAND_ASR_EQUIVALENT_WAKE_WORDS = listOf(
        "제이비스",
        "자비서",
        "자비써",
        "자비쓰",
        "자비수",
        "잡이스",
        "서비스",
    )
    private val ACTIVATION_ASR_EQUIVALENT_WAKE_WORDS = WAKE_WORDS + listOf("다비스")
    private val DIRECT_SHORT_SHOT_PATTERNS =
        (WAKE_WORDS + COMMAND_ASR_EQUIVALENT_WAKE_WORDS).flatMap { wakeWord ->
            listOf("${wakeWord}찍", "${wakeWord}찌")
        }.toSet()
}
