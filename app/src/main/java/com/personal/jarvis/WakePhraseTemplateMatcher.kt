package com.personal.jarvis

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

object WakePhraseTemplateMatcher {
    private const val TAG = "JarvisWakeTemplate"
    private const val ENROLLMENT_WAV_NAME = "jarvis-owner-enroll-last.wav"
    private const val SAMPLE_RATE_HZ = OwnerVoiceEngine.SAMPLE_RATE_HZ
    private const val FRAME_MS = 40L
    private const val HOP_MS = 20L
    private const val MIN_TEMPLATE_MS = 700L
    private const val MAX_TEMPLATE_MS = 2200L
    private const val MIN_CANDIDATE_MS = 500L
    private const val MAX_CANDIDATE_MS = 2600L
    private const val SPEECH_GAP_MERGE_MS = 500L
    private const val SPEECH_END_GAP_MS = 200L
    private const val EDGE_MARGIN_MS = 200L
    private const val MIN_CANDIDATE_PEAK_RMS = 0.00030f
    private const val MIN_ACCEPT_PEAK_RMS = 0.00120f
    private const val MIN_TEMPLATE_PEAK_RMS = 0.010f
    private const val ACTIVE_FLOOR_RATIO = 0.18f
    private const val ACTIVE_PEAK_RATIO = 0.12f
    private const val ACCEPT_DISTANCE = 0.27f
    private const val DTW_BAND_RATIO = 0.35f
    private const val ZCR_WEIGHT = 0.30f
    private const val DELTA_WEIGHT = 1.0f

    @Volatile private var cachedTemplates: TemplateSet? = null

    data class Result(
        val accepted: Boolean,
        val distance: Float = Float.POSITIVE_INFINITY,
        val templateCount: Int = 0,
        val candidateCount: Int = 0,
        val candidateStartMs: Long = 0L,
        val candidateDurationMs: Long = 0L,
        val peakRms: Float = 0f,
        val reason: String = "",
    )

    private data class TemplateSet(
        val fileModifiedAt: Long,
        val fileSize: Long,
        val templates: List<List<FrameStats>>,
    )

    private data class FrameStats(
        val rms: Float,
        val zeroCrossingRate: Float,
    )

    private data class Feature(
        val logRms: Float,
        val deltaLogRms: Float,
        val zeroCrossingRate: Float,
    )

    private data class Segment(
        val startFrame: Int,
        val endFrame: Int,
    ) {
        val durationMs: Long
            get() = (endFrame - startFrame + 1) * HOP_MS
    }

    fun invalidateCache() {
        cachedTemplates = null
    }

    fun match(context: Context, samples: FloatArray): Result {
        val templates = loadTemplates(context)
            ?: return Result(accepted = false, reason = "template_unavailable")
        if (templates.templates.isEmpty()) {
            return Result(accepted = false, reason = "template_empty")
        }

        val frames = frameStats(samples)
        if (frames.isEmpty()) {
            return Result(
                accepted = false,
                templateCount = templates.templates.size,
                reason = "input_empty",
            )
        }

        val peakRms = frames.maxOf { it.rms }
        if (peakRms < MIN_CANDIDATE_PEAK_RMS) {
            return Result(
                accepted = false,
                templateCount = templates.templates.size,
                peakRms = peakRms,
                reason = "peak_below_min",
            )
        }

        val candidates = speechSegments(
            frames = frames,
            minimumDurationMs = MIN_CANDIDATE_MS,
            maximumDurationMs = MAX_CANDIDATE_MS,
            minimumPeakRms = MIN_CANDIDATE_PEAK_RMS,
        )
        if (candidates.isEmpty()) {
            return Result(
                accepted = false,
                templateCount = templates.templates.size,
                peakRms = peakRms,
                reason = "candidate_empty",
            )
        }

        var bestDistance = Float.POSITIVE_INFINITY
        var bestSegment = candidates.first()
        candidates.forEach { candidate ->
            val candidateFrames = frames.subList(candidate.startFrame, candidate.endFrame + 1)
            templates.templates.forEach { template ->
                val distance = dtwDistance(candidateFrames, template)
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestSegment = candidate
                }
            }
        }

