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
    fun commandWindowRequestsMoreFinalAlternativesThanIdleWake() {
        assertEquals(8, SpeechRecognitionIntentFactory.maxResultsFor(commandWindowOpen = true))
        assertEquals(5, SpeechRecognitionIntentFactory.maxResultsFor(commandWindowOpen = false))
    }

    @Test
    fun commandWindowBiasesRecognizerTowardSupportedCommandPhrases() {
        val biasingStrings = SpeechRecognitionIntentFactory.biasingStringsFor(commandWindowOpen = true)

        assertTrue("자비스 사진 찍어" in biasingStrings)
        assertTrue("자베스 사진 찍어" in biasingStrings)
        assertTrue("쟈비스 사진 찍어" in biasingStrings)
        assertTrue("자 비서 사진 찍어줘" in biasingStrings)
        assertTrue("잡이스 사진 찍어 줘" in biasingStrings)
        assertTrue("자비스 사진 찍어 주세요" in biasingStrings)
        assertTrue("자비스 사진 찍" in biasingStrings)
        assertTrue("자비스 사진 찌" in biasingStrings)
        assertTrue("자비스 사진 지" in biasingStrings)
        assertTrue("자비스 사진 치" in biasingStrings)
        assertTrue("자비스 찍" in biasingStrings)
        assertTrue("자비스 찌" in biasingStrings)
        assertTrue("자베스 사진 찍" in biasingStrings)
        assertTrue("자베스 사진 찌" in biasingStrings)
        assertTrue("자베스 사진 지" in biasingStrings)
        assertTrue("자베스 찍" in biasingStrings)
        assertTrue("쟈비스 사진 찍" in biasingStrings)
        assertTrue("쟈비스 사진 치" in biasingStrings)
        assertTrue("쟈비스 찌" in biasingStrings)
        assertTrue("잡스 사진 지" in biasingStrings)
        assertTrue("잡스 찍" in biasingStrings)
        assertTrue("자비스 사진 찍어 줘" in biasingStrings)
        assertTrue("자비스 사진 지거" in biasingStrings)
        assertTrue("자비스 사진 치거" in biasingStrings)
        assertTrue("자비스 사진 지켜" in biasingStrings)
        assertTrue("자비스 찌거" in biasingStrings)
        assertTrue("자비스 치거" in biasingStrings)
        assertTrue("자 비서 사진 찌" in biasingStrings)
        assertTrue("자 비서 찌" in biasingStrings)
        assertTrue("자비서 사진 찍" in biasingStrings)
        assertTrue("자비서 사진 지" in biasingStrings)
        assertTrue("자비서 사진 치" in biasingStrings)
        assertTrue("자비서 찍" in biasingStrings)
        assertTrue("자비서 찌" in biasingStrings)
        assertTrue("제이비스 사진 찍어" in biasingStrings)
        assertTrue("제이비스 사진 찍" in biasingStrings)
        assertTrue("제이비스 사진 찌" in biasingStrings)
        assertTrue("제이비스 사진 지" in biasingStrings)
        assertTrue("제이비스 사진 치" in biasingStrings)
        assertTrue("제이비스 찍" in biasingStrings)
        assertTrue("제이비스 찌" in biasingStrings)
        assertTrue("서비스 사진 찍어" in biasingStrings)
        assertTrue("서비스 사진 찍" in biasingStrings)
        assertTrue("서비스 사진 찌" in biasingStrings)
        assertTrue("서비스 사진 지" in biasingStrings)
        assertTrue("서비스 사진 치" in biasingStrings)
        assertTrue("서비스 찍" in biasingStrings)
        assertTrue("서비스 찌" in biasingStrings)
        assertTrue("자비수 사진 지" in biasingStrings)
        assertTrue("잡이스 사진 찍" in biasingStrings)
        assertTrue("잡이스 사진 치" in biasingStrings)
        assertTrue("잡이스 찍" in biasingStrings)
        assertTrue("자비스 카메라 실행" in biasingStrings)
        assertEquals(biasingStrings.distinct(), biasingStrings)
    }

    @Test
    fun commandWindowBiasingStringsAreAcceptedByCommandParsers() {
        SpeechRecognitionIntentFactory.biasingStringsFor(commandWindowOpen = true).forEach { phrase ->
            assertTrue(
                actual = CommandInterpreter.parse(phrase) != null ||
                    CommandInterpreter.parseFastPartial(phrase) != null,
                message = phrase,
            )
        }
    }

    @Test
    fun commandWindowBiasCountMatchesLiveCheckStaleApkGuard() {
        assertEquals(
            196,
            SpeechRecognitionIntentFactory.biasingStringsFor(commandWindowOpen = true).size,
        )
    }

    @Test
    fun idleWakeDoesNotUseCommandBiasingStrings() {
        assertEquals(
            emptyList(),
            SpeechRecognitionIntentFactory.biasingStringsFor(commandWindowOpen = false),
        )
    }
}
