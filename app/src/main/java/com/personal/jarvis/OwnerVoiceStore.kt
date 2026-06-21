package com.personal.jarvis

import android.content.Context
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder

object OwnerVoiceStore {
    private const val PREFS_NAME = "owner_voice"
    private const val KEY_EMBEDDINGS = "owner_embeddings_v2"
    private const val KEY_EMBEDDING = "owner_embedding_v1"
    private const val KEY_ENROLLMENT_PHRASE_ID = "owner_enrollment_phrase_id"
    private const val KEY_ACCESS_KEY_LEGACY = "picovoice_access_key"
    private const val KEY_PROFILE_LEGACY = "owner_profile"

    const val MODEL_ASSET_NAME = "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"
    const val DEFAULT_ACCEPT_THRESHOLD = 0.50f
    const val MIN_CONFIGURED_EMBEDDINGS = 2
    const val OWNER_ENROLLMENT_PHRASE = "자비스 깨어나"
    const val OWNER_ENROLLMENT_PHRASE_ID = "jarvis_activation_v3"

    fun saveEmbedding(context: Context, embedding: FloatArray) {
        saveEmbeddings(context, listOf(embedding))
    }

    fun saveEmbeddings(context: Context, embeddings: List<FloatArray>) {
        val validEmbeddings = embeddings.filter { it.isNotEmpty() }
        if (validEmbeddings.isEmpty()) return

        val encoded = validEmbeddings.joinToString("\n", transform = ::encodeEmbedding)
        prefs(context)
            .edit()
            .putString(KEY_EMBEDDINGS, encoded)
            .putString(KEY_EMBEDDING, encodeEmbedding(validEmbeddings.first()))
            .putString(KEY_ENROLLMENT_PHRASE_ID, OWNER_ENROLLMENT_PHRASE_ID)
            .apply()
    }

    fun getEmbedding(context: Context): FloatArray? = getEmbeddings(context).firstOrNull()

    fun getEmbeddings(context: Context): List<FloatArray> {
        val stored = prefs(context).getString(KEY_EMBEDDINGS, null)
            ?.lineSequence()
            ?.mapNotNull { decodeEmbedding(it.trim()) }
            ?.filter { it.isNotEmpty() }
            ?.toList()
            .orEmpty()
        if (stored.isNotEmpty()) return stored

        return getLegacyEmbedding(context)?.let(::listOf).orEmpty()
    }

    fun embeddingCount(context: Context): Int = getEmbeddings(context).size

    fun enrollmentPhraseId(context: Context): String? = prefs(context).getString(KEY_ENROLLMENT_PHRASE_ID, null)

    fun hasProfile(context: Context): Boolean = getEmbeddings(context).isNotEmpty()

    fun isConfigured(context: Context): Boolean {
        return embeddingCount(context) >= MIN_CONFIGURED_EMBEDDINGS &&
            enrollmentPhraseId(context) == OWNER_ENROLLMENT_PHRASE_ID
    }

    fun clearProfile(context: Context) {
        prefs(context)
            .edit()
                .remove(KEY_EMBEDDINGS)
                .remove(KEY_EMBEDDING)
                .remove(KEY_ENROLLMENT_PHRASE_ID)
                .remove(KEY_ACCESS_KEY_LEGACY)
                .remove(KEY_PROFILE_LEGACY)
            .apply()
    }

    private fun encodeEmbedding(embedding: FloatArray): String {
        val bytes = ByteBuffer.allocate(embedding.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                embedding.forEach(::putFloat)
            }
            .array()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun getLegacyEmbedding(context: Context): FloatArray? {
        val encoded = prefs(context).getString(KEY_EMBEDDING, null) ?: return null
        return decodeEmbedding(encoded)
    }

    private fun decodeEmbedding(encoded: String): FloatArray? {
        val bytes = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size % Float.SIZE_BYTES != 0) return null

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.float }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
