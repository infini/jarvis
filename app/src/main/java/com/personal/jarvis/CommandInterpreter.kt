package com.personal.jarvis

import java.util.Locale

object CommandInterpreter {
    fun parse(text: String, requireWakeWord: Boolean = true): String? {
        val normalized = normalize(text)

        if (normalized.isBlank()) return null
        if (requireWakeWord && !hasWakeWord(normalized)) return null

        val wantsFrontCamera = listOf("셀피", "셀카", "전면", "앞카메라", "프론트카메라").any(normalized::contains)
        val wantsRearCamera = listOf("후면", "후방", "뒷카메라", "뒤카메라", "백카메라", "리어카메라").any(normalized::contains)
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
            listOf("모드", "전환", "바꿔", "변경", "열어", "켜", "시작", "실행").any(normalized::contains)
        val wantsFilter = listOf("필터", "효과", "색감").any(normalized::contains)
        val wantsBack = listOf("뒤로", "백").any(normalized::contains)
        val wantsHome = listOf("홈", "홈으로").any(normalized::contains)
        val wantsWakeScreen = listOf("화면", "디스플레이", "폰", "휴대폰").any(normalized::contains) &&
            listOf("켜", "깨워", "켜줘", "켜라", "온").any(normalized::contains)
        val wantsStop = listOf("멈춰", "중지", "꺼", "그만").any(normalized::contains)
        val wantsCloseApp = listOf("종료", "닫아", "닫어", "꺼", "나가", "끝내").any(normalized::contains)

        return when {
            mentionsCamera && wantsCloseApp -> CommandBus.COMMAND_HOME
            wantsStop -> CommandBus.COMMAND_STOP_LISTENING
            wantsShot && mentionsCamera -> CommandBus.COMMAND_OPEN_CAMERA_AND_TAKE_PHOTO
            wantsShot -> CommandBus.COMMAND_TAKE_PHOTO
            wantsRearCamera && (mentionsCamera || wantsSpecificCameraMode) -> CommandBus.COMMAND_OPEN_REAR_CAMERA
            wantsFrontCamera && (mentionsCamera || wantsSpecificCameraMode) -> CommandBus.COMMAND_OPEN_FRONT_CAMERA
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

    fun isWakeOnly(text: String): Boolean {
        val normalized = normalize(text)
        if (!hasWakeWord(normalized)) return false

        val withoutWake = WAKE_WORDS.fold(normalized) { current, wakeWord ->
            current.replace(wakeWord, "")
        }
        return withoutWake.isBlank() || listOf("헤이", "hey", "하이").any { withoutWake == it }
    }

    private fun normalize(text: String): String {
        return text
            .lowercase(Locale.KOREAN)
            .replace("\\s+".toRegex(), "")
    }

    private fun hasWakeWord(normalized: String): Boolean {
        return WAKE_WORDS.any(normalized::contains)
    }

    private val WAKE_WORDS = listOf(
        "자비스",
        "자베스",
        "쟈비스",
        "제비스",
        "차비스",
        "jarvis",
    )
}
