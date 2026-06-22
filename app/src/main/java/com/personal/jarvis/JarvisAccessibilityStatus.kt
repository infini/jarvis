package com.personal.jarvis

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object JarvisAccessibilityStatus {
    fun isEnabled(context: Context): Boolean {
        val appContext = context.applicationContext
        val expected = ComponentName(
            appContext,
            JarvisAccessibilityService::class.java,
        ).flattenToString()
        val enabledServices = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        return enabledServices.split(':').any { service ->
            service.equals(expected, ignoreCase = true)
        }
    }
}
