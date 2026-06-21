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
    @Volatile private var enrolling = false
    private var thread: Thread? = null

    val isEnrolling: Boolean
        get() = enrolling

    fun start(durationMs: Long) {
        if (enrolling) return

        enrolling = true
        onProgress(0)
        onStatus("목소리 등록 중: 조용한 곳에서 등록 문구 '${OwnerVoiceStore.OWNER_ENROLLMENT_PHRASE}'를 여러 번 또렷하게 말하세요.")

        thread = Thread({
            try {
                val samples = OwnerVoiceEngine.recordSamples(
                    durationMs = durationMs,
                    shouldContinue = { enrolling },
                    onProgress = { progress ->
                        postToMain {
                            if (enrolling) {
                                val percent = (progress * 100f).toInt().coerceIn(0, 100)
                                onProgress(percent)
                                onStatus("목소리 등록 중: $percent%")
                            }
                        }
                    },
                )

                if (!enrolling) return@Thread

                postToMain {
                    if (enrolling) onStatus("목소리 등록 중: 음성 특징을 계산하는 중입니다.")
                }

                writeEnrollmentTemplate(samples)
                val embeddings = OwnerVoiceEngine.createEnrollmentEmbeddings(context, samples)
                if (embeddings.isEmpty()) {
                    throw IllegalStateException("충분한 음성 특징을 만들지 못했습니다. 더 또렷하게 다시 등록하세요.")
                }
                if (embeddings.size < OwnerVoiceEngine.MIN_OWNER_EMBEDDINGS) {
                    throw IllegalStateException("등록 문구 '${OwnerVoiceStore.OWNER_ENROLLMENT_PHRASE}'를 여러 번 또렷하게 말해 다시 등록하세요.")
                }

                if (!enrolling) return@Thread

                OwnerVoiceStore.saveEmbeddings(context, embeddings)
                postToMain {
                    if (enrolling) {
                        onStatus("목소리 등록 완료: ${embeddings.size}개 음성 특징 저장됨")
                        enrolling = false
                        thread = null
                        onCompleted(embeddings.size)
                    }
                }
            } catch (e: Exception) {
                if (enrolling) {
                    postToMain {
                        if (enrolling) {
                            enrolling = false
                            thread = null
                            onFailed("목소리 등록 실패: ${e.message}")
                        }
                    }
                }
            }
        }, "JarvisOwnerEnrollment").also { it.start() }
    }

    fun stop() {
        enrolling = false
        thread?.interrupt()
        thread = null
    }

    private fun writeEnrollmentTemplate(samples: FloatArray) {
        PcmWavFile.writeMono16(
            file = context.cacheDir.resolve(ENROLLMENT_WAV_NAME),
            samples = samples,
            sampleRateHz = OwnerVoiceEngine.SAMPLE_RATE_HZ,
        )
        WakePhraseTemplateMatcher.invalidateCache()
    }

    private companion object {
        private const val ENROLLMENT_WAV_NAME = "jarvis-owner-enroll-last.wav"
    }
}
