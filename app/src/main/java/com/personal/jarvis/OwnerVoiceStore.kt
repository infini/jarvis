package com.personal.jarvis

import android.content.Context
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder

object OwnerVoiceStore {
    private const val PREFS_NAME = "owner_voice"
    private const val KEY_EMBEDDING = "owner_embedding_v1"
    private const val KEY_ACCESS_KEY_LEGACY = "picovoice_access_key"
    private const val KEY_PROFILE_LEGACY = "owner_profile"

    const val MODEL_ASSET_NAME = "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"
    const val DEFAULT_ACCEPT_THRESHOLD = 0.50f

    fun saveEmbedding(context: Context, embedding: FloatArray) {
        val bytes = ByteBuffer.allocate(embedding.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                embedding.forEach(::putFloat)
            }
            .array()
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        prefs(context)
            .edit()
            .putString(KEY_EMBEDDING, encoded)
            .apply()
    }

    fun getEmbedding(context: Context): FloatArray? {
        val encoded = prefs(context).getString(KEY_EMBEDDING, null) ?: return null
        val bytes = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size % Float.SIZE_BYTES != 0) return null

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.float }
    }

    fun hasProfile(context: Context): Boolean = getEmbedding(context) != null

    fun isConfigured(context: Context): Boolean = hasProfile(context)

    fun clearProfile(context: Context) {
        prefs(context)
            .edit()
            .remove(KEY_EMBEDDING)
            .remove(KEY_ACCESS_KEY_LEGACY)
            .remove(KEY_PROFILE_LEGACY)
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
