package com.personal.jarvis

object JarvisLogSanitizer {
    private val sensitiveLatencyField = Regex("(^|\\s)(text|first|candidates|photo)=")

    fun latencyDetail(detail: String, includeSensitiveSpeech: Boolean): String {
        if (detail.isBlank() || includeSensitiveSpeech) return detail
        val firstSensitiveField = sensitiveLatencyField.find(detail) ?: return detail
        return buildString {
            append(detail.substring(0, firstSensitiveField.range.first))
            append(firstSensitiveField.groupValues[1])
            append(firstSensitiveField.groupValues[2])
            append("=redacted")
        }
    }
}
