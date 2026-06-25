package com.personal.jarvis

import android.content.Context
import org.json.JSONObject
import java.io.File

object CommandRecognitionCaptureStore {
    const val CAPTURE_DIR_NAME = "command-recognition-attempts"
    const val MAX_CAPTURE_FILES = 120

    data class CaptureInfo(
        val wavFile: File,
        val metadataFile: File,
        val timestampMs: Long,
        val outcome: String,
    )

    fun save(
        context: Context,
        outcome: String,
        result: LocalCommandRecognizer.Result,
    ): CaptureInfo? {
        if (result.samples.isEmpty()) return null

        val timestampMs = System.currentTimeMillis()
        val dir = captureDir(context)
        val safeOutcome = safeName(outcome)
        val baseName = uniqueBaseName(dir, "command-$timestampMs-$safeOutcome")
        val wavFile = File(dir, "$baseName.wav")
        val metadataFile = File(dir, "$baseName.json")

        PcmWavFile.writeMono16(
            file = wavFile,
            samples = result.samples,
            sampleRateHz = OwnerVoiceEngine.SAMPLE_RATE_HZ,
        )
        writeMetadata(metadataFile, timestampMs, outcome, result)
        pruneCaptures(dir)

        return CaptureInfo(
            wavFile = wavFile,
            metadataFile = metadataFile,
            timestampMs = timestampMs,
            outcome = outcome,
        )
    }

    fun captureFiles(context: Context): List<File> {
        return captureDir(context)
            .listFiles { file -> file.isFile && file.extension.equals("wav", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    fun metadataFile(wavFile: File): File {
        return File(wavFile.parentFile, wavFile.nameWithoutExtension + ".json")
    }

    internal fun captureFilesToPrune(files: List<File>): List<File> {
        return files
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .dropLast(MAX_CAPTURE_FILES)
    }

    private fun captureDir(context: Context): File {
        return File(context.cacheDir, CAPTURE_DIR_NAME)
    }

    private fun writeMetadata(
        metadataFile: File,
        timestampMs: Long,
        outcome: String,
        result: LocalCommandRecognizer.Result,
    ) {
        val metadata = JSONObject()
            .put("timestampMs", timestampMs)
            .put("outcome", outcome)
            .put("command", result.command.orEmpty())
            .put("text", result.text)
            .put("endpoint", result.endpoint)
            .put("elapsedMs", result.elapsedMs)
            .put("activeSpeechMs", result.activeSpeechMs)
            .put("trailingSilenceMs", result.trailingSilenceMs)
            .put("peakRms", result.peakRms)
            .put("meanRms", result.meanRms)
            .put("asrGain", result.asrGain)
            .put("sampleCount", result.samples.size)
            .put("sampleRateHz", OwnerVoiceEngine.SAMPLE_RATE_HZ)
            .put("sampleMatchCommandId", result.sampleMatchCommandId.orEmpty())
            .put("sampleMatchAccepted", result.sampleMatchAccepted)
            .put("sampleMatchReason", result.sampleMatchReason)
            .put("sampleMatchDistance", finiteOrNull(result.sampleMatchDistance))
            .put("sampleMatchNextDistance", finiteOrNull(result.sampleMatchNextDistance))
            .put("sampleMatchDurationRatio", result.sampleMatchDurationRatio)
        metadataFile.parentFile?.mkdirs()
        metadataFile.writeText(metadata.toString(2), Charsets.UTF_8)
    }

    private fun pruneCaptures(dir: File) {
        val wavFiles = dir
            .listFiles { file -> file.isFile && file.extension.equals("wav", ignoreCase = true) }
            .orEmpty()
            .toList()
        captureFilesToPrune(wavFiles).forEach { wavFile ->
            metadataFile(wavFile).delete()
            wavFile.delete()
        }
    }

    private fun uniqueBaseName(dir: File, baseName: String): String {
        var index = 0
        while (true) {
            val suffix = if (index == 0) "" else "-$index"
            val candidate = "$baseName$suffix"
            if (!File(dir, "$candidate.wav").exists() && !File(dir, "$candidate.json").exists()) {
                return candidate
            }
            index += 1
        }
    }

    private fun safeName(value: String): String {
        return value
            .lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "_")
            .trim('_')
            .ifBlank { "unknown" }
    }

    private fun finiteOrNull(value: Float): Any {
        return if (value.isFinite()) value else JSONObject.NULL
    }
}
