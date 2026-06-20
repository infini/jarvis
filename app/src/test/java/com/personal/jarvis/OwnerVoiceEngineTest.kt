package com.personal.jarvis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun acceptsNearMatchAfterTwoConsecutiveScores() {
        val first = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.29f, activeSpeechMs = 650L),
            previousState = OwnerVoiceEngine.ConsecutiveAcceptState(),
        )
        val second = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.30f, activeSpeechMs = 650L),
            previousState = first.second,
        )

        assertFalse(first.first.accepted)
        assertTrue(second.first.accepted)
        assertEquals(OwnerVoiceEngine.Acceptance.NEAR_CONSECUTIVE, second.first.acceptance)
    }

    @Test
    fun acceptsSoftWakeAfterTwoConsecutiveLowOwnerScores() {
        val first = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.17f, activeSpeechMs = 500L),
            previousState = OwnerVoiceEngine.ConsecutiveAcceptState(),
        )
        val second = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.18f, activeSpeechMs = 500L),
            previousState = first.second,
        )

        assertFalse(first.first.accepted)
        assertTrue(second.first.accepted)
        assertEquals(OwnerVoiceEngine.Acceptance.SOFT_WAKE_CONSECUTIVE, second.first.acceptance)
    }

    @Test
    fun acceptsSingleSoftWakeWhenScoreAndSpeechAreEnough() {
        val result = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.21f, activeSpeechMs = 1000L),
            previousState = OwnerVoiceEngine.ConsecutiveAcceptState(),
        )

        assertTrue(result.first.accepted)
        assertEquals(OwnerVoiceEngine.Acceptance.SOFT_WAKE_SINGLE, result.first.acceptance)
    }

    @Test
    fun resetsSoftWakeCountWhenScoreDrops() {
        val first = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.17f, activeSpeechMs = 500L),
            previousState = OwnerVoiceEngine.ConsecutiveAcceptState(),
        )
        val reset = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.10f, activeSpeechMs = 500L),
            previousState = first.second,
        )
        val secondAfterReset = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.17f, activeSpeechMs = 500L),
            previousState = reset.second,
        )

        assertFalse(secondAfterReset.first.accepted)
    }

    private fun ownerMatch(score: Float, activeSpeechMs: Long): OwnerVoiceEngine.Match {
        return OwnerVoiceEngine.Match(
            score = score,
            accepted = false,
            activeSpeechMs = activeSpeechMs,
        )
    }
}
