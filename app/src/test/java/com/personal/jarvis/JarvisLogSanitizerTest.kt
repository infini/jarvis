package com.personal.jarvis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class JarvisLogSanitizerTest {
    @Test
    fun `release detail removes speech fields and everything that follows`() {
        val sanitized = JarvisLogSanitizer.latencyDetail(
            "count=2 candidates=자비스_사진_찍어|서비스_사진 photo=a:take_photo:wcf first=자비스_사진 text=자비스 사진 찍어 elapsedMs=42",
            includeSensitiveSpeech = false,
        )

        assertEquals(
            "count=2 candidates=redacted",
            sanitized,
        )
        assertFalse(sanitized.contains("자비스"))
    }

    @Test
    fun `release detail cannot be escaped by key shaped speech`() {
        val sanitized = JarvisLogSanitizer.latencyDetail(
            "source=local text=내 비밀번호 secret=123 elapsedMs=42",
            includeSensitiveSpeech = false,
        )

        assertEquals("source=local text=redacted", sanitized)
        assertFalse(sanitized.contains("secret"))
        assertFalse(sanitized.contains("123"))
    }

    @Test
    fun `debug detail keeps exact diagnostic speech`() {
        val detail = "text=자비스 사진 찍어 endpoint=android"
        assertEquals(
            detail,
            JarvisLogSanitizer.latencyDetail(detail, includeSensitiveSpeech = true),
        )
    }
}
