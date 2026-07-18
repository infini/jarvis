package com.personal.jarvis

import android.content.Context
import android.os.Handler
import android.util.Log

class LocalCommandSession(
    private val context: Context,
    private val handler: Handler,
) {
    @Volatile private var active = false
    @Volatile private var disabled = false
    private var thread: Thread? = null
    private val generation = SessionGeneration()

    val isActive: Boolean
        get() = active

    fun canStart(): Boolean {
        return !disabled && LocalCommandRecognizer.isAvailable(context)
    }

    fun start(
        timeoutMs: Long,
        onText: (String) -> Unit,
        onComplete: (Outcome) -> Unit,
    ) {
        if (active) return

        val sessionToken = generation.begin()
        active = true
        thread = Thread({
            var failed = false
            val result = runCatching {
                LocalCommandRecognizer.listenForCommand(
                    context = context,
                    timeoutMs = timeoutMs,
                    shouldContinue = {
                        active &&
                            generation.isCurrent(sessionToken) &&
                            !Thread.currentThread().isInterrupted
                    },
                    onText = { text ->
                        handler.post {
                            if (active && generation.isCurrent(sessionToken)) {
                                onText(text)
                            }
                        }
                    },
                )
            }.onFailure {
                failed = true
                Log.w(TAG, "Local command recognition failed: ${it.message}")
            }.getOrNull()

            handler.post {
                if (!active || !generation.tryComplete(sessionToken)) return@post

                active = false
                thread = null
                val outcome = Outcome(result = result, failed = failed)
                if (outcome.unavailable) disabled = true
                onComplete(outcome)
            }
        }, "JarvisLocalCommand").also { it.start() }
    }

    fun stop() {
        generation.invalidate()
        active = false
        thread?.interrupt()
        thread = null
    }

    data class Outcome(
        val result: LocalCommandRecognizer.Result?,
        val failed: Boolean,
    ) {
        val unavailable: Boolean
            get() = failed || result?.unavailable == true
    }

    companion object {
        private const val TAG = "JarvisLocalCommandSession"
    }
}
