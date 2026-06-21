package com.personal.jarvis.debug

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.personal.jarvis.CommandInterpreter
import com.personal.jarvis.OwnerVoiceEngine
import com.personal.jarvis.OwnerVoiceStore
import com.personal.jarvis.PcmWavFile
import org.json.JSONObject
import java.io.File

class JarvisDebugOwnerCalibrateService : Service() {
    @Volatile private var calibrating = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestId = intent
            ?.getStringExtra(JarvisDebugOwnerCalibrateActivity.EXTRA_REQUEST_ID)
            .orEmpty()
        if (calibrating) {
            Log.e(TAG, "request_id=$requestId status=failed reason=calibration_already_running")
            return START_NOT_STICKY
        }

        calibrating = true
        Thread({
            try {
                calibrateFromActivationCaptures(requestId)
            } finally {
                calibrating = false
                stopSelf(startId)
            }
        }, "JarvisOwnerCalibrate").start()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun calibrateFromActivationCaptures(requestId: String) {
        val existingEmbeddings = OwnerVoiceStore.getEmbeddings(this).filter { it.isNotEmpty() }
        if (existingEmbeddings.size < OwnerVoiceStore.MIN_CONFIGURED_EMBEDDINGS) {
            Log.e(
                TAG,
                "request_id=$requestId status=failed reason=owner_profile_not_configured " +
                    "profile_embeddings=${existingEmbeddings.size}",
            )
            return
        }

        val captureDir = File(cacheDir, CAPTURE_DIR)
        val metadataFiles = captureDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        if (metadataFiles.isEmpty()) {
            Log.e(TAG, "request_id=$requestId status=failed reason=no_activation_metadata")
            return
        }

        val mergedEmbeddings = existingEmbeddings.toMutableList()
        var phraseCaptureCount = 0
        var newEmbeddingCount = 0
        metadataFiles.take(MAX_CAPTURE_FILES_TO_SCAN).forEach { metadataFile ->
            if (mergedEmbeddings.size >= OwnerVoiceEngine.MAX_OWNER_EMBEDDINGS) return@forEach
            val metadata = runCatching { JSONObject(metadataFile.readText(Charsets.UTF_8)) }
                .getOrNull()
                ?: return@forEach
            if (!metadata.optBoolean("accepted", false)) return@forEach

            val text = metadata.optString("text", "")
            if (!CommandInterpreter.isActivationWakeAsrEquivalent(text)) return@forEach

            val wavFile = File(metadataFile.parentFile, metadataFile.nameWithoutExtension + ".wav")
            if (!wavFile.isFile) return@forEach

            phraseCaptureCount += 1
            val samples = runCatching { PcmWavFile.readMono16(wavFile) }
                .getOrNull()
                ?: return@forEach
            val embeddings = OwnerVoiceEngine.createEnrollmentEmbeddings(this, samples)
            embeddings.forEach { embedding ->
                if (mergedEmbeddings.size >= OwnerVoiceEngine.MAX_OWNER_EMBEDDINGS) return@forEach
                if (isDuplicateEmbedding(embedding, mergedEmbeddings)) return@forEach

                mergedEmbeddings += embedding
                newEmbeddingCount += 1
            }
            Log.i(
                TAG,
                "request_id=$requestId status=capture_processed file=${wavFile.name} " +
                    "candidate_embeddings=${embeddings.size} profile_embeddings=${mergedEmbeddings.size}",
            )
        }

        if (newEmbeddingCount <= 0) {
            Log.e(
                TAG,
                "request_id=$requestId status=failed reason=no_new_embeddings " +
                    "phrase_captures=$phraseCaptureCount profile_embeddings=${existingEmbeddings.size}",
            )
            return
        }

        OwnerVoiceStore.saveEmbeddings(this, mergedEmbeddings)
        Log.i(
            TAG,
            "request_id=$requestId status=completed " +
                "phrase_captures=$phraseCaptureCount added_embeddings=$newEmbeddingCount " +
                "profile_embeddings=${mergedEmbeddings.size} " +
                "profile_phrase_id=${OwnerVoiceStore.OWNER_ENROLLMENT_PHRASE_ID}",
        )
    }

    private fun isDuplicateEmbedding(candidate: FloatArray, embeddings: List<FloatArray>): Boolean {
        return embeddings.any { existing ->
            OwnerVoiceEngine.cosineSimilarity(candidate, existing) >= DUPLICATE_SIMILARITY
        }
    }

    companion object {
        private const val TAG = "JarvisDebugCalibrate"
        private const val CAPTURE_DIR = "jarvis-activation-attempts"
        private const val MAX_CAPTURE_FILES_TO_SCAN = 80
        private const val DUPLICATE_SIMILARITY = 0.995f
    }
}
