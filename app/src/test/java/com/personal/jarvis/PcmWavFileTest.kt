package com.personal.jarvis

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcmWavFileTest {
    @Test
    fun writesAndReadsMono16PcmWav() {
        val file = File.createTempFile("jarvis-wav-test", ".wav")
        val samples = floatArrayOf(-1f, -0.25f, 0f, 0.25f, 1f)

        try {
            PcmWavFile.writeMono16(file, samples, OwnerVoiceEngine.SAMPLE_RATE_HZ)
            val decoded = PcmWavFile.readMono16(file)

            assertEquals(samples.size, decoded.size)
            samples.zip(decoded).forEach { (expected, actual) ->
                assertTrue(abs(expected - actual) < 0.0001f)
            }
        } finally {
            file.delete()
        }
    }
}
