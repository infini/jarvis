package com.personal.jarvis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JarvisSetupStatusTest {
    @Test
    fun `required setup advances in user flow order`() {
        val empty = status()
        assertEquals(JarvisSetupStatus.RequiredStep.MICROPHONE, empty.nextRequiredStep)
        assertEquals(3, empty.remainingRequiredSteps)

        val microphoneReady = status(microphone = true)
        assertEquals(JarvisSetupStatus.RequiredStep.OWNER_VOICE, microphoneReady.nextRequiredStep)

        val ownerReady = status(microphone = true, owner = true)
        assertEquals(JarvisSetupStatus.RequiredStep.ACCESSIBILITY, ownerReady.nextRequiredStep)
    }

    @Test
    fun `core listening readiness does not depend on optional reliability settings`() {
        val ready = status(
            microphone = true,
            owner = true,
            accessibility = true,
            notifications = false,
            assistantAvailable = true,
            assistantHeld = false,
            battery = false,
        )

        assertTrue(ready.canListen)
        assertEquals(3, ready.completedRequiredSteps)
        assertEquals(0, ready.remainingRequiredSteps)
        assertNull(ready.nextRequiredStep)
        assertFalse(ready.quickLaunchConfigured)
    }

    @Test
    fun `unavailable assistant role is treated as already configured`() {
        assertTrue(status(assistantAvailable = false).quickLaunchConfigured)
    }

    private fun status(
        microphone: Boolean = false,
        notifications: Boolean = false,
        owner: Boolean = false,
        accessibility: Boolean = false,
        assistantAvailable: Boolean = false,
        assistantHeld: Boolean = false,
        battery: Boolean = false,
    ) = JarvisSetupStatus(
        microphoneGranted = microphone,
        notificationsGranted = notifications,
        ownerVoiceConfigured = owner,
        accessibilityReady = accessibility,
        assistantRoleAvailable = assistantAvailable,
        assistantRoleHeld = assistantHeld,
        batteryOptimizationDisabled = battery,
    )
}
