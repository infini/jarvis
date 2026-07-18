package com.personal.jarvis

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandVoiceSampleMatcherTest {
    @Test
    fun commandSampleMatcherAcceptsCommandWithMultipleMatchingSamples() {
        val template = withSilence(patternedPhrase(durationMs = 1_200))
        val result = CommandVoiceSampleMatcher.matchSamples(
            samples = withSilence(patternedPhrase(durationMs = 1_200, scale = 0.90f)),
            templateSamplesByCommand = mapOf(
                "take_photo" to listOf(
                    template,
                    withSilence(patternedPhrase(durationMs = 1_200, scale = 0.95f)),
                ),
            ),
        )

        assertTrue(result.accepted, result.reason)
        assertEquals("take_photo", result.commandId)
    }

    @Test
    fun commandSampleMatcherRejectsCommandWithSingleSample() {
        val sample = withSilence(patternedPhrase(durationMs = 1_100))
        val result = CommandVoiceSampleMatcher.matchSamples(
            samples = sample,
            templateSamplesByCommand = mapOf("take_photo" to listOf(sample)),
        )

        assertFalse(result.accepted)
        assertNull(result.commandId)
        assertEquals(1, result.validExemplarCount)
        assertEquals("sample_count_below_min", result.reason)
    }

    @Test
    fun commandSampleMatcherRequiresTwoMatchingExemplars() {
        val matchingTemplate = withSilence(patternedPhrase(durationMs = 1_200))
        val mismatchingTemplate = withSilence(patternedPhrase(durationMs = 2_600))
        val result = CommandVoiceSampleMatcher.matchSamples(
            samples = withSilence(patternedPhrase(durationMs = 1_200, scale = 0.92f)),
            templateSamplesByCommand = mapOf(
                "take_photo" to listOf(matchingTemplate, mismatchingTemplate),
            ),
        )

        assertFalse(result.accepted)
        assertNull(result.commandId)
        assertEquals(2, result.validExemplarCount)
        assertEquals(2, result.matchedExemplarCount)
        assertTrue(result.secondExemplarDurationRatio < 0.72f)
        assertEquals("duration_ratio_below_min", result.reason)
    }

    @Test
    fun commandSampleMatcherPrefersTwoDurationValidExemplarsOverCloserDurationOutlier() {
        val candidate = withSilence(patternedPhrase(durationMs = 1_200))
        val matchingTemplate = withSilence(patternedPhrase(durationMs = 1_200, scale = 0.94f))
        val shiftedMatchingTemplate = withSilence(shiftedPatternedPhrase(durationMs = 1_200))
        val durationOutlier = withSilence(patternedPhrase(durationMs = 2_600))

        val shiftedMatch = CommandVoiceSampleMatcher.matchSamples(
            samples = candidate,
            templateSamplesByCommand = mapOf(
                "take_photo" to listOf(shiftedMatchingTemplate, shiftedMatchingTemplate),
            ),
        )
        val outlierMatch = CommandVoiceSampleMatcher.matchSamples(
            samples = candidate,
            templateSamplesByCommand = mapOf("take_photo" to listOf(durationOutlier, durationOutlier)),
        )
        val result = CommandVoiceSampleMatcher.matchSamples(
            samples = candidate,
            templateSamplesByCommand = mapOf(
                "take_photo" to listOf(
                    matchingTemplate,
                    shiftedMatchingTemplate,
                    durationOutlier,
                ),
            ),
        )

        assertTrue(
            outlierMatch.bestExemplarDistance < shiftedMatch.bestExemplarDistance,
            "The test fixture must keep the duration-invalid outlier closer than the second valid exemplar " +
                "(outlier=${outlierMatch.bestExemplarDistance}, valid=${shiftedMatch.bestExemplarDistance})",
        )
        assertTrue(outlierMatch.durationRatio < 0.72f)
        assertTrue(shiftedMatch.accepted, shiftedMatch.reason)
        assertTrue(result.accepted, result.reason)
        assertEquals("take_photo", result.commandId)
        assertEquals(3, result.validExemplarCount)
        assertEquals(3, result.matchedExemplarCount)
        assertTrue(result.durationRatio in 0.72f..1.45f)
        assertTrue(result.secondExemplarDurationRatio in 0.72f..1.45f)
    }

    @Test
    fun invalidCloserCommandDoesNotHideEligibleConsensus() {
        val candidate = withSilence(patternedPhrase(durationMs = 1_200))
        val result = CommandVoiceSampleMatcher.matchSamples(
            samples = candidate,
            templateSamplesByCommand = mapOf(
                "invalid_closer" to listOf(
                    withSilence(patternedPhrase(durationMs = 2_600)),
                    withSilence(patternedPhrase(durationMs = 2_600)),
                ),
                "take_photo" to listOf(
                    withSilence(patternedPhrase(durationMs = 1_200, scale = 0.94f)),
                    withSilence(shiftedPatternedPhrase(durationMs = 1_200)),
                ),
            ),
        )

        assertTrue(result.accepted, result.reason)
        assertEquals("take_photo", result.commandId)
    }

    @Test
    fun commandSampleMatcherDoesNotCountInvalidExemplar() {
        val validTemplate = withSilence(patternedPhrase(durationMs = 1_100))
        val invalidTemplate = FloatArray(validTemplate.size)
        val result = CommandVoiceSampleMatcher.matchSamples(
            samples = withSilence(patternedPhrase(durationMs = 1_100, scale = 0.94f)),
            templateSamplesByCommand = mapOf(
                "take_photo" to listOf(validTemplate, invalidTemplate),
            ),
        )

        assertFalse(result.accepted)
        assertNull(result.commandId)
        assertEquals(1, result.validExemplarCount)
        assertEquals(1, result.matchedExemplarCount)
        assertEquals("sample_count_below_min", result.reason)
    }

    @Test
    fun commandSampleMatcherRejectsAmbiguousCommands() {
        val sample = withSilence(patternedPhrase(durationMs = 1_100))
        val result = CommandVoiceSampleMatcher.matchSamples(
            samples = sample,
            templateSamplesByCommand = mapOf(
                "take_photo" to listOf(sample, sample),
                "open_camera" to listOf(sample, sample),
            ),
        )

        assertFalse(result.accepted)
        assertNull(result.commandId)
        assertEquals("ambiguous", result.reason)
    }

    @Test
    fun commandSampleMatcherRejectsCandidateWithShortDurationRatio() {
        val longTemplate = constantSignal(durationMs = 1_700)
        val shortCandidate = constantSignal(durationMs = 560)
        val result = CommandVoiceSampleMatcher.matchSamples(
            samples = shortCandidate,
            templateSamplesByCommand = mapOf("take_photo" to listOf(longTemplate, longTemplate)),
        )

        assertFalse(result.accepted)
        assertNull(result.commandId)
        assertEquals("duration_ratio_below_min", result.reason)
        assertTrue(result.durationRatio < 0.72f)
    }

    private fun withSilence(samples: FloatArray, silenceMs: Int = 120): FloatArray {
        val silenceSamples = OwnerVoiceEngine.SAMPLE_RATE_HZ * silenceMs / 1_000
        return FloatArray(samples.size + silenceSamples * 2) { index ->
            when (index) {
                in 0 until silenceSamples -> 0f
                in silenceSamples until silenceSamples + samples.size -> samples[index - silenceSamples]
                else -> 0f
            }
        }
    }

    private fun patternedPhrase(durationMs: Int, scale: Float = 1f): FloatArray {
        val sampleRate = OwnerVoiceEngine.SAMPLE_RATE_HZ
        val sampleCount = sampleRate * durationMs / 1_000
        return FloatArray(sampleCount) { index ->
            val progress = index.toFloat() / sampleCount
            val envelope = when {
                progress < 0.18f -> 0.08f
                progress < 0.34f -> 0.24f
                progress < 0.52f -> 0.13f
                progress < 0.74f -> 0.30f
                else -> 0.17f
            }
            val carrier = sin(2.0 * PI * 190.0 * index / sampleRate).toFloat()
            carrier * envelope * scale
        }
    }

    private fun shiftedPatternedPhrase(durationMs: Int): FloatArray {
        val sampleRate = OwnerVoiceEngine.SAMPLE_RATE_HZ
        val sampleCount = sampleRate * durationMs / 1_000
        return FloatArray(sampleCount) { index ->
            val progress = index.toFloat() / sampleCount
            val envelope = when {
                progress < 0.18f -> 0.30f
                progress < 0.34f -> 0.08f
                progress < 0.52f -> 0.24f
                progress < 0.74f -> 0.13f
                else -> 0.17f
            }
            val carrier = sin(2.0 * PI * 190.0 * index / sampleRate).toFloat()
            carrier * envelope
        }
    }

    private fun constantSignal(durationMs: Int): FloatArray {
        val sampleRate = OwnerVoiceEngine.SAMPLE_RATE_HZ
        val sampleCount = sampleRate * durationMs / 1_000
        return FloatArray(sampleCount) { 0.20f }
    }
}
