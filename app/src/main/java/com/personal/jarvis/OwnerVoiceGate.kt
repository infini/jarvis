package com.personal.jarvis

import android.content.Context
import android.os.Handler
import android.util.Log

class OwnerVoiceGate(
    private val context: Context,
    private val handler: Handler,
    private val onAuthorized: (OwnerVoiceEngine.Match) -> Unit,
    private val onMissingProfile: () -> Unit,
    private val onVerificationError: (Exception) -> Unit,
) {
    @Volatile private var verifying = false
    @Volatile private var authorizedUntil = 0L
    @Volatile private var nonStrictSuppressedUntil = 0L
    private var verificationThread: Thread? = null

    val isVerifying: Boolean
        get() = verifying

    fun isConfigured(): Boolean = OwnerVoiceStore.isConfigured(context)

    fun isAuthorized(): Boolean {
        return System.currentTimeMillis() < authorizedUntil
    }

    fun authorizeFor(durationMs: Long) {
        authorizedUntil = System.currentTimeMillis() + durationMs
    }

    fun clearAuthorization() {
        authorizedUntil = 0L
    }

    fun extendAuthorization(durationMs: Long) {
        authorizedUntil = authorizedUntil.coerceAtLeast(System.currentTimeMillis() + durationMs)
    }

    fun suppressNonStrictFor(durationMs: Long) {
        nonStrictSuppressedUntil = System.currentTimeMillis() + durationMs
    }

    fun startVerification(
        audioWindowMs: Long,
        verificationIntervalMs: Long,
        authorizationWindowMs: Long,
    ) {
        if (verifying) return

        if (!isConfigured()) {
            Log.w(TAG, "Owner voice embedding is not configured; falling back to speech recognition")
            onMissingProfile()
            return
        }

        verifying = true
        verificationThread = Thread({
            try {
                val match = OwnerVoiceEngine.waitForOwnerMatch(
                    context = context,
                    windowMs = audioWindowMs,
                    verificationIntervalMs = verificationIntervalMs,
                    shouldContinue = {
                        verifying && !Thread.currentThread().isInterrupted
                    },
                    onMatch = { candidate ->
                        Log.d(
                            TAG,
                            "Owner voice ${statusFor(candidate)}: " +
                                "score=${candidate.score}, speech=${candidate.activeSpeechMs}ms, " +
                                "elapsed=${candidate.verificationElapsedMs}ms, " +
                                "attempts=${candidate.verificationAttempts}, " +
                                "profileEmbeddings=${candidate.ownerEmbeddingCount}, " +
                                "peakRms=${candidate.peakRms}, noiseRms=${candidate.noiseFloorRms}, " +
                                "thresholdRms=${candidate.activeThresholdRms}, " +
                                "reason=${candidate.rejectReason ?: "none"}",
                        )
                    },
                    shouldAccept = { candidate ->
                        !isNonStrictSuppressed(candidate)
                    },
                )
                if (!verifying || Thread.currentThread().isInterrupted) return@Thread

                handler.post {
                    if (!verifying) return@post

                    verifying = false
                    verificationThread = null
                    if (match?.accepted == true) {
                        authorizeFor(authorizationWindowMs)
                        onAuthorized(match)
                    } else {
                        onVerificationError(IllegalStateException("Owner voice verification ended without a match"))
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    if (!verifying) return@post

                    verifying = false
                    verificationThread = null
                    Log.w(TAG, "Owner voice verification failed: ${e.message}")
                    onVerificationError(e)
                }
            }
        }, "JarvisOwnerVerify").also { it.start() }
        Log.d(TAG, "Owner voice verification started")
    }

    fun stop() {
        verifying = false
        verificationThread?.interrupt()
        verificationThread = null
    }

    private fun statusFor(match: OwnerVoiceEngine.Match): String {
        if (match.accepted && isNonStrictSuppressed(match)) return "suppressed-non-strict"

        return when (match.acceptance) {
            OwnerVoiceEngine.Acceptance.STRICT -> "accepted"
            OwnerVoiceEngine.Acceptance.HIGH_CONFIDENCE_SINGLE -> "accepted-high-confidence"
            OwnerVoiceEngine.Acceptance.NEAR_CONSECUTIVE -> "accepted-near"
            OwnerVoiceEngine.Acceptance.SOFT_WAKE_SINGLE -> "accepted-soft-wake-single"
            OwnerVoiceEngine.Acceptance.SOFT_WAKE_CONSECUTIVE -> "accepted-soft-wake"
            OwnerVoiceEngine.Acceptance.REJECTED -> "rejected"
        }
    }

    private fun isNonStrictSuppressed(match: OwnerVoiceEngine.Match): Boolean {
        if (!match.accepted || match.acceptance == OwnerVoiceEngine.Acceptance.STRICT) return false
        if (match.acceptance != OwnerVoiceEngine.Acceptance.SOFT_WAKE_CONSECUTIVE) return false

        return System.currentTimeMillis() < nonStrictSuppressedUntil
    }

    companion object {
        private const val TAG = "OwnerVoiceGate"
    }
}
