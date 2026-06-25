package com.personal.jarvis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.io.path.createTempDirectory

class CommandVoiceSampleStoreTest {
    @Test
    fun commandSampleDirectoryNameKeepsStableCommandIds() {
        assertEquals("open_camera", CommandVoiceSampleStore.directoryNameForCommand(CommandBus.COMMAND_OPEN_CAMERA))
        assertEquals("take_photo", CommandVoiceSampleStore.directoryNameForCommand(CommandBus.COMMAND_TAKE_PHOTO))
    }

    @Test
    fun commandSampleDirectoryNameSanitizesUnsafeInput() {
        assertEquals("bad_command", CommandVoiceSampleStore.directoryNameForCommand("../bad command!!"))
        assertEquals("command", CommandVoiceSampleStore.directoryNameForCommand(" ../ "))
    }

    @Test
    fun commandSamplePruneSelectsOldestFilesBeyondLimit() {
        val dir = createTempDirectory("jarvis-samples").toFile()
        try {
            val files = (0 until CommandVoiceSampleStore.MAX_SAMPLES_PER_COMMAND + 2).map { index ->
                dir.resolve("sample-$index.wav").apply {
                    writeText("sample")
                    setLastModified(1_000L + index)
                }
            }

            val prunedNames = CommandVoiceSampleStore.sampleFilesToPrune(files).map { it.name }

            assertEquals(listOf("sample-0.wav", "sample-1.wav"), prunedNames)
        } finally {
            dir.deleteRecursively()
        }
    }
}
