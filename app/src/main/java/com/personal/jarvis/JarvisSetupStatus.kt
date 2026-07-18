package com.personal.jarvis

import android.Manifest
import android.annotation.TargetApi
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager

data class JarvisSetupStatus(
    val microphoneGranted: Boolean,
    val notificationsGranted: Boolean,
    val ownerVoiceConfigured: Boolean,
    val accessibilityReady: Boolean,
    val assistantRoleAvailable: Boolean,
    val assistantRoleHeld: Boolean,
    val batteryOptimizationDisabled: Boolean,
) {
    enum class RequiredStep {
        MICROPHONE,
        OWNER_VOICE,
        ACCESSIBILITY,
    }

    val completedRequiredSteps: Int
        get() = listOf(microphoneGranted, ownerVoiceConfigured, accessibilityReady).count { it }

    val requiredStepCount: Int
        get() = 3

    val remainingRequiredSteps: Int
        get() = requiredStepCount - completedRequiredSteps

    val canListen: Boolean
        get() = remainingRequiredSteps == 0

    val nextRequiredStep: RequiredStep?
        get() = when {
            !microphoneGranted -> RequiredStep.MICROPHONE
            !ownerVoiceConfigured -> RequiredStep.OWNER_VOICE
            !accessibilityReady -> RequiredStep.ACCESSIBILITY
            else -> null
        }

    val quickLaunchConfigured: Boolean
        get() = !assistantRoleAvailable || assistantRoleHeld

    companion object {
        fun capture(context: Context): JarvisSetupStatus {
            val appContext = context.applicationContext
            val assistantStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                captureAssistantStatus(appContext)
            } else {
                false to false
            }
            val powerManager = appContext.getSystemService(PowerManager::class.java)

            return JarvisSetupStatus(
                microphoneGranted = appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED,
                notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED,
                ownerVoiceConfigured = OwnerVoiceStore.isConfigured(appContext),
                accessibilityReady = JarvisAccessibilityStatus.current(appContext).isReadyForAutomation,
                assistantRoleAvailable = assistantStatus.first,
                assistantRoleHeld = assistantStatus.second,
                batteryOptimizationDisabled = powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true,
            )
        }

        @TargetApi(Build.VERSION_CODES.Q)
        private fun captureAssistantStatus(context: Context): Pair<Boolean, Boolean> {
            val roleManager = context.getSystemService(RoleManager::class.java)
            val available = roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true
            return available to (available && roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true)
        }
    }
}
