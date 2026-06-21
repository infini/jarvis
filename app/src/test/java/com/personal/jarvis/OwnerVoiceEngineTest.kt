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
    fun preparesQuietEnrollmentSpeechForLowDeviceGain() {
        val sampleRate = OwnerVoiceEngine.SAMPLE_RATE_HZ
        val samples = FloatArray(sampleRate * 6) { index ->
            if (index % 2 == 0) 0.00035f else -0.00035f
        }
        val speechStart = sampleRate / 2
        val speechSamples = sampleRate * 4800 / 1000

        for (index in speechStart until speechStart + speechSamples) {
            samples[index] = if (index % 2 == 0) 0.00175f else -0.00175f
        }

        val prepared = assertNotNull(OwnerVoiceEngine.prepareSamplesForEmbedding(samples))

        assertTrue(prepared.activeSpeechMs in 4775L..4825L)
        assertTrue(prepared.samples.size < samples.size)
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
    fun preparesVeryQuietWakeSpeechForVerification() {
        val sampleRate = OwnerVoiceEngine.SAMPLE_RATE_HZ
        val samples = FloatArray(sampleRate * 2) { index ->
            if (index % 2 == 0) 0.00035f else -0.00035f
        }
        val speechStart = sampleRate / 2
        val speechSamples = sampleRate * 520 / 1000

        for (index in speechStart until speechStart + speechSamples) {
            samples[index] = if (index % 2 == 0) 0.00105f else -0.00105f
        }

        val prepared = assertNotNull(
            OwnerVoiceEngine.prepareSamplesForEmbedding(
                samples = samples,
                requireSpeechContrast = true,
            ),
        )

        assertTrue(prepared.activeSpeechMs in 500L..550L)
        assertTrue(prepared.samples.size >= sampleRate * 1200 / 1000)
    }

    @Test
    fun preparesVerificationSpeechWhenNoiseFloorIsRaised() {
        val sampleRate = OwnerVoiceEngine.SAMPLE_RATE_HZ
        val samples = FloatArray(sampleRate * 2) { index ->
            if (index % 2 == 0) 0.0010f else -0.0010f
        }
        val speechStart = sampleRate / 2
        val speechSamples = sampleRate * 900 / 1000

        for (index in speechStart until speechStart + speechSamples) {
            samples[index] = if (index % 2 == 0) 0.00165f else -0.00165f
        }

        val prepared = assertNotNull(
            OwnerVoiceEngine.prepareSamplesForEmbedding(
                samples = samples,
                requireSpeechContrast = true,
            ),
        )

        assertTrue(prepared.activeSpeechMs in 875L..925L)
        assertTrue(prepared.samples.size >= sampleRate * 1200 / 1000)
    }

    @Test
    fun preparesSubSecondWakeWindowForVerification() {
        val sampleRate = OwnerVoiceEngine.SAMPLE_RATE_HZ
        val samples = FloatArray(sampleRate * 800 / 1000)
        val speechStart = sampleRate * 180 / 1000
        val speechSamples = sampleRate * 520 / 1000

        for (index in speechStart until speechStart + speechSamples) {
            samples[index] = if (index % 2 == 0) 0.003f else -0.003f
        }

        val prepared = assertNotNull(
            OwnerVoiceEngine.prepareSamplesForEmbedding(
                samples = samples,
                requireSpeechContrast = true,
            ),
        )

        assertTrue(prepared.activeSpeechMs in 500L..550L)
        assertEquals(sampleRate * 1200 / 1000, prepared.samples.size)
    }

    @Test
    fun rejectsLowContrastNoiseForVerification() {
        val samples = FloatArray(OwnerVoiceEngine.SAMPLE_RATE_HZ * 2) { index ->
            if (index % 2 == 0) 0.00095f else -0.00095f
        }

        assertNull(
            OwnerVoiceEngine.prepareSamplesForEmbedding(
                samples = samples,
                requireSpeechContrast = true,
            ),
        )
    }

    @Test
    fun createsShortEnrollmentSegmentsFromLongRecording() {
        val sampleRate = OwnerVoiceEngine.SAMPLE_RATE_HZ
        val samples = FloatArray(sampleRate * 6)

        val segments = OwnerVoiceEngine.enrollmentSegments(samples)

        assertEquals(7, segments.size)
        segments.forEach { segment ->
            assertEquals(sampleRate * 1400 / 1000, segment.size)
        }
    }

    @Test
    fun acceptsHighConfidenceSingleScoreWhenOwnerProfileHasMultipleEmbeddings() {
        val result = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(
                score = 0.37f,
                activeSpeechMs = 520L,
                ownerEmbeddingCount = OwnerVoiceStore.MIN_CONFIGURED_EMBEDDINGS,
            ),
            previousState = OwnerVoiceEngine.ConsecutiveAcceptState(),
        )

        assertTrue(result.first.accepted)
        assertEquals(OwnerVoiceEngine.Acceptance.HIGH_CONFIDENCE_SINGLE, result.first.acceptance)
    }

    @Test
    fun rejectsHighConfidenceSingleScoreWhenOwnerProfileIsLegacy() {
        val result = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.37f, activeSpeechMs = 520L, ownerEmbeddingCount = 1),
            previousState = OwnerVoiceEngine.ConsecutiveAcceptState(),
        )

        assertFalse(result.first.accepted)
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
            match = ownerMatch(score = 0.16f, activeSpeechMs = 850L),
            previousState = OwnerVoiceEngine.ConsecutiveAcceptState(),
        )

        assertTrue(result.first.accepted)
        assertEquals(OwnerVoiceEngine.Acceptance.SOFT_WAKE_SINGLE, result.first.acceptance)
    }

    @Test
    fun rejectsSingleSoftWakeWhenSpeechIsTooShortForLowerThreshold() {
        val result = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.21f, activeSpeechMs = 500L),
            previousState = OwnerVoiceEngine.ConsecutiveAcceptState(),
        )

        assertFalse(result.first.accepted)
    }

    @Test
    fun rejectsWeakSingleSoftWakeUntilScoresAreConsecutive() {
        val result = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.15f, activeSpeechMs = 900L),
            previousState = OwnerVoiceEngine.ConsecutiveAcceptState(),
        )

        assertFalse(result.first.accepted)
    }

    @Test
    fun rejectsWeakSingleSoftWakeWhenWakeWordIsShort() {
        val result = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.17f, activeSpeechMs = 450L),
            previousState = OwnerVoiceEngine.ConsecutiveAcceptState(),
        )

        assertFalse(result.first.accepted)
    }

    @Test
    fun rejectsRelaxedOwnerScoresWhenPeakRmsIsTooLowForActivationAsr() {
        val result = OwnerVoiceEngine.applyConsecutiveAcceptPolicy(
            match = ownerMatch(score = 0.18f, activeSpeechMs = 1200L, peakRms = 0.0025f),
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

    @Test
    fun activationPhraseOwnerMatchAcceptsNearScoreWithoutSoftWakePolicy() {
        val result = OwnerVoiceEngine.acceptActivationPhraseMatch(
            ownerMatch(
                score = 0.29f,
                activeSpeechMs = 650L,
                ownerEmbeddingCount = OwnerVoiceStore.MIN_CONFIGURED_EMBEDDINGS,
            ),
        )

        assertTrue(result.accepted)
        assertEquals(OwnerVoiceEngine.Acceptance.NEAR_CONSECUTIVE, result.acceptance)
    }

    @Test
    fun activationPhraseOwnerMatchRejectsSoftWakeOnlyScore() {
        val result = OwnerVoiceEngine.acceptActivationPhraseMatch(
            ownerMatch(
                score = 0.17f,
                activeSpeechMs = 900L,
                ownerEmbeddingCount = OwnerVoiceStore.MIN_CONFIGURED_EMBEDDINGS,
            ),
        )

        assertFalse(result.accepted)
    }

    @Test
    fun activationPhraseOwnerMatchRejectsLowPeakNearScore() {
        val result = OwnerVoiceEngine.acceptActivationPhraseMatch(
            ownerMatch(
                score = 0.31f,
                activeSpeechMs = 900L,
                ownerEmbeddingCount = OwnerVoiceStore.MIN_CONFIGURED_EMBEDDINGS,
                peakRms = 0.0025f,
            ),
        )

        assertFalse(result.accepted)
    }

    @Test
    fun activationPhraseOwnerMatchRejectsLegacyProfile() {
        val result = OwnerVoiceEngine.acceptActivationPhraseMatch(
            ownerMatch(score = 0.31f, activeSpeechMs = 900L, ownerEmbeddingCount = 1),
        )

        assertFalse(result.accepted)
    }

    private fun ownerMatch(
        score: Float,
        activeSpeechMs: Long,
        ownerEmbeddingCount: Int = 0,
        peakRms: Float = 0.01f,
    ): OwnerVoiceEngine.Match {
        return OwnerVoiceEngine.Match(
            score = score,
            accepted = false,
            activeSpeechMs = activeSpeechMs,
            ownerEmbeddingCount = ownerEmbeddingCount,
            peakRms = peakRms,
        )
    }
}