        val accepted = bestDistance <= ACCEPT_DISTANCE && peakRms >= MIN_ACCEPT_PEAK_RMS
        val result = Result(
            accepted = accepted,
            distance = bestDistance,
            templateCount = templates.templates.size,
            candidateCount = candidates.size,
            candidateStartMs = bestSegment.startFrame * HOP_MS,
            candidateDurationMs = bestSegment.durationMs,
            peakRms = peakRms,
            reason = when {
                accepted -> "accepted"
                peakRms < MIN_ACCEPT_PEAK_RMS -> "accept_peak_below_min"
                else -> "distance_above_threshold"
            },
        )
        Log.d(
            TAG,
            "match accepted=${result.accepted} distance=${result.distance} " +
                "templates=${result.templateCount} candidates=${result.candidateCount} " +
                "candidateStartMs=${result.candidateStartMs} candidateMs=${result.candidateDurationMs} " +
                "peakRms=${result.peakRms} reason=${result.reason}",
        )
        return result
    }

    private fun loadTemplates(context: Context): TemplateSet? {
        val file = File(context.cacheDir, ENROLLMENT_WAV_NAME)
        if (!file.isFile || file.length() <= 0L) return null

        cachedTemplates?.let { cached ->
            if (cached.fileModifiedAt == file.lastModified() && cached.fileSize == file.length()) {
                return cached
            }
        }

        val samples = runCatching { PcmWavFile.readMono16(file) }
            .onFailure { Log.w(TAG, "Failed to read enrollment wake template: ${it.message}") }
            .getOrNull()
            ?: return null
        val frames = frameStats(samples)
        if (frames.isEmpty() || frames.maxOf { it.rms } < MIN_TEMPLATE_PEAK_RMS) return null

        val templates = speechSegments(
            frames = frames,
            minimumDurationMs = MIN_TEMPLATE_MS,
            maximumDurationMs = MAX_TEMPLATE_MS,
            minimumPeakRms = MIN_TEMPLATE_PEAK_RMS,
        )
            .map { segment -> frames.subList(segment.startFrame, segment.endFrame + 1).toList() }
        val created = TemplateSet(
            fileModifiedAt = file.lastModified(),
            fileSize = file.length(),
            templates = templates,
        )
        cachedTemplates = created
        Log.i(TAG, "Loaded wake phrase templates count=${templates.size} file=${file.name}")
        return created
    }

    private fun speechSegments(
        frames: List<FrameStats>,
        minimumDurationMs: Long,
        maximumDurationMs: Long,
        minimumPeakRms: Float,
    ): List<Segment> {
        if (frames.isEmpty()) return emptyList()

        val peakRms = frames.maxOf { it.rms }
        if (peakRms < minimumPeakRms) return emptyList()

        val floorRms = noiseFloor(frames.map { it.rms })
        val threshold = maxOf(
            minimumPeakRms,
            peakRms * ACTIVE_PEAK_RATIO,
            floorRms + (peakRms - floorRms) * ACTIVE_FLOOR_RATIO,
        )
        val maxInactiveFrames = (SPEECH_END_GAP_MS / HOP_MS).toInt()
        val mergeGapFrames = (SPEECH_GAP_MERGE_MS / HOP_MS).toInt()
        val marginFrames = (EDGE_MARGIN_MS / HOP_MS).toInt()
        val rawSegments = mutableListOf<Segment>()
        var startFrame = -1
        var lastActiveFrame = -1

        frames.forEachIndexed { index, frame ->
            if (frame.rms >= threshold) {
                if (startFrame < 0) startFrame = index
                lastActiveFrame = index
            } else if (startFrame >= 0 && index - lastActiveFrame > maxInactiveFrames) {
                addSegment(rawSegments, startFrame, lastActiveFrame, minimumDurationMs)
                startFrame = -1
                lastActiveFrame = -1
            }
        }
        if (startFrame >= 0) {
            addSegment(rawSegments, startFrame, lastActiveFrame, minimumDurationMs)
        }

        val mergedSegments = mutableListOf<Segment>()
        rawSegments.forEach { segment ->
            val previous = mergedSegments.lastOrNull()
            if (previous != null && segment.startFrame - previous.endFrame < mergeGapFrames) {
                mergedSegments[mergedSegments.lastIndex] = Segment(previous.startFrame, segment.endFrame)
            } else {
                mergedSegments += segment
            }
        }

        return mergedSegments
            .map { segment ->
                Segment(
                    startFrame = (segment.startFrame - marginFrames).coerceAtLeast(0),
                    endFrame = (segment.endFrame + marginFrames).coerceAtMost(frames.lastIndex),
                )
            }
            .filter { it.durationMs in minimumDurationMs..maximumDurationMs }
    }

    private fun addSegment(
        segments: MutableList<Segment>,
        startFrame: Int,
        endFrame: Int,
        minimumDurationMs: Long,
    ) {
        if (endFrame >= startFrame && (endFrame - startFrame + 1) * HOP_MS >= minimumDurationMs) {
            segments += Segment(startFrame, endFrame)
        }
    }

    private fun frameStats(samples: FloatArray): List<FrameStats> {
        val frameSamples = (SAMPLE_RATE_HZ * FRAME_MS / 1000L).toInt()
        val hopSamples = (SAMPLE_RATE_HZ * HOP_MS / 1000L).toInt()
        if (samples.size < frameSamples || frameSamples <= 0 || hopSamples <= 0) return emptyList()

        val frames = mutableListOf<FrameStats>()
        var start = 0
        while (start + frameSamples <= samples.size) {
            frames += frameStats(samples, start, start + frameSamples)
            start += hopSamples
        }
        return frames
    }

    private fun frameStats(samples: FloatArray, start: Int, end: Int): FrameStats {
        var sumSquares = 0.0
        var zeroCrossings = 0
        var previous = samples[start]
        for (index in start until end) {
            val sample = samples[index]
            sumSquares += sample.toDouble() * sample.toDouble()
            if (index > start && (previous >= 0f) != (sample >= 0f)) {
                zeroCrossings += 1
            }
            previous = sample
        }

        return FrameStats(
            rms = sqrt(sumSquares / (end - start)).toFloat(),
            zeroCrossingRate = zeroCrossings.toFloat() / (end - start - 1).coerceAtLeast(1),
        )
    }

    private fun dtwDistance(left: List<FrameStats>, right: List<FrameStats>): Float {
        val normalizedLeft = normalize(left)
        val normalizedRight = normalize(right)
        if (normalizedLeft.isEmpty() || normalizedRight.isEmpty()) return Float.POSITIVE_INFINITY

        val leftCount = normalizedLeft.size
        val rightCount = normalizedRight.size
        val band = maxOf(abs(leftCount - rightCount), (maxOf(leftCount, rightCount) * DTW_BAND_RATIO).toInt())
        var previous = FloatArray(rightCount + 1) { Float.POSITIVE_INFINITY }
        var current = FloatArray(rightCount + 1) { Float.POSITIVE_INFINITY }
        previous[0] = 0f

        for (leftIndex in 1..leftCount) {
            current.fill(Float.POSITIVE_INFINITY)
            val firstRight = maxOf(1, leftIndex - band)
            val lastRight = minOf(rightCount, leftIndex + band)
            for (rightIndex in firstRight..lastRight) {
                val cost = featureDistance(
                    normalizedLeft[leftIndex - 1],
                    normalizedRight[rightIndex - 1],
                )
                current[rightIndex] = cost + minOf(
                    previous[rightIndex],
                    current[rightIndex - 1],
                    previous[rightIndex - 1],
                )
            }

            val next = previous
            previous = current
            current = next
        }

        return previous[rightCount] / (leftCount + rightCount)
    }

    private fun normalize(frames: List<FrameStats>): List<Feature> {
        if (frames.isEmpty()) return emptyList()

        val logRms = frames.map { frame -> ln(frame.rms.coerceAtLeast(0.000001f).toDouble()).toFloat() }
        val logMean = logRms.average().toFloat()
        val logStd = standardDeviation(logRms, logMean).coerceAtLeast(0.000001f)
        val zcrMean = frames.map { it.zeroCrossingRate }.average().toFloat()
        val zcrStd = standardDeviation(frames.map { it.zeroCrossingRate }, zcrMean).coerceAtLeast(0.000001f)
        val normalized = mutableListOf<Feature>()
        var previousLogRms = 0f

        frames.forEachIndexed { index, frame ->
            val currentLogRms = (logRms[index] - logMean) / logStd
            normalized += Feature(
                logRms = currentLogRms,
                deltaLogRms = if (index == 0) 0f else currentLogRms - previousLogRms,
                zeroCrossingRate = ((frame.zeroCrossingRate - zcrMean) / zcrStd) * ZCR_WEIGHT,
            )
            previousLogRms = currentLogRms
        }
        return normalized
    }

    private fun featureDistance(left: Feature, right: Feature): Float {
        val logDiff = left.logRms - right.logRms
        val deltaDiff = (left.deltaLogRms - right.deltaLogRms) * DELTA_WEIGHT
        val zcrDiff = left.zeroCrossingRate - right.zeroCrossingRate
        return sqrt(
            (logDiff * logDiff + deltaDiff * deltaDiff + zcrDiff * zcrDiff).toDouble() / 3.0,
        ).toFloat()
    }

    private fun standardDeviation(values: List<Float>, mean: Float): Float {
        if (values.isEmpty()) return 0f
        val variance = values.sumOf { value ->
            val diff = value - mean
            (diff * diff).toDouble()
        } / values.size
        return sqrt(variance).toFloat()
    }

    private fun noiseFloor(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[(sorted.lastIndex * 0.2f).toInt().coerceIn(0, sorted.lastIndex)]
    }
}
