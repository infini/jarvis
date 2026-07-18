package com.personal.jarvis

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandExecutionTrackerTest {
    @Test
    fun suppressesCommandWhileAnotherDispatchIsInFlight() {
        val tracker = CommandExecutionTracker { 100L }
        assertNotNull(tracker.reserve("take_photo", 500L))

        assertNull(tracker.reserve("take_photo", 500L))
        assertTrue(tracker.isActive("take_photo"))
    }

    @Test
    fun failedDispatchCanBeRetriedImmediately() {
        val tracker = CommandExecutionTracker { 100L }
        val first = assertNotNull(tracker.reserve("take_photo", 500L))
        tracker.complete(first, succeeded = false)

        assertNotNull(tracker.reserve("take_photo", 500L))
    }

    @Test
    fun successfulDispatchStartsCooldownAtCompletion() {
        var now = 100L
        val tracker = CommandExecutionTracker { now }
        val first = assertNotNull(tracker.reserve("take_photo", 500L))
        now = 300L
        tracker.complete(first, succeeded = true)

        now = 799L
        assertNull(tracker.reserve("take_photo", 500L))
        now = 800L
        assertNotNull(tracker.reserve("take_photo", 500L))
    }

    @Test
    fun staleCompletionCannotClearOrCoolDownReplacement() {
        var now = 100L
        val tracker = CommandExecutionTracker { now }
        val stale = assertNotNull(tracker.reserve("take_photo", 500L))
        tracker.cancelActive()
        val replacement = assertNotNull(tracker.reserve("take_photo", 500L))

        now = 200L
        tracker.complete(stale, succeeded = true)
        assertTrue(tracker.isActive("take_photo"))
        tracker.complete(replacement, succeeded = false)
        assertFalse(tracker.hasActiveCommand())
        assertNotNull(tracker.reserve("take_photo", 500L))
    }
}
