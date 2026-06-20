package com.personal.jarvis

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

class JarvisLatencyTrace private constructor(
    val id: Long,
    val startedAtMs: Long,
) {
    private var lastEventAtMs = startedAtMs

    fun mark(event: String, detail: String = "") {
        val now = SystemClock.elapsedRealtime()
        val totalMs = now - startedAtMs
        val stepMs = now - lastEventAtMs
        lastEventAtMs = now
        log(id, "total=${totalMs}ms step=${stepMs}ms event=$event", detail)
    }

    fun finish(event: String, detail: String = "") {
        mark(event, detail)
    }

    companion object {
        const val TAG = "JarvisLatency"
        private val nextId = AtomicLong(1L)

        fun start(event: String, detail: String = ""): JarvisLatencyTrace {
            val trace = JarvisLatencyTrace(nextId.getAndIncrement(), SystemClock.elapsedRealtime())
            trace.mark(event, detail)
            return trace
        }

        fun logExternal(traceId: Long?, event: String, detail: String = "") {
            val id = traceId?.takeIf { it > 0L }?.toString() ?: "none"
            val suffix = if (detail.isBlank()) "" else " $detail"
            Log.i(TAG, "trace=$id event=$event$suffix")
        }

        fun elapsedSince(startedAtMs: Long): Long {
            return (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)
        }

        private fun log(traceId: Long, body: String, detail: String) {
            val suffix = if (detail.isBlank()) "" else " $detail"
            Log.i(TAG, "trace=$traceId $body$suffix")
        }
    }
}
