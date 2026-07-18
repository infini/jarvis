package com.personal.jarvis

import java.util.Locale

/** Resolves whether a camera switch label describes the current lens or its tap target. */
internal object CameraFacingLabelParser {
    fun currentFacing(contentDescription: String): CameraLauncher.CameraFacing? {
        if (contentDescription.isBlank()) return null

        val segments = contentDescription.split(',', '，')
        if (segments.size > 1) {
            simpleFacing(segments.last())?.let { return it }
        }

        val normalized = normalize(contentDescription)
        actionTarget(normalized)?.let { target ->
            return when (target) {
                CameraLauncher.CameraFacing.FRONT -> CameraLauncher.CameraFacing.BACK
                CameraLauncher.CameraFacing.BACK -> CameraLauncher.CameraFacing.FRONT
            }
        }

        return explicitCurrentFacing(normalized)
    }

    private fun simpleFacing(value: String): CameraLauncher.CameraFacing? {
        return when (normalize(value)) {
            "front", "frontcamera", "selfie", "selfiecamera", "전면", "전면카메라", "셀피", "셀카",
            "前置", "前置摄像头", "前置攝像頭", "前置鏡頭" -> CameraLauncher.CameraFacing.FRONT
            "rear", "rearcamera", "back", "backcamera", "후면", "후방", "후면카메라", "후방카메라",
            "后置", "後置", "后置摄像头", "後置攝像頭", "後置鏡頭" -> CameraLauncher.CameraFacing.BACK
            else -> null
        }
    }

    private fun actionTarget(value: String): CameraLauncher.CameraFacing? {
        return when {
            FRONT_TARGET_PHRASES.any(value::contains) -> CameraLauncher.CameraFacing.FRONT
            BACK_TARGET_PHRASES.any(value::contains) -> CameraLauncher.CameraFacing.BACK
            else -> null
        }
    }

    private fun explicitCurrentFacing(value: String): CameraLauncher.CameraFacing? {
        return when {
            FRONT_CURRENT_PHRASES.any(value::contains) -> CameraLauncher.CameraFacing.FRONT
            BACK_CURRENT_PHRASES.any(value::contains) -> CameraLauncher.CameraFacing.BACK
            else -> null
        }
    }

    private fun normalize(value: String): String {
        return value
            .lowercase(Locale.KOREAN)
            .replace(NON_WORD_SEPARATOR, "")
    }

    private val NON_WORD_SEPARATOR = "[\\s\\p{Punct}，]+".toRegex()
    private val FRONT_TARGET_PHRASES = listOf(
        "switchtofront",
        "switchtoselfie",
        "changetofront",
        "전면으로전환",
        "전면카메라로전환",
        "셀피카메라로전환",
        "切换到前置",
        "切換到前置",
        "切换至前置",
        "切換至前置",
    )
    private val BACK_TARGET_PHRASES = listOf(
        "switchtorear",
        "switchtoback",
        "changetorear",
        "changetoback",
        "후면으로전환",
        "후면카메라로전환",
        "후방카메라로전환",
        "切换到后置",
        "切換到後置",
        "切换至后置",
        "切換至後置",
    )
    private val FRONT_CURRENT_PHRASES = listOf(
        "currentfrontcamera",
        "frontcameraselected",
        "frontfacing",
        "현재전면",
        "전면카메라선택됨",
    )
    private val BACK_CURRENT_PHRASES = listOf(
        "currentrearcamera",
        "currentbackcamera",
        "rearcameraselected",
        "backcameraselected",
        "rearfacing",
        "backfacing",
        "현재후면",
        "현재후방",
        "후면카메라선택됨",
    )
}
