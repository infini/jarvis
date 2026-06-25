package com.personal.jarvis

import android.content.Context
import android.util.Log

object CommandVoiceSampleMatcher {
    private const val TAG = "JarvisCommandSample"
    private const val SAMPLE_RATE_HZ = OwnerVoiceEngine.SAMPLE_RATE_HZ
    private const val FRAME_MS = 40L
    private const val HOP_MS = 20L
    private const val MIN_TEMPLATE_MS = 450L
    private const val MAX_TEMPLATE_MS = 3600L
    private const val MIN_CANDIDATE_MS = 450L
    private const val MAX_CANDIDATE_MS = 3800L
    private const val SPEECH_GAP_MERGE_MS = 500L
    private const val SPEECH_END_GAP_MS = 220L
    private const val EDGE_MARGIN_MS = 180L
    private const val MIN_TEMPLATE_PEAK_RMS = 0.0012f
    private const val MIN_CANDIDATE_PEAK_RMS = 0.00075f
    private const val MIN_ACCEPT_PEAK_RMS = 0.0012f
    private const val CANDIDATE_REFERENCE_PEAK_PERCENTILE = 0.95f
    private const val ACTIVE_FLOOR_RATIO = 0.18f
    private const val ACTIVE_PEAK_RATIO = 0.12f
    private const val ACCEPT_DISTANCE = 0.20f
    private const val ACCEPT_MARGIN = 0.035f
    private const val MIN_DURATION_RATIO = 0.72f
    private const val MAX_DURATION_RATIO = 1.45f
    private const val MIN_SAMPLES_PER_COMMAND = 2

    @Volatile private var cachedTemplates: TemplateCache? = null

    data class Result(
        val commandId: String?,
        val accepted: Boolean,
        val distance: Float = Float.POSITIVE_INFINITY,
        val nextCommandDistance: Float = Float.POSITIVE_INFINITY,
        val templateCount: Int = 0,
        val candidateCount: Int = 0,
        val sampleCountForCommand: Int = 0,
        val durationRatio: Float = 0f,
        val reason: String = "",
    )

    private data class TemplateCache(
        val signature: String,
        val templates: List<Template>,
        val sampleCountsByCommand: Map<String, Int>,
    )

    private data class Template(
        val commandId: String,
        val frames: List<AudioTemplateMatcher.FrameStats>,
        val durationMs: Long,
    )

    private data class CommandCandidate(
        val distance: Float,
        val candidateDurationMs: Long,
        val templateDurationMs: Long,
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
        dtwBandRatio = 0.35f,
        zcrWeight = 0.30f,
        deltaWeight = 1.0f,
    )

    fun invalidateCache() {
        cachedTemplates = null
    }

    fun match(context: Context, samples: FloatArray): Result {
        return matchWithCache(loadTemplates(context), samples)
    }

    internal fun matchSamples(
        samples: FloatArray,
        templateSamplesByCommand: Map<String, List<FloatArray>>,
    ): Result {
        val templates = mutableListOf<Template>()
        val sampleCounts = mutableMapOf<String, Int>()
        templateSamplesByCommand.forEach { (commandId, sampleList) ->
            sampleCounts[commandId] = sampleList.size
            if (sampleList.size < MIN_SAMPLES_PER_COMMAND) return@forEach

            sampleList.forEach { sample ->
                templates += templatesForSample(commandId, sample)
            }
        }
        return matchWithCache(
            TemplateCache(
                signature = "test",
                templates = templates,
                sampleCountsByCommand = sampleCounts,
            ),
            samples,
        )
    }

