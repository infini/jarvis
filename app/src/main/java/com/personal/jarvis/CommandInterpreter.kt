package com.personal.jarvis

import java.util.Locale

object CommandInterpreter {
    fun parse(text: String): String? {
        val normalized = text
            .lowercase(Locale.KOREAN)
            .replace("\\s+".toRegex(), "")

        if (normalized.isBlank()) return null

        val mentionsCamera = normalized.contains("카메라") ||
            normalized.contains("셀피") ||
            normalized.contains("사진")

        val wantsShot = listOf("찍어", "촬영", "찰칵", "셔터", "찍자").any(normalized::contains)
        val wantsCameraOpen = mentionsCamera &&
            listOf("열어", "켜", "시작", "실행").any(normalized::contains)
        val wantsFilter = listOf("필터", "효과", "색감").any(normalized::contains)
        val wantsSwitchCamera = listOf("전면", "후면", "셀피", "카메라전환", "렌즈전환").any(normalized::contains)
        val wantsBack = listOf("뒤로", "백").any(normalized::contains)
        val wantsHome = listOf("홈", "홈으로").any(normalized::contains)
        val wantsStop = listOf("멈춰", "중지", "꺼", "그만").any(normalized::contains)

        return when {
            wantsStop -> CommandBus.COMMAND_STOP_LISTENING
            wantsShot && mentionsCamera -> CommandBus.COMMAND_OPEN_CAMERA_AND_TAKE_PHOTO
            wantsShot -> CommandBus.COMMAND_TAKE_PHOTO
            wantsCameraOpen -> CommandBus.COMMAND_OPEN_CAMERA
            wantsFilter -> CommandBus.COMMAND_OPEN_FILTERS
            wantsSwitchCamera -> CommandBus.COMMAND_SWITCH_CAMERA
            wantsBack -> CommandBus.COMMAND_BACK
            wantsHome -> CommandBus.COMMAND_HOME
            else -> null
        }
    }
}
