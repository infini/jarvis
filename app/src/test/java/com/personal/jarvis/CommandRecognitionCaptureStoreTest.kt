package com.personal.jarvis

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandRecognitionCaptureStoreTest {
    @Test
    fun commandCapturePruneSelectsOldestFilesBeyondLimit() {
        val dir = createTempDirectory("jarvis-command-captures").toFile()
        try {
            val files = (0 until CommandRecognitionCaptureStore.MAX_CAPTURE_FILES + 2).map { index ->
                dir.resolve("capture-$index.wav").apply {
                    writeText("sample")
                    setLastModified(2_000L + index)
                }
            }

            val prunedNames = CommandRecognitionCaptureStore.captureFilesToPrune(files).map { it.name }

            assertEquals(listOf("capture-0.wav", "capture-1.wav"), prunedNames)
        } finally {
            dir.deleteRecursively()
        }
    }
}
