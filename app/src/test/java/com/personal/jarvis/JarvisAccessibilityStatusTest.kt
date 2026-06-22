package com.personal.jarvis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JarvisAccessibilityStatusTest {
    @Test
    fun reportsOffWhenFrameworkOrServiceSettingIsMissing() {
        val disabled = JarvisAccessibilityStatus.Status(
            frameworkEnabled = false,
            serviceConfigured = true,
            serviceBoundInProcess = true,
        )
        val notConfigured = JarvisAccessibilityStatus.Status(
            frameworkEnabled = true,
            serviceConfigured = false,
            serviceBoundInProcess = true,
        )

        assertFalse(disabled.isReadyForAutomation)
        assertEquals("꺼짐", disabled.label)
        assertTrue(disabled.guidance!!.contains("접근성 서비스"))

        assertFalse(notConfigured.isReadyForAutomation)
        assertEquals("꺼짐", notConfigured.label)
        assertTrue(notConfigured.guidance!!.contains("접근성 서비스"))
    }

    @Test
    fun reportsReconnectNeededWhenServiceIsConfiguredButNotBound() {
        val status = JarvisAccessibilityStatus.Status(
            frameworkEnabled = true,
            serviceConfigured = true,
            serviceBoundInProcess = false,
        )

        assertFalse(status.isReadyForAutomation)
        assertEquals("연결 필요", status.label)
        assertTrue(status.guidance!!.contains("껐다가 다시 켜세요"))
    }

    @Test
    fun reportsReadyOnlyWhenConfiguredAndBound() {
        val status = JarvisAccessibilityStatus.Status(
            frameworkEnabled = true,
            serviceConfigured = true,
            serviceBoundInProcess = true,
        )

        assertTrue(status.isReadyForAutomation)
        assertEquals("켜짐", status.label)
        assertNull(status.guidance)
    }
}