    private fun matchWithCache(cache: TemplateCache, samples: FloatArray): Result {
        if (cache.templates.isEmpty()) {
            return Result(commandId = null, accepted = false, reason = "template_empty")
        }

        val frames = AudioTemplateMatcher.frameStats(samples, matchConfig)
        if (frames.isEmpty()) {
            return Result(
                commandId = null,
                accepted = false,
                templateCount = cache.templates.size,
                reason = "input_empty",
            )
        }

        val peakRms = frames.maxOf { it.rms }
        if (peakRms < MIN_CANDIDATE_PEAK_RMS) {
            return Result(
                commandId = null,
                accepted = false,
                templateCount = cache.templates.size,
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
                commandId = null,
                accepted = false,
                templateCount = cache.templates.size,
                reason = "candidate_empty",
            )
        }

        val bestByCommand = mutableMapOf<String, CommandCandidate>()
        candidates.forEach { candidate ->
            val candidateFrames = frames.subList(candidate.startFrame, candidate.endFrame + 1)
            cache.templates.forEach { template ->
                val distance = AudioTemplateMatcher.dtwDistance(candidateFrames, template.frames, matchConfig)
                val previous = bestByCommand[template.commandId]?.distance ?: Float.POSITIVE_INFINITY
                if (distance < previous) {
                    bestByCommand[template.commandId] = CommandCandidate(
                        distance = distance,
                        candidateDurationMs = candidate.durationMs,
                        templateDurationMs = template.durationMs,
                    )
                }
            }
        }

        val ranked = bestByCommand.entries.sortedBy { it.value.distance }
        val best = ranked.firstOrNull()
            ?: return Result(
                commandId = null,
                accepted = false,
                templateCount = cache.templates.size,
                candidateCount = candidates.size,
                reason = "no_distance",
            )
        val nextCommandDistance = ranked.drop(1).firstOrNull()?.value?.distance ?: Float.POSITIVE_INFINITY
        val sampleCount = cache.sampleCountsByCommand[best.key] ?: 0
        val durationRatio = durationRatio(best.value)
        val accepted = sampleCount >= MIN_SAMPLES_PER_COMMAND &&
            best.value.distance <= ACCEPT_DISTANCE &&
            peakRms >= MIN_ACCEPT_PEAK_RMS &&
            durationRatio in MIN_DURATION_RATIO..MAX_DURATION_RATIO &&
            (nextCommandDistance.isInfinite() || nextCommandDistance - best.value.distance >= ACCEPT_MARGIN)
        val reason = when {
            accepted -> "accepted"
            sampleCount < MIN_SAMPLES_PER_COMMAND -> "sample_count_below_min"
            best.value.distance > ACCEPT_DISTANCE -> "distance_above_threshold"
            peakRms < MIN_ACCEPT_PEAK_RMS -> "accept_peak_below_min"
            durationRatio < MIN_DURATION_RATIO -> "duration_ratio_below_min"
            durationRatio > MAX_DURATION_RATIO -> "duration_ratio_above_max"
            !nextCommandDistance.isInfinite() && nextCommandDistance - best.value.distance < ACCEPT_MARGIN -> "ambiguous"
            else -> "rejected"
        }
        val result = Result(
            commandId = best.key.takeIf { accepted },
            accepted = accepted,
            distance = best.value.distance,
            nextCommandDistance = nextCommandDistance,
            templateCount = cache.templates.size,
            candidateCount = candidates.size,
            sampleCountForCommand = sampleCount,
            durationRatio = durationRatio,
            reason = reason,
        )
        logDebug(
            "match accepted=${result.accepted} command=${result.commandId.orEmpty()} " +
                "distance=${result.distance} next=${result.nextCommandDistance} ratio=${result.durationRatio} " +
                "templates=${result.templateCount} candidates=${result.candidateCount} " +
                "samplesForCommand=${result.sampleCountForCommand} reason=${result.reason}",
        )
        return result
    }

    private fun logDebug(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private fun durationRatio(candidate: CommandCandidate): Float {
        if (candidate.templateDurationMs <= 0L) return 0f
        return candidate.candidateDurationMs.toFloat() / candidate.templateDurationMs
    }

    private fun loadTemplates(context: Context): TemplateCache {
        val signature = templateSignature(context)
        cachedTemplates?.let { cached ->
            if (cached.signature == signature) return cached
        }

        val templates = mutableListOf<Template>()
        val sampleCounts = mutableMapOf<String, Int>()
        CommandCatalog.entries.forEach commandLoop@{ entry ->
            val files = CommandVoiceSampleStore.sampleFiles(context, entry.commandId)
            sampleCounts[entry.commandId] = files.size
            if (files.size < MIN_SAMPLES_PER_COMMAND) return@commandLoop

            files.forEach fileLoop@{ file ->
                val samples = runCatching { PcmWavFile.readMono16(file) }
                    .onFailure { Log.w(TAG, "Failed to read command sample ${file.name}: ${it.message}") }
                    .getOrNull()
                    ?: return@fileLoop
                templates += templatesForSample(entry.commandId, samples)
            }
        }

        return TemplateCache(
            signature = signature,
            templates = templates,
            sampleCountsByCommand = sampleCounts,
        ).also {
            cachedTemplates = it
            Log.i(TAG, "Loaded command voice templates count=${templates.size}")
        }
    }

    private fun templatesForSample(commandId: String, samples: FloatArray): List<Template> {
        val frames = AudioTemplateMatcher.frameStats(samples, matchConfig)
        if (frames.isEmpty() || frames.maxOf { it.rms } < MIN_TEMPLATE_PEAK_RMS) return emptyList()

        return AudioTemplateMatcher.speechSegments(
            frames = frames,
            config = matchConfig,
            minimumDurationMs = MIN_TEMPLATE_MS,
            maximumDurationMs = MAX_TEMPLATE_MS,
            minimumPeakRms = MIN_TEMPLATE_PEAK_RMS,
        ).map { segment ->
            Template(
                commandId = commandId,
                frames = frames.subList(segment.startFrame, segment.endFrame + 1).toList(),
                durationMs = segment.durationMs,
            )
        }
    }

    private fun templateSignature(context: Context): String {
        return CommandCatalog.entries.joinToString("|") { entry ->
            val files = CommandVoiceSampleStore.sampleFiles(context, entry.commandId)
            val fileSignature = files.joinToString(",") { file ->
                "${file.name}:${file.lastModified()}:${file.length()}"
            }
            "${entry.commandId}[$fileSignature]"
        }
    }

}
