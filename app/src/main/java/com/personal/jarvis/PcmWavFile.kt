package com.personal.jarvis

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object PcmWavFile {
    private const val WAV_HEADER_BYTES = 44
    private const val PCM_FORMAT = 1
    private const val CHANNELS_MONO = 1
    private const val BITS_PER_SAMPLE = 16

    fun writeMono16(file: File, samples: FloatArray, sampleRateHz: Int) {
        file.parentFile?.mkdirs()
        val dataBytes = samples.size * Short.SIZE_BYTES
        val buffer = ByteBuffer.allocate(WAV_HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(WAV_HEADER_BYTES - 8 + dataBytes)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(PCM_FORMAT.toShort())
        buffer.putShort(CHANNELS_MONO.toShort())
        buffer.putInt(sampleRateHz)
        buffer.putInt(sampleRateHz * Short.SIZE_BYTES)
        buffer.putShort(Short.SIZE_BYTES.toShort())
        buffer.putShort(BITS_PER_SAMPLE.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataBytes)
        samples.forEach { sample ->
            val pcm = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            buffer.putShort(pcm)
        }
        file.writeBytes(buffer.array())
    }

    fun readMono16(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size >= WAV_HEADER_BYTES) { "WAV file is too small: ${file.name}" }

        val header = ByteBuffer.wrap(bytes, 0, WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        require(readAscii(bytes, 0, 4) == "RIFF") { "Missing RIFF header: ${file.name}" }
        require(readAscii(bytes, 8, 4) == "WAVE") { "Missing WAVE header: ${file.name}" }
        require(readAscii(bytes, 12, 4) == "fmt ") { "Missing fmt chunk: ${file.name}" }
        require(header.getShort(20).toInt() == PCM_FORMAT) { "Only PCM WAV is supported: ${file.name}" }
        require(header.getShort(22).toInt() == CHANNELS_MONO) { "Only mono WAV is supported: ${file.name}" }
        require(header.getShort(34).toInt() == BITS_PER_SAMPLE) { "Only 16-bit WAV is supported: ${file.name}" }
        require(readAscii(bytes, 36, 4) == "data") { "Missing data chunk: ${file.name}" }

        val dataBytes = header.getInt(40).coerceAtMost(bytes.size - WAV_HEADER_BYTES)
        require(dataBytes >= 0 && dataBytes % Short.SIZE_BYTES == 0) {
            "Invalid WAV data size: ${file.name}"
        }
        val data = ByteBuffer.wrap(bytes, WAV_HEADER_BYTES, dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dataBytes / Short.SIZE_BYTES) {
            data.getShort() / 32768.0f
        }
    }

    private fun readAscii(bytes: ByteArray, offset: Int, length: Int): String {
        return bytes.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)
    }
}
