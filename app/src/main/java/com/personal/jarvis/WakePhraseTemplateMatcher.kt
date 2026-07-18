package com.personal.jarvis

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
    private const val CANDIDATE_REFERENCE_PEAK_PERCENTILE = 0.95f
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
        val templates: List<List<AudioTemplateMatcher.FrameStats>>,
    )

    private val matchConfig = AudioTemplateMatcher.Config(
        sampleRateHz = SAMPLE_RATE_HZ,
        frameMs = FRAME_MS,
        hopMs = HOP_MS,
        speechGapMergeMs = SPEECH_GAP_MERGE_MS,
        speechEndGapMs = SPEECH_END_GAP_MS,
        edgeMarginMs = EDGE_MARGIN_MS,
        candidateReferencePeakPercentile = CANDIDATE_REFERENCE_PEAK_PERCENTILE,
        activeFloorRatio = ACTIVE_FLOOR_RATIO,
        activePeakRatio = ACTIVE_PEAK_RATIO,
        dtwBandRatio = DTW_BAND_RATIO,
        zcrWeight = ZCR_WEIGHT,
        deltaWeight = DELTA_WEIGHT,
    )

    fun invalidateCache() {
        cachedTemplates = null
    }

    internal fun saveEnrollmentTemplate(context: Context, samples: FloatArray): File {
        val target = File(context.cacheDir, ENROLLMENT_WAV_NAME)
        val temporary = File(context.cacheDir, "$ENROLLMENT_WAV_NAME.tmp")
        temporary.delete()
        return try {
            PcmWavFile.writeMono16(temporary, samples, SAMPLE_RATE_HZ)
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            invalidateCache()
            target
        } finally {
            temporary.delete()
        }
    }

    internal fun clearEnrollmentTemplate(context: Context): Boolean {
        invalidateCache()
        return listOf(
            File(context.cacheDir, ENROLLMENT_WAV_NAME),
            File(context.cacheDir, "$ENROLLMENT_WAV_NAME.tmp"),
        ).all { file ->
            runCatching {
                Files.deleteIfExists(file.toPath())
                true
            }.getOrDefault(false)
        }
    }

    fun match(context: Context, samples: FloatArray): Result {
        val templates = loadTemplates(context)
            ?: return Result(accepted = false, reason = "template_unavailable")
        if (templates.templates.isEmpty()) {
            return Result(accepted = false, reason = "template_empty")
        }

        val frames = AudioTemplateMatcher.frameStats(samples, matchConfig)
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

        val candidates = AudioTemplateMatcher.speechSegments(
            frames = frames,
            config = matchConfig,
            minimumDurationMs = MIN_CANDIDATE_MS,
            maximumDurationMs = MAX_CANDIDATE_MS,
            minimumPeakRms = MIN_CANDIDATE_PEAK_RMS,
            useRobustReferencePeak = true,
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
                val distance = AudioTemplateMatcher.dtwDistance(candidateFrames, template, matchConfig)
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
        val frames = AudioTemplateMatcher.frameStats(samples, matchConfig)
        if (frames.isEmpty() || frames.maxOf { it.rms } < MIN_TEMPLATE_PEAK_RMS) return null

        val templates = AudioTemplateMatcher.speechSegments(
            frames = frames,
            config = matchConfig,
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
}
