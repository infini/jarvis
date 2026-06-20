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

        active = true
        thread = Thread({
            var failed = false
            val result = runCatching {
                LocalCommandRecognizer.listenForCommand(
                    context = context,
                    timeoutMs = timeoutMs,
                    shouldContinue = {
                        active && !Thread.currentThread().isInterrupted
                    },
                    onText = onText,
                )
            }.onFailure {
                failed = true
                Log.w(TAG, "Local command recognition failed: ${it.message}")
            }.getOrNull()

            handler.post {
                if (!active) return@post

                active = false
                thread = null
                val outcome = Outcome(result = result, failed = failed)
                if (outcome.unavailable) disabled = true
                onComplete(outcome)
            }
        }, "JarvisLocalCommand").also { it.start() }
    }

    fun stop() {
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
