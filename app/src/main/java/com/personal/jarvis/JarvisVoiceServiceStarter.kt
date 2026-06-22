package com.personal.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

object JarvisVoiceServiceStarter {
    const val EXTRA_START_SOURCE = "start_source"
    const val EXTRA_COMMAND_WINDOW_MS = "command_window_ms"
    private const val TAG = "JarvisVoiceStarter"
    private const val DEFAULT_COMMAND_WINDOW_MS = 30000L
    @Volatile private var ownerEnrollmentActive = false

    fun setOwnerEnrollmentActive(active: Boolean) {
        ownerEnrollmentActive = active
    }

    fun start(context: Context, source: String): Boolean {
        return startInternal(context, source, commandWindowMs = null)
    }

    fun openCommandWindow(
        context: Context,
        source: String,
        commandWindowMs: Long = DEFAULT_COMMAND_WINDOW_MS,
    ): Boolean {
        return startInternal(context, source, commandWindowMs)
    }

    private fun startInternal(
        context: Context,
        source: String,
        commandWindowMs: Long?,
    ): Boolean {
        val appContext = context.applicationContext
        autoStartBlockReason(appContext)?.let { reason ->
            Log.w(TAG, "Blocked JarvisVoiceService start from $source: $reason")
            return false
        }
        if (JarvisVoiceService.isRunning && commandWindowMs == null) return true

        val intent = Intent(appContext, JarvisVoiceService::class.java)
            .putExtra(EXTRA_START_SOURCE, source)
        if (commandWindowMs != null) {
            intent.putExtra(EXTRA_COMMAND_WINDOW_MS, commandWindowMs)
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
            Log.d(TAG, "Requested JarvisVoiceService start: source=$source")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start JarvisVoiceService from $source: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    fun autoStartBlockReason(context: Context): String? {
        val appContext = context.applicationContext
        if (ownerEnrollmentActive) {
            return "owner_voice_enrollment_active"
        }
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return "record_audio_permission_missing"
        }
        if (!OwnerVoiceStore.hasProfile(appContext)) {
            return "owner_voice_profile_missing"
        }
        if (!OwnerVoiceStore.isConfigured(appContext)) {
            return "owner_voice_profile_reenrollment_required"
        }
        return null
    }

}
