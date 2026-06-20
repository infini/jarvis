package com.personal.jarvis

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnerVoiceEngineTest {
    @Test
    fun preparesShortSpeechByTrimmingSilenceAndPaddingMinimumLength() {
        val sampleRate = OwnerVoiceEngine.SAMPLE_RATE_HZ
        val samples = FloatArray(sampleRate * 2)
        val speechStart = sampleRate / 2
        val speechSamples = sampleRate * 700 / 1000

        for (index in speechStart until speechStart + speechSamples) {
            samples[index] = if (index % 2 == 0) 0.08f else -0.08f
        }

        val prepared = assertNotNull(OwnerVoiceEngine.prepareSamplesForEmbedding(samples))

        assertTrue(prepared.activeSpeechMs in 675L..725L)
        assertTrue(prepared.samples.size >= sampleRate * 1200 / 1000)
        assertTrue(prepared.samples.size < samples.size)
    }

    @Test
    fun rejectsSilenceOnlySamples() {
        val samples = FloatArray(OwnerVoiceEngine.SAMPLE_RATE_HZ * 2)

        assertNull(OwnerVoiceEngine.prepareSamplesForEmbedding(samples))
    }

    @Test
    fun rejectsVeryShortNoisePulse() {
        val sampleRate = OwnerVoiceEngine.SAMPLE_RATE_HZ
        val samples = FloatArray(sampleRate * 2)
        val speechStart = sampleRate / 2
        val speechSamples = sampleRate * 200 / 1000

        for (index in speechStart until speechStart + speechSamples) {
            samples[index] = if (index % 2 == 0) 0.08f else -0.08f
        }

        assertNull(OwnerVoiceEngine.prepareSamplesForEmbedding(samples))
    }
}
