package com.personal.jarvis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CameraFacingLabelParserTest {
    @Test
    fun parsesXiaomiCurrentFacingSuffix() {
        assertEquals(
            CameraLauncher.CameraFacing.BACK,
            CameraFacingLabelParser.currentFacing("전후면 카메라 전환,후면"),
        )
        assertEquals(
            CameraLauncher.CameraFacing.FRONT,
            CameraFacingLabelParser.currentFacing("전후면 카메라 전환，전면"),
        )
    }

    @Test
    fun invertsEnglishSwitchTarget() {
        assertEquals(
            CameraLauncher.CameraFacing.BACK,
            CameraFacingLabelParser.currentFacing("Switch to front camera"),
        )
    }

    @Test
    fun invertsKoreanSwitchTarget() {
        assertEquals(
            CameraLauncher.CameraFacing.FRONT,
            CameraFacingLabelParser.currentFacing("후면 카메라로 전환"),
        )
    }

    @Test
    fun acceptsExplicitCurrentFacingState() {
        assertEquals(
            CameraLauncher.CameraFacing.FRONT,
            CameraFacingLabelParser.currentFacing("Front camera selected"),
        )
    }

    @Test
    fun rejectsAmbiguousOrEmptyLabels() {
        assertNull(CameraFacingLabelParser.currentFacing("Front camera"))
        assertNull(CameraFacingLabelParser.currentFacing(""))
    }
}
