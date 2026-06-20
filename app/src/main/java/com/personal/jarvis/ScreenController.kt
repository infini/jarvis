package com.personal.jarvis

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

object ScreenController {
    private const val TAG = "ScreenController"
    private const val WAKE_TIMEOUT_MS = 2_000L

    @Suppress("DEPRECATION")
    fun wake(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        if (powerManager.isInteractive) {
            Log.d(TAG, "Screen is already interactive")
            return true
        }

        return try {
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "${context.packageName}:wake-screen",
            )
            wakeLock.acquire(WAKE_TIMEOUT_MS)
            Log.d(TAG, "Wake lock acquired for $WAKE_TIMEOUT_MS ms")
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Failed to wake screen", error)
            false
        }
    }

    fun sleep(service: AccessibilityService): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.w(TAG, "Lock screen global action requires Android 9+")
            return false
        }

        val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        Log.d(TAG, "Lock screen global action result=$success")
        return success
    }
}
