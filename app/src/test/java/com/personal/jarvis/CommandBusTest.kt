package com.personal.jarvis

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandBusTest {
    @Test
    fun tracksDirectReceiverRegistration() {
        var cancelled = false
        val receiver = object : CommandBus.DirectReceiver {
            override fun cancelPendingCommands() {
                cancelled = true
            }

            override fun onDirectCommand(
                command: String,
                source: String,
                traceId: Long?,
                traceStartedAtMs: Long,
                sentAtMs: Long,
                onCompleted: (Boolean) -> Unit,
            ) {
                onCompleted(false)
            }
        }

        assertFalse(CommandBus.hasDirectReceiver())
        try {
            CommandBus.registerDirectReceiver(receiver)
            assertTrue(CommandBus.hasDirectReceiver())
            CommandBus.cancelPending()
            assertTrue(cancelled)
        } finally {
            CommandBus.unregisterDirectReceiver(receiver)
        }
        assertFalse(CommandBus.hasDirectReceiver())
    }
}
