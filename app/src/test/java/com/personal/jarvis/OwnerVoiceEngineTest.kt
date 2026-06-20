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
    fun preparesQuietWakeSpeechForVerification() {
        val sampleRate = OwnerVoiceEngine.SAMPLE_RATE_HZ
        val samples = FloatArray(sampleRate * 2)
        val speechStart = sampleRate / 2
        val speechSamples = sampleRate * 500 / 1000

        for (index in speechStart until speechStart + speechSamples) {
            samples[index] = if (index % 2 == 0) 0.003f else -0.003f
        }

        val prepared = assertNotNull(
            OwnerVoiceEngine.prepareSamplesForEmbedding(
                samples = samples,
                requireSpeechContrast = true,
            ),
        )

        assertTrue(prepared.activeSpeechMs in 475L..525L)
        assertTrue(prepared.samples.size >= sampleRate * 1200 / 1000)
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
    fun acceptsSoftWakeAfterFourConsecutiveLowOwnerScores() {
        val first = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.15f, activeSpeechMs = 500L),
            previousState = OwnerVoiceEngine.ConsecutiveAcceptState(),
        )
        val second = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.16f, activeSpeechMs = 500L),
            previousState = first.second,
        )
        val third = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.17f, activeSpeechMs = 500L),
            previousState = second.second,
        )
        val fourth = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.18f, activeSpeechMs = 500L),
            previousState = third.second,
        )

        assertFalse(first.first.accepted)
        assertFalse(second.first.accepted)
        assertFalse(third.first.accepted)
        assertTrue(fourth.first.accepted)
        assertEquals(OwnerVoiceEngine.Acceptance.SOFT_WAKE_CONSECUTIVE, fourth.first.acceptance)
    }

    @Test
    fun acceptsSingleSoftWakeWhenScoreAndSpeechAreEnough() {
        val result = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.25f, activeSpeechMs = 1000L),
            previousState = OwnerVoiceEngine.ConsecutiveAcceptState(),
        )

        assertTrue(result.first.accepted)
        assertEquals(OwnerVoiceEngine.Acceptance.SOFT_WAKE_SINGLE, result.first.acceptance)
    }

    @Test
    fun rejectsWeakSingleSoftWakeUntilScoresAreConsecutive() {
        val result = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.17f, activeSpeechMs = 675L),
            previousState = OwnerVoiceEngine.ConsecutiveAcceptState(),
        )

        assertFalse(result.first.accepted)
    }

    @Test
    fun rejectsSingleSoftWakeWhenWakeWordIsTooShort() {
        val result = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.17f, activeSpeechMs = 450L),
            previousState = OwnerVoiceEngine.ConsecutiveAcceptState(),
        )

        assertFalse(result.first.accepted)
    }

    @Test
    fun acceptsSoftWakeAcrossOneBridgeScore() {
        val first = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.15f, activeSpeechMs = 700L),
            previousState = OwnerVoiceEngine.ConsecutiveAcceptState(),
        )
        val bridge = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.11f, activeSpeechMs = 700L),
            previousState = first.second,
        )
        val second = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.16f, activeSpeechMs = 700L),
            previousState = bridge.second,
        )
        val third = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.17f, activeSpeechMs = 700L),
            previousState = second.second,
        )
        val fourth = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.18f, activeSpeechMs = 700L),
            previousState = third.second,
        )

        assertFalse(first.first.accepted)
        assertFalse(bridge.first.accepted)
        assertFalse(second.first.accepted)
        assertFalse(third.first.accepted)
        assertTrue(fourth.first.accepted)
        assertEquals(OwnerVoiceEngine.Acceptance.SOFT_WAKE_CONSECUTIVE, fourth.first.acceptance)
    }

    @Test
    fun resetsSoftWakeCountAfterTwoBridgeScores() {
        val first = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.15f, activeSpeechMs = 700L),
            previousState = OwnerVoiceEngine.ConsecutiveAcceptState(),
        )
        val bridge = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.11f, activeSpeechMs = 700L),
            previousState = first.second,
        )
        val reset = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.11f, activeSpeechMs = 700L),
            previousState = bridge.second,
        )
        val secondAfterReset = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.15f, activeSpeechMs = 700L),
            previousState = reset.second,
        )

        assertFalse(secondAfterReset.first.accepted)
    }

    @Test
    fun resetsSoftWakeCountWhenScoreDrops() {
        val first = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.15f, activeSpeechMs = 500L),
            previousState = OwnerVoiceEngine.ConsecutiveAcceptState(),
        )
        val reset = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.09f, activeSpeechMs = 500L),
            previousState = first.second,
        )
        val secondAfterReset = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.15f, activeSpeechMs = 500L),
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
