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
        val validExemplarCount: Int = 0,
        val matchedExemplarCount: Int = 0,
        val bestExemplarDistance: Float = Float.POSITIVE_INFINITY,
        val secondExemplarDistance: Float = Float.POSITIVE_INFINITY,
        val secondExemplarDurationRatio: Float = 0f,
        val reason: String = "",
    )

    private data class TemplateCache(
        val signature: String,
        val templates: List<Template>,
        val validExemplarCountsByCommand: Map<String, Int>,
    )

    private data class Template(
        val commandId: String,
        val exemplarId: String,
        val frames: List<AudioTemplateMatcher.FrameStats>,
        val durationMs: Long,
    )

    private data class ExemplarKey(
        val commandId: String,
        val exemplarId: String,
    )

    private data class CommandCandidate(
        val distance: Float,
        val candidateDurationMs: Long,
        val templateDurationMs: Long,
    )

    private data class CommandConsensus(
        val commandId: String,
        val matches: List<CommandCandidate>,
    ) {
        val topMatches: List<CommandCandidate>
            get() = matches.take(MIN_SAMPLES_PER_COMMAND)

        val score: Float
            get() = if (topMatches.size < MIN_SAMPLES_PER_COMMAND) {
                Float.POSITIVE_INFINITY
            } else {
                topMatches.sumOf { it.distance.toDouble() }.toFloat() / topMatches.size
            }
    }

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
        val validExemplarCounts = mutableMapOf<String, Int>()
        templateSamplesByCommand.forEach { (commandId, sampleList) ->
            sampleList.forEachIndexed { index, sample ->
                val exemplarTemplates = templatesForSample(
                    commandId = commandId,
                    exemplarId = "$commandId:$index",
                    samples = sample,
                )
                if (exemplarTemplates.isNotEmpty()) {
                    templates += exemplarTemplates
                    validExemplarCounts[commandId] = (validExemplarCounts[commandId] ?: 0) + 1
                }
            }
        }
        return matchWithCache(
            TemplateCache(
                signature = "test",
                templates = templates,
                validExemplarCountsByCommand = validExemplarCounts,
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

        val bestByExemplar = mutableMapOf<ExemplarKey, CommandCandidate>()
        candidates.forEach { candidate ->
            val candidateFrames = frames.subList(candidate.startFrame, candidate.endFrame + 1)
            cache.templates.forEach { template ->
                val distance = AudioTemplateMatcher.dtwDistance(candidateFrames, template.frames, matchConfig)
                val exemplarKey = ExemplarKey(template.commandId, template.exemplarId)
                val match = CommandCandidate(
                    distance = distance,
                    candidateDurationMs = candidate.durationMs,
                    templateDurationMs = template.durationMs,
                )
                val previous = bestByExemplar[exemplarKey]
                if (previous == null || isBetterExemplarMatch(match, previous)) {
                    bestByExemplar[exemplarKey] = match
                }
            }
        }

        val consensusByCommand = bestByExemplar.entries
            .groupBy(
                keySelector = { it.key.commandId },
                valueTransform = { it.value },
            )
            .map { (commandId, matches) ->
                CommandConsensus(
                    commandId = commandId,
                    matches = matches.sortedWith(
                        compareByDescending<CommandCandidate> {
                            durationRatio(it) in MIN_DURATION_RATIO..MAX_DURATION_RATIO
                        }.thenBy { it.distance },
                    ),
                )
            }
        val ranked = consensusByCommand
            .filter { consensus ->
                (cache.validExemplarCountsByCommand[consensus.commandId] ?: 0) >= MIN_SAMPLES_PER_COMMAND &&
                    consensus.topMatches.size >= MIN_SAMPLES_PER_COMMAND
            }
            .sortedBy { it.score }
        val eligibleRanked = ranked.filter(::passesConsensusGate)
        val eligibleBest = eligibleRanked.firstOrNull()
        val best = eligibleBest ?: ranked.firstOrNull()
            ?: return Result(
                commandId = null,
                accepted = false,
                templateCount = cache.templates.size,
                candidateCount = candidates.size,
                validExemplarCount = cache.validExemplarCountsByCommand.values.maxOrNull() ?: 0,
                matchedExemplarCount = consensusByCommand.maxOfOrNull { it.matches.size } ?: 0,
                reason = if (
                    cache.validExemplarCountsByCommand.values.any { it in 1 until MIN_SAMPLES_PER_COMMAND }
                ) {
                    "sample_count_below_min"
                } else {
                    "no_distance"
                },
            )
        val topMatches = best.topMatches
        val bestMatch = topMatches[0]
        val secondMatch = topMatches[1]
        val nextCommandDistance = eligibleRanked.drop(1).firstOrNull()?.score ?: Float.POSITIVE_INFINITY
        val validExemplarCount = cache.validExemplarCountsByCommand[best.commandId] ?: 0
        val durationRatio = durationRatio(bestMatch)
        val secondDurationRatio = durationRatio(secondMatch)
        val accepted = eligibleBest != null &&
            peakRms >= MIN_ACCEPT_PEAK_RMS &&
            (nextCommandDistance.isInfinite() || nextCommandDistance - best.score >= ACCEPT_MARGIN)
        val reason = when {
            accepted -> "accepted"
            bestMatch.distance > ACCEPT_DISTANCE || secondMatch.distance > ACCEPT_DISTANCE -> {
                "distance_above_threshold"
            }
            peakRms < MIN_ACCEPT_PEAK_RMS -> "accept_peak_below_min"
            durationRatio < MIN_DURATION_RATIO || secondDurationRatio < MIN_DURATION_RATIO -> {
                "duration_ratio_below_min"
            }
            durationRatio > MAX_DURATION_RATIO || secondDurationRatio > MAX_DURATION_RATIO -> {
                "duration_ratio_above_max"
            }
            !nextCommandDistance.isInfinite() && nextCommandDistance - best.score < ACCEPT_MARGIN -> "ambiguous"
            else -> "rejected"
        }
        val result = Result(
            commandId = best.commandId.takeIf { accepted },
            accepted = accepted,
            distance = best.score,
            nextCommandDistance = nextCommandDistance,
            templateCount = cache.templates.size,
            candidateCount = candidates.size,
            sampleCountForCommand = validExemplarCount,
            durationRatio = durationRatio,
            validExemplarCount = validExemplarCount,
            matchedExemplarCount = best.matches.size,
            bestExemplarDistance = bestMatch.distance,
            secondExemplarDistance = secondMatch.distance,
            secondExemplarDurationRatio = secondDurationRatio,
            reason = reason,
        )
        logDebug(
            "match accepted=${result.accepted} command=${result.commandId.orEmpty()} " +
                "distance=${result.distance} next=${result.nextCommandDistance} ratio=${result.durationRatio} " +
                "bestExemplar=${result.bestExemplarDistance} " +
                "secondExemplar=${result.secondExemplarDistance} " +
                "secondRatio=${result.secondExemplarDurationRatio} " +
                "templates=${result.templateCount} candidates=${result.candidateCount} " +
                "validExemplars=${result.validExemplarCount} " +
                "matchedExemplars=${result.matchedExemplarCount} reason=${result.reason}",
        )
        return result
    }

    private fun passesConsensusGate(consensus: CommandConsensus): Boolean {
        if (consensus.topMatches.size < MIN_SAMPLES_PER_COMMAND) return false
        return consensus.topMatches.all { match ->
            match.distance <= ACCEPT_DISTANCE &&
                durationRatio(match) in MIN_DURATION_RATIO..MAX_DURATION_RATIO
        }
    }

    private fun logDebug(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private fun durationRatio(candidate: CommandCandidate): Float {
        if (candidate.templateDurationMs <= 0L) return 0f
        return candidate.candidateDurationMs.toFloat() / candidate.templateDurationMs
    }

    private fun isBetterExemplarMatch(
        candidate: CommandCandidate,
        current: CommandCandidate,
    ): Boolean {
        val candidateDurationAccepted = durationRatio(candidate) in MIN_DURATION_RATIO..MAX_DURATION_RATIO
        val currentDurationAccepted = durationRatio(current) in MIN_DURATION_RATIO..MAX_DURATION_RATIO
        return when {
            candidateDurationAccepted && !currentDurationAccepted -> true
            !candidateDurationAccepted && currentDurationAccepted -> false
            else -> candidate.distance < current.distance
        }
    }

    private fun loadTemplates(context: Context): TemplateCache {
        val signature = templateSignature(context)
        cachedTemplates?.let { cached ->
            if (cached.signature == signature) return cached
        }

        val templates = mutableListOf<Template>()
        val validExemplarCounts = mutableMapOf<String, Int>()
        CommandCatalog.entries.forEach { entry ->
            val files = CommandVoiceSampleStore.sampleFiles(context, entry.commandId)
            files.forEach fileLoop@{ file ->
                val samples = runCatching { PcmWavFile.readMono16(file) }
                    .onFailure { Log.w(TAG, "Failed to read command sample ${file.name}: ${it.message}") }
                    .getOrNull()
                    ?: return@fileLoop
                val exemplarTemplates = templatesForSample(
                    commandId = entry.commandId,
                    exemplarId = file.absolutePath,
                    samples = samples,
                )
                if (exemplarTemplates.isNotEmpty()) {
                    templates += exemplarTemplates
                    validExemplarCounts[entry.commandId] =
                        (validExemplarCounts[entry.commandId] ?: 0) + 1
                }
            }
        }

        return TemplateCache(
            signature = signature,
            templates = templates,
            validExemplarCountsByCommand = validExemplarCounts,
        ).also {
            cachedTemplates = it
            Log.i(TAG, "Loaded command voice templates count=${templates.size}")
        }
    }

    private fun templatesForSample(
        commandId: String,
        exemplarId: String,
        samples: FloatArray,
    ): List<Template> {
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
                exemplarId = exemplarId,
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
