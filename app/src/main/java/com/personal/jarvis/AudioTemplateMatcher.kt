package com.personal.jarvis

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

object AudioTemplateMatcher {
    data class Config(
        val sampleRateHz: Int,
        val frameMs: Long,
        val hopMs: Long,
        val speechGapMergeMs: Long,
        val speechEndGapMs: Long,
        val edgeMarginMs: Long,
        val candidateReferencePeakPercentile: Float,
        val activeFloorRatio: Float,
        val activePeakRatio: Float,
        val dtwBandRatio: Float,
        val zcrWeight: Float,
        val deltaWeight: Float,
    )

    data class FrameStats(
        val rms: Float,
        val zeroCrossingRate: Float,
    )

    data class Segment(
        val startFrame: Int,
        val endFrame: Int,
        val durationMs: Long,
    )

    private data class Feature(
        val logRms: Float,
        val deltaLogRms: Float,
        val zeroCrossingRate: Float,
    )

    fun frameStats(samples: FloatArray, config: Config): List<FrameStats> {
        val frameSamples = (config.sampleRateHz * config.frameMs / 1000L).toInt()
        val hopSamples = (config.sampleRateHz * config.hopMs / 1000L).toInt()
        if (samples.size < frameSamples || frameSamples <= 0 || hopSamples <= 0) return emptyList()

        val frames = mutableListOf<FrameStats>()
        var start = 0
        while (start + frameSamples <= samples.size) {
            frames += frameStats(samples, start, start + frameSamples)
            start += hopSamples
        }
        return frames
    }

    fun speechSegments(
        frames: List<FrameStats>,
        config: Config,
        minimumDurationMs: Long,
        maximumDurationMs: Long,
        minimumPeakRms: Float,
        useRobustReferencePeak: Boolean = false,
    ): List<Segment> {
        if (frames.isEmpty()) return emptyList()

        val peakRms = frames.maxOf { it.rms }
        if (peakRms < minimumPeakRms) return emptyList()

        val floorRms = noiseFloor(frames.map { it.rms })
        val referencePeakRms = if (useRobustReferencePeak) {
            percentile(
                values = frames.map { it.rms },
                percentile = config.candidateReferencePeakPercentile,
            )
        } else {
            peakRms
        }
        val threshold = maxOf(
            minimumPeakRms,
            referencePeakRms * config.activePeakRatio,
            floorRms + (referencePeakRms - floorRms) * config.activeFloorRatio,
        )
        val maxInactiveFrames = (config.speechEndGapMs / config.hopMs).toInt()
        val mergeGapFrames = (config.speechGapMergeMs / config.hopMs).toInt()
        val marginFrames = (config.edgeMarginMs / config.hopMs).toInt()
        val rawSegments = mutableListOf<Segment>()
        var startFrame = -1
        var lastActiveFrame = -1

        frames.forEachIndexed { index, frame ->
            if (frame.rms >= threshold) {
                if (startFrame < 0) startFrame = index
                lastActiveFrame = index
            } else if (startFrame >= 0 && index - lastActiveFrame > maxInactiveFrames) {
                addSegment(rawSegments, startFrame, lastActiveFrame, minimumDurationMs, config.hopMs)
                startFrame = -1
                lastActiveFrame = -1
            }
        }
        if (startFrame >= 0) {
            addSegment(rawSegments, startFrame, lastActiveFrame, minimumDurationMs, config.hopMs)
        }

        val mergedSegments = mutableListOf<Segment>()
        rawSegments.forEach { segment ->
            val previous = mergedSegments.lastOrNull()
            if (previous != null && segment.startFrame - previous.endFrame < mergeGapFrames) {
                mergedSegments[mergedSegments.lastIndex] = segmentFor(
                    startFrame = previous.startFrame,
                    endFrame = segment.endFrame,
                    hopMs = config.hopMs,
                )
            } else {
                mergedSegments += segment
            }
        }

        return mergedSegments
            .map { segment ->
                segmentFor(
                    startFrame = (segment.startFrame - marginFrames).coerceAtLeast(0),
                    endFrame = (segment.endFrame + marginFrames).coerceAtMost(frames.lastIndex),
                    hopMs = config.hopMs,
                )
            }
            .filter { it.durationMs in minimumDurationMs..maximumDurationMs }
    }

    fun dtwDistance(left: List<FrameStats>, right: List<FrameStats>, config: Config): Float {
        val normalizedLeft = normalize(left, config)
        val normalizedRight = normalize(right, config)
        if (normalizedLeft.isEmpty() || normalizedRight.isEmpty()) return Float.POSITIVE_INFINITY

        val leftCount = normalizedLeft.size
        val rightCount = normalizedRight.size
        val band = maxOf(abs(leftCount - rightCount), (maxOf(leftCount, rightCount) * config.dtwBandRatio).toInt())
        var previous = FloatArray(rightCount + 1) { Float.POSITIVE_INFINITY }
        var current = FloatArray(rightCount + 1) { Float.POSITIVE_INFINITY }
        previous[0] = 0f

        for (leftIndex in 1..leftCount) {
            current.fill(Float.POSITIVE_INFINITY)
            val firstRight = maxOf(1, leftIndex - band)
            val lastRight = minOf(rightCount, leftIndex + band)
            for (rightIndex in firstRight..lastRight) {
                val cost = featureDistance(
                    left = normalizedLeft[leftIndex - 1],
                    right = normalizedRight[rightIndex - 1],
                    config = config,
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

    private fun addSegment(
        segments: MutableList<Segment>,
        startFrame: Int,
        endFrame: Int,
        minimumDurationMs: Long,
        hopMs: Long,
    ) {
        val segment = segmentFor(startFrame, endFrame, hopMs)
        if (endFrame >= startFrame && segment.durationMs >= minimumDurationMs) {
            segments += segment
        }
    }

    private fun segmentFor(startFrame: Int, endFrame: Int, hopMs: Long): Segment {
        return Segment(
            startFrame = startFrame,
            endFrame = endFrame,
            durationMs = (endFrame - startFrame + 1) * hopMs,
        )
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

    private fun normalize(frames: List<FrameStats>, config: Config): List<Feature> {
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
                zeroCrossingRate = ((frame.zeroCrossingRate - zcrMean) / zcrStd) * config.zcrWeight,
            )
            previousLogRms = currentLogRms
        }
        return normalized
    }

    private fun featureDistance(left: Feature, right: Feature, config: Config): Float {
        val logDiff = left.logRms - right.logRms
        val deltaDiff = (left.deltaLogRms - right.deltaLogRms) * config.deltaWeight
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
        return percentile(values, 0.20f)
    }

    private fun percentile(values: List<Float>, percentile: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[(sorted.lastIndex * percentile).toInt().coerceIn(0, sorted.lastIndex)]
    }
}
