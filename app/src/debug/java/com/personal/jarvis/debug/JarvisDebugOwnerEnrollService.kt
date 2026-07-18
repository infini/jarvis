package com.personal.jarvis.debug

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.personal.jarvis.JarvisNotificationController
import com.personal.jarvis.JarvisVoiceService
import com.personal.jarvis.JarvisVoiceServiceStarter
import com.personal.jarvis.CommandInterpreter
import com.personal.jarvis.LocalCommandRecognizer
import com.personal.jarvis.OwnerVoiceEngine
import com.personal.jarvis.OwnerVoiceStore
import com.personal.jarvis.WakePhraseTemplateMatcher

class JarvisDebugOwnerEnrollService : Service() {
    @Volatile private var enrolling = false
    private lateinit var notificationController: JarvisNotificationController

    override fun onCreate() {
        super.onCreate()
        notificationController = JarvisNotificationController(
            service = this,
            defaultText = "Debug owner voice enrollment",
        )
        notificationController.createChannel()
        notificationController.startForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val durationMs = intent
            ?.getLongExtra(JarvisDebugOwnerEnrollActivity.EXTRA_DURATION_MS, DEFAULT_DURATION_MS)
            ?.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
            ?: DEFAULT_DURATION_MS
        val requestId = intent?.getStringExtra(JarvisDebugOwnerEnrollActivity.EXTRA_REQUEST_ID).orEmpty()

        if (enrolling) {
            Log.e(TAG, "request_id=$requestId status=failed reason=enrollment_already_running")
            return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "request_id=$requestId status=failed reason=record_audio_permission_missing")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        enrolling = true
        JarvisVoiceServiceStarter.setOwnerEnrollmentActive(true)
        stopService(Intent(this, JarvisVoiceService::class.java))
        Log.i(TAG, "request_id=$requestId status=recording durationMs=$durationMs")

        Thread({
            var completedEmbeddingCount = 0
            try {
                val samples = OwnerVoiceEngine.recordSamples(
                    durationMs = durationMs,
                    shouldContinue = { enrolling },
                )
                Log.i(TAG, "request_id=$requestId status=embedding samples=${samples.size}")
                val activationCheck = LocalCommandRecognizer.recognizeBufferedActivation(
                    context = this,
                    samples = samples,
                    endpoint = "owner_enrollment",
                )
                Log.i(
                    TAG,
                    "request_id=$requestId status=activation_check " +
                        "text=${activationCheck.text} peakRms=${activationCheck.peakRms} " +
                        "meanRms=${activationCheck.meanRms} asrGain=${activationCheck.asrGain}",
                )
                if (!CommandInterpreter.containsActivationWake(activationCheck.text)) {
                    Log.e(
                        TAG,
                        "request_id=$requestId status=failed reason=activation_phrase_missing " +
                            "text=${activationCheck.text}",
                    )
                    return@Thread
                }

                val embeddings = OwnerVoiceEngine.createEnrollmentEmbeddings(this, samples)
                if (embeddings.size < OwnerVoiceEngine.MIN_OWNER_EMBEDDINGS) {
                    val summary = OwnerVoiceEngine.summarizeAudio(samples)
                    Log.e(
                        TAG,
                        "request_id=$requestId status=failed reason=not_enough_embeddings " +
                            "profile_embeddings=${embeddings.size} " +
                            "durationMs=${summary.durationMs} " +
                            "peakRms=${summary.peakFrameRms} meanRms=${summary.meanRms}",
                    )
                    return@Thread
                }

                val debugWav = WakePhraseTemplateMatcher.saveEnrollmentTemplate(this, samples)
                Log.i(TAG, "request_id=$requestId status=debug_wav path=${debugWav.absolutePath}")
                OwnerVoiceStore.saveEmbeddings(this, embeddings)
                completedEmbeddingCount = embeddings.size
            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "request_id=$requestId status=failed " +
                        "reason=${error.javaClass.simpleName} message=${error.message}",
                )
            } finally {
                enrolling = false
                JarvisVoiceServiceStarter.setOwnerEnrollmentActive(false)
                if (completedEmbeddingCount > 0) {
                    val started = JarvisVoiceServiceStarter.start(this, "debug_owner_enrollment_completed")
                    Log.i(
                        TAG,
                        "request_id=$requestId status=completed " +
                            "profile_embeddings=$completedEmbeddingCount " +
                            "profile_phrase_id=${OwnerVoiceStore.OWNER_ENROLLMENT_PHRASE_ID} " +
                            "service_start_requested=$started",
                    )
                }
                stopSelf(startId)
            }
        }, "JarvisDebugOwnerEnroll").start()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        enrolling = false
        JarvisVoiceServiceStarter.setOwnerEnrollmentActive(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "JarvisDebugEnroll"
        private const val DEFAULT_DURATION_MS = 6_000L
        private const val MIN_DURATION_MS = 3_000L
        private const val MAX_DURATION_MS = 12_000L
    }
}
