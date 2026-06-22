package com.personal.jarvis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandCatalogTest {
    @Test
    fun documentsEveryCommandBusCommand() {
        val documentedCommands = CommandCatalog.entries.map { it.commandId }.toSet()
        val expectedCommands = setOf(
            CommandBus.COMMAND_OPEN_CAMERA,
            CommandBus.COMMAND_OPEN_FRONT_CAMERA,
            CommandBus.COMMAND_OPEN_REAR_CAMERA,
            CommandBus.COMMAND_OPEN_CAMERA_AND_TAKE_PHOTO,
            CommandBus.COMMAND_TAKE_PHOTO,
            CommandBus.COMMAND_OPEN_FILTERS,
            CommandBus.COMMAND_SWITCH_CAMERA,
            CommandBus.COMMAND_BACK,
            CommandBus.COMMAND_HOME,
            CommandBus.COMMAND_WAKE_SCREEN,
            CommandBus.COMMAND_SLEEP_SCREEN,
            CommandBus.COMMAND_STOP_LISTENING,
            CommandBus.COMMAND_STOP_SERVICE,
        )

        assertEquals(expectedCommands, documentedCommands)
        assertEquals(CommandCatalog.entries.size, documentedCommands.size)
    }

    @Test
    fun catalogPrimaryPhrasesMatchInterpreter() {
        CommandCatalog.entries.forEach { entry ->
            assertEquals(
                expected = entry.commandId,
                actual = CommandInterpreter.parse(entry.phrases.first()),
                message = entry.title,
            )
        }
    }

    @Test
    fun catalogAllPhrasesMatchInterpreter() {
        CommandCatalog.entries.forEach { entry ->
            entry.phrases.forEach { phrase ->
                assertEquals(
                    expected = entry.commandId,
                    actual = CommandInterpreter.parse(phrase),
                    message = phrase,
                )
            }
        }
    }

    @Test
    fun catalogHasDetailedUserFacingContent() {
        CommandCatalog.entries.forEach { entry ->
            assertTrue(entry.title.isNotBlank())
            assertTrue(entry.phrases.isNotEmpty())
            assertTrue(entry.summary.isNotBlank())
            assertTrue(entry.detail.isNotBlank())
            assertTrue(entry.requirements.isNotEmpty())
        }
    }
}
