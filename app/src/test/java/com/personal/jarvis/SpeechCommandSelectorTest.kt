package com.personal.jarvis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpeechCommandSelectorTest {
    @Test
    fun finalSelectionUsesStrictParseForCompleteTopCandidate() {
        val selection = SpeechCommandSelector.selectFinal(
            listOf("자비스, 사진 찍어", "자비스, 사진 지"),
        )

        assertEquals(CommandBus.COMMAND_TAKE_PHOTO, selection?.command)
        assertEquals(SpeechCommandSelector.SOURCE_FINAL, selection?.source)
        assertEquals(1, selection?.candidateIndex)
        assertEquals("자비스, 사진 찍어", selection?.text)
    }

    @Test
    fun finalSelectionPrefersCompleteLaterCandidateOverClippedTopCandidate() {
        val selection = SpeechCommandSelector.selectFinal(
            listOf("자비스, 사진 지", "자비스, 카메라 실행"),
        )

        assertEquals(CommandBus.COMMAND_OPEN_CAMERA, selection?.command)
        assertEquals(SpeechCommandSelector.SOURCE_FINAL, selection?.source)
        assertEquals(2, selection?.candidateIndex)
        assertEquals("자비스, 카메라 실행", selection?.text)
    }

    @Test
    fun finalSelectionRecoversClippedPhotoCandidatesWhenStrictParseFails() {
        val selection = SpeechCommandSelector.selectFinal(
            listOf("자비스, 사진 지", "자비스, 사진 치"),
        )

        assertEquals(CommandBus.COMMAND_TAKE_PHOTO, selection?.command)
        assertEquals(SpeechCommandSelector.SOURCE_FINAL_FAST_PARTIAL, selection?.source)
        assertEquals(1, selection?.candidateIndex)
        assertEquals("자비스, 사진 지", selection?.text)
    }

    @Test
    fun finalSelectionKeepsCandidateIndexForRecoveredLaterCandidate() {
        val selection = SpeechCommandSelector.selectFinal(
            listOf("자비스, 지", "서비스, 사진 치"),
        )

        assertEquals(CommandBus.COMMAND_TAKE_PHOTO, selection?.command)
        assertEquals(SpeechCommandSelector.SOURCE_FINAL_FAST_PARTIAL, selection?.source)
        assertEquals(2, selection?.candidateIndex)
        assertEquals("서비스, 사진 치", selection?.text)
    }

    @Test
    fun topCandidateNegationVetoesLowerRankedPositiveCommand() {
        assertNull(
            SpeechCommandSelector.selectFinal(
                listOf("자비스 사진 찍지 마", "자비스 사진 찍어"),
            ),
        )
    }

    @Test
    fun politeOrLowerRankedNegationVetoesAllCandidates() {
        assertNull(
            SpeechCommandSelector.selectFinal(
                listOf("자비스 사진 찍지 마요", "자비스 사진 찍어"),
            ),
        )
        assertNull(
            SpeechCommandSelector.selectFinal(
                listOf("자비스 사진 지", "자비스 사진 찍지 마"),
            ),
        )
        assertNull(
            SpeechCommandSelector.selectFinal(
                listOf("자비스 사진 찍지 마 제발", "자비스 사진 찍어"),
            ),
        )
        assertNull(
            SpeechCommandSelector.selectFinal(
                listOf("자비스 사진 찍지 말라고", "자비스 사진 찍어"),
            ),
        )
    }

    @Test
    fun partialNegationIsNotACandidate() {
        assertNull(SpeechCommandSelector.selectPartial(listOf("자비스 사진 찍지 마")))
    }

    @Test
    fun partialSelectionUsesFastPartialSource() {
        val selection = SpeechCommandSelector.selectPartial(
            listOf("자비스, 사진 치"),
        )

        assertEquals(CommandBus.COMMAND_TAKE_PHOTO, selection?.command)
        assertEquals(SpeechCommandSelector.SOURCE_PARTIAL, selection?.source)
        assertEquals(1, selection?.candidateIndex)
    }

    @Test
    fun partialSelectionOnlyUsesTopCandidate() {
        val selection = SpeechCommandSelector.selectPartial(
            listOf("자비스, 사진 지워", "자비스, 사진 지"),
        )

        assertNull(selection)
    }

    @Test
    fun openCameraWaitsForFinalToAvoidPreemptingCompoundCommand() {
        assertNull(SpeechCommandSelector.selectPartial(listOf("자비스, 카메라 실행")))
        assertNull(SpeechCommandSelector.selectPartial(listOf("자비스, 카메라 실행하고 찍어")))

        val compound = SpeechCommandSelector.selectFinal(
            listOf("자비스, 카메라 실행하고 찍어"),
        )
        assertEquals(CommandBus.COMMAND_OPEN_CAMERA_AND_TAKE_PHOTO, compound?.command)
        assertEquals(SpeechCommandSelector.SOURCE_FINAL, compound?.source)
    }

    @Test
    fun selectionRejectsPhotoCandidatesWithoutWakeWord() {
        assertNull(SpeechCommandSelector.selectFinal(listOf("사진 지")))
        assertNull(SpeechCommandSelector.selectPartial(listOf("사진 지")))
    }
}
