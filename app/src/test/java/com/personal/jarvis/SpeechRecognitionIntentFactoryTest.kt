package com.personal.jarvis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeechRecognitionIntentFactoryTest {
    @Test
    fun commandWindowUsesLowLatencySpeechTiming() {
        val timing = SpeechRecognitionIntentFactory.timingFor(commandWindowOpen = true)

        assertEquals(180L, timing.minimumLengthMs)
        assertEquals(90L, timing.possiblyCompleteSilenceMs)
        assertEquals(180L, timing.completeSilenceMs)
    }

    @Test
    fun idleWakeKeepsMoreConservativeSpeechTiming() {
        val commandTiming = SpeechRecognitionIntentFactory.timingFor(commandWindowOpen = true)
        val idleTiming = SpeechRecognitionIntentFactory.timingFor(commandWindowOpen = false)

        assertTrue(commandTiming.minimumLengthMs < idleTiming.minimumLengthMs)
        assertTrue(commandTiming.possiblyCompleteSilenceMs < idleTiming.possiblyCompleteSilenceMs)
        assertTrue(commandTiming.completeSilenceMs < idleTiming.completeSilenceMs)
    }

    @Test
    fun commandWindowBiasesRecognizerTowardSupportedCommandPhrases() {
        val biasingStrings = SpeechRecognitionIntentFactory.biasingStringsFor(commandWindowOpen = true)

        assertTrue("자비스 사진 찍어" in biasingStrings)
        assertTrue("자비스 사진 찍어 주세요" in biasingStrings)
        assertTrue("자비스 사진 찍" in biasingStrings)
        assertTrue("자비스 사진 찌" in biasingStrings)
        assertTrue("자비스 사진 찍어 줘" in biasingStrings)
        assertTrue("자비스 사진 지거" in biasingStrings)
        assertTrue("자비스 사진 치거" in biasingStrings)
        assertTrue("자비스 사진 지켜" in biasingStrings)
        assertTrue("제이비스 사진 찍어" in biasingStrings)
        assertTrue("서비스 사진 찍어" in biasingStrings)
        assertTrue("자비스 카메라 실행" in biasingStrings)
        assertEquals(biasingStrings.distinct(), biasingStrings)
    }

    @Test
    fun idleWakeDoesNotUseCommandBiasingStrings() {
        assertEquals(
            emptyList(),
            SpeechRecognitionIntentFactory.biasingStringsFor(commandWindowOpen = false),
        )
    }
}
