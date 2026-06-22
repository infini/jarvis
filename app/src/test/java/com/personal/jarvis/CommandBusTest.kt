package com.personal.jarvis

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandBusTest {
    @Test
    fun tracksDirectReceiverRegistration() {
        val receiver = object : CommandBus.DirectReceiver {
            override fun onDirectCommand(
                command: String,
                source: String,
                traceId: Long?,
                traceStartedAtMs: Long,
                sentAtMs: Long,
            ): Boolean = false
        }

        assertFalse(CommandBus.hasDirectReceiver())
        try {
            CommandBus.registerDirectReceiver(receiver)
            assertTrue(CommandBus.hasDirectReceiver())
        } finally {
            CommandBus.unregisterDirectReceiver(receiver)
        }
        assertFalse(CommandBus.hasDirectReceiver())
    }
}
