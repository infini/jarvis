package com.personal.jarvis

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionGenerationTest {
    @Test
    fun beginningNewSessionInvalidatesPreviousToken() {
        val generation = SessionGeneration()
        val previous = generation.begin()

        val current = generation.begin()

        assertFalse(generation.isCurrent(previous))
        assertTrue(generation.isCurrent(current))
    }

    @Test
    fun stopInvalidatesCurrentToken() {
        val generation = SessionGeneration()
        val token = generation.begin()

        generation.invalidate()

        assertFalse(generation.isCurrent(token))
        assertFalse(generation.tryComplete(token))
    }

    @Test
    fun onlyCurrentSessionCanCompleteOnce() {
        val generation = SessionGeneration()
        val stale = generation.begin()
        val current = generation.begin()

        assertFalse(generation.tryComplete(stale))
        assertTrue(generation.tryComplete(current))
        assertFalse(generation.tryComplete(current))
    }
}
