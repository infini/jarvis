package com.personal.jarvis

import java.util.concurrent.atomic.AtomicLong

internal class SessionGeneration {
    private val epoch = AtomicLong(0L)

    fun begin(): Long = epoch.incrementAndGet()

    fun isCurrent(token: Long): Boolean = epoch.get() == token

    fun tryComplete(token: Long): Boolean = epoch.compareAndSet(token, token + 1L)

    fun invalidate() {
        epoch.incrementAndGet()
    }
}
