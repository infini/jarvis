package com.personal.jarvis

import android.content.Context
import org.json.JSONObject
import java.io.File

object CommandVoiceSampleStore {
    const val MAX_SAMPLES_PER_COMMAND = 12

    private const val SAMPLE_DIR_NAME = "command_voice_samples"
    private const val WAV_EXTENSION = ".wav"
    private const val JSON_EXTENSION = ".json"

    data class SampleInfo(
        val commandId: String,
        val phrase: String,
        val file: File,
        val createdAtMs: Long,
        val durationMs: Long,
        val peakFrameRms: Float,
        val meanRms: Float,
    )

    data class Summary(
        val count: Int,
        val lastSample: SampleInfo?,
    )

    fun save(
        context: Context,
        entry: CommandCatalog.Entry,
        samples: FloatArray,
    ): SampleInfo {
        val timestampMs = System.currentTimeMillis()
        val dir = sampleDir(context, entry.commandId)
        dir.mkdirs()

        val wavFile = uniqueFile(dir, "sample-$timestampMs", WAV_EXTENSION)
        PcmWavFile.writeMono16(
            file = wavFile,
            samples = samples,
            sampleRateHz = OwnerVoiceEngine.SAMPLE_RATE_HZ,
        )

        val summary = OwnerVoiceEngine.summarizeAudio(samples)
        val info = SampleInfo(
            commandId = entry.commandId,
            phrase = entry.phrases.first(),
            file = wavFile,
            createdAtMs = timestampMs,
            durationMs = summary.durationMs,
            peakFrameRms = summary.peakFrameRms,
            meanRms = summary.meanRms,
        )
        writeMetadata(info, entry)
        pruneSamples(dir)
        CommandVoiceSampleMatcher.invalidateCache()
        return info
    }

    fun summary(context: Context, commandId: String): Summary {
        val samples = samples(context, commandId)
        return Summary(
            count = samples.size,
            lastSample = samples.maxByOrNull { it.createdAtMs },
        )
    }

    fun samples(context: Context, commandId: String): List<SampleInfo> {
        return sampleFiles(context, commandId).map { file ->
            readMetadata(commandId, file) ?: fallbackInfo(commandId, file)
        }.sortedBy { it.createdAtMs }
    }

    fun sampleFiles(context: Context, commandId: String): List<File> {
        val dir = sampleDir(context, commandId)
        return dir.listFiles { file -> file.isFile && file.extension == "wav" }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    fun deleteSamples(context: Context, commandId: String): Int {
        val dir = sampleDir(context, commandId)
        val count = sampleFiles(context, commandId).size
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.deleteRecursively() }
        }
        CommandVoiceSampleMatcher.invalidateCache()
        return count
    }

    internal fun directoryNameForCommand(commandId: String): String {
        val cleaned = commandId
            .lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "_")
            .trim('_')
        return cleaned.ifBlank { "command" }
    }

    internal fun sampleFilesToPrune(files: List<File>): List<File> {
        return files
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .dropLast(MAX_SAMPLES_PER_COMMAND)
    }

    private fun sampleDir(context: Context, commandId: String): File {
        return File(File(context.filesDir, SAMPLE_DIR_NAME), directoryNameForCommand(commandId))
    }

    private fun pruneSamples(dir: File) {
        val wavFiles = dir.listFiles { file -> file.isFile && file.extension == "wav" }.orEmpty()
        sampleFilesToPrune(wavFiles.toList()).forEach { wavFile ->
            metadataFile(wavFile).delete()
            wavFile.delete()
        }
    }

    private fun uniqueFile(dir: File, baseName: String, extension: String): File {
        var index = 0
        while (true) {
            val suffix = if (index == 0) "" else "-$index"
            val file = File(dir, "$baseName$suffix$extension")
            if (!file.exists()) return file
            index += 1
        }
    }

    private fun writeMetadata(info: SampleInfo, entry: CommandCatalog.Entry) {
        val metadata = JSONObject()
            .put("commandId", info.commandId)
            .put("title", entry.title)
            .put("phrase", info.phrase)
            .put("createdAtMs", info.createdAtMs)
            .put("durationMs", info.durationMs)
            .put("peakFrameRms", info.peakFrameRms)
            .put("meanRms", info.meanRms)
            .put("sampleRateHz", OwnerVoiceEngine.SAMPLE_RATE_HZ)
        metadataFile(info.file).writeText(metadata.toString(2), Charsets.UTF_8)
    }

    private fun readMetadata(commandId: String, wavFile: File): SampleInfo? {
        val file = metadataFile(wavFile)
        if (!file.isFile) return null

        return runCatching {
            val metadata = JSONObject(file.readText(Charsets.UTF_8))
            SampleInfo(
                commandId = metadata.optString("commandId", commandId),
                phrase = metadata.optString("phrase", ""),
                file = wavFile,
                createdAtMs = metadata.optLong("createdAtMs", wavFile.lastModified()),
                durationMs = metadata.optLong("durationMs", 0L),
                peakFrameRms = metadata.optDouble("peakFrameRms", 0.0).toFloat(),
                meanRms = metadata.optDouble("meanRms", 0.0).toFloat(),
            )
        }.getOrNull()
    }

    private fun fallbackInfo(commandId: String, wavFile: File): SampleInfo {
        return SampleInfo(
            commandId = commandId,
            phrase = "",
            file = wavFile,
            createdAtMs = wavFile.lastModified(),
            durationMs = 0L,
            peakFrameRms = 0f,
            meanRms = 0f,
        )
    }

    private fun metadataFile(wavFile: File): File {
        return File(wavFile.parentFile, wavFile.name.removeSuffix(WAV_EXTENSION) + JSON_EXTENSION)
    }
}
