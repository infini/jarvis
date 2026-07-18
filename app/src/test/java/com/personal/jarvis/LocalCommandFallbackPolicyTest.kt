package com.personal.jarvis

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalCommandFallbackPolicyTest {
    @Test
    fun allowsFallbackForBlankFinalText() {
        assertTrue(LocalCommandRecognizer.isVoiceSampleFallbackAllowed(""))
    }

    @Test
    fun allowsFallbackWhenFinalTextRetainsWakeWord() {
        assertTrue(LocalCommandRecognizer.isVoiceSampleFallbackAllowed("자비스 사진"))
    }

    @Test
    fun rejectsFallbackForExplicitNegation() {
        assertFalse(LocalCommandRecognizer.isVoiceSampleFallbackAllowed("자비스 사진 찍지 마"))
        assertFalse(LocalCommandRecognizer.isVoiceSampleFallbackAllowed("자비스 취소"))
    }

    @Test
    fun rejectsFallbackWithoutWakeWord() {
        assertFalse(LocalCommandRecognizer.isVoiceSampleFallbackAllowed("사진 찍어"))
    }
}
