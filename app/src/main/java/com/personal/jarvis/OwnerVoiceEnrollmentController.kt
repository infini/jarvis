package com.personal.jarvis

import android.content.Context

class OwnerVoiceEnrollmentController(
    private val context: Context,
    private val postToMain: (() -> Unit) -> Unit,
    private val onProgress: (Int) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onCompleted: (Int) -> Unit,
    private val onFailed: (String) -> Unit,
) {
    private val sessionLock = Any()
    private val sessionGeneration = SessionGeneration()
    @Volatile private var enrolling = false
    private var thread: Thread? = null

    val isEnrolling: Boolean
        get() = enrolling

    fun start(durationMs: Long) {
        val token: Long
        val worker: Thread
        synchronized(sessionLock) {
            if (enrolling) return
            token = sessionGeneration.begin()
            enrolling = true
            worker = Thread({ runEnrollment(token, durationMs) }, "JarvisOwnerEnrollment")
            thread = worker
        }

        onProgress(0)
        onStatus("목소리 등록 중: 조용한 곳에서 등록 문구 '${OwnerVoiceStore.OWNER_ENROLLMENT_PHRASE}'를 여러 번 또렷하게 말하세요.")
        worker.start()
    }

    fun stop() {
        val worker = synchronized(sessionLock) {
            sessionGeneration.invalidate()
            enrolling = false
            thread.also { thread = null }
        }
        worker?.interrupt()
    }

    private fun runEnrollment(token: Long, durationMs: Long) {
        try {
            val samples = OwnerVoiceEngine.recordSamples(
                durationMs = durationMs,
                shouldContinue = { isActive(token) },
                onProgress = { progress ->
                    postToMain {
                        if (isActive(token)) {
                            val percent = (progress * 100f).toInt().coerceIn(0, 100)
                            onProgress(percent)
                            onStatus("목소리 등록 중: $percent%")
                        }
                    }
                },
            )
            if (!isActive(token)) return

            postToMain {
                if (isActive(token)) onStatus("목소리 등록 중: 음성 특징을 계산하는 중입니다.")
            }
            val embeddings = OwnerVoiceEngine.createEnrollmentEmbeddings(context, samples)
            if (embeddings.isEmpty()) {
                throw IllegalStateException("충분한 음성 특징을 만들지 못했습니다. 더 또렷하게 다시 등록하세요.")
            }
            if (embeddings.size < OwnerVoiceEngine.MIN_OWNER_EMBEDDINGS) {
                throw IllegalStateException("등록 문구 '${OwnerVoiceStore.OWNER_ENROLLMENT_PHRASE}'를 여러 번 또렷하게 말해 다시 등록하세요.")
            }

            val completionToken = synchronized(sessionLock) {
                if (!isActive(token)) return
                WakePhraseTemplateMatcher.saveEnrollmentTemplate(context, samples)
                OwnerVoiceStore.saveEmbeddings(context, embeddings)
                if (!sessionGeneration.tryComplete(token)) return
                enrolling = false
                if (thread === Thread.currentThread()) thread = null
                token + 1L
            }
            postToMain {
                if (sessionGeneration.isCurrent(completionToken)) {
                    onStatus("목소리 등록 완료: ${embeddings.size}개 음성 특징 저장됨")
                    onCompleted(embeddings.size)
                }
            }
        } catch (error: Exception) {
            completeFailure(token, error)
        }
    }

    private fun completeFailure(token: Long, error: Exception) {
        val completionToken = synchronized(sessionLock) {
            if (!isActive(token) || !sessionGeneration.tryComplete(token)) return
            enrolling = false
            if (thread === Thread.currentThread()) thread = null
            token + 1L
        }
        postToMain {
            if (sessionGeneration.isCurrent(completionToken)) {
                onFailed("목소리 등록 실패: ${error.message}")
            }
        }
    }

    private fun isActive(token: Long): Boolean =
        enrolling && sessionGeneration.isCurrent(token)

}
