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
}
