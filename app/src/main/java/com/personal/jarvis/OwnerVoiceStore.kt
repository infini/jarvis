package com.personal.jarvis

import android.content.Context
import android.util.Base64

object OwnerVoiceStore {
    private const val PREFS_NAME = "owner_voice"
    private const val KEY_ACCESS_KEY = "picovoice_access_key"
    private const val KEY_PROFILE = "owner_profile"

    const val DEFAULT_ACCEPT_THRESHOLD = 0.72f

    fun getAccessKey(context: Context): String {
        return prefs(context).getString(KEY_ACCESS_KEY, "").orEmpty()
    }

    fun saveAccessKey(context: Context, accessKey: String) {
        prefs(context)
            .edit()
            .putString(KEY_ACCESS_KEY, accessKey.trim())
            .apply()
    }

    fun hasAccessKey(context: Context): Boolean = getAccessKey(context).isNotBlank()

    fun saveProfile(context: Context, profileBytes: ByteArray) {
        val encoded = Base64.encodeToString(profileBytes, Base64.NO_WRAP)
        prefs(context)
            .edit()
            .putString(KEY_PROFILE, encoded)
            .apply()
    }

    fun getProfileBytes(context: Context): ByteArray? {
        val encoded = prefs(context).getString(KEY_PROFILE, null) ?: return null
        return runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
    }

    fun hasProfile(context: Context): Boolean = getProfileBytes(context) != null

    fun isConfigured(context: Context): Boolean {
        return hasAccessKey(context) && hasProfile(context)
    }

    fun clearProfile(context: Context) {
        prefs(context)
            .edit()
            .remove(KEY_PROFILE)
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
