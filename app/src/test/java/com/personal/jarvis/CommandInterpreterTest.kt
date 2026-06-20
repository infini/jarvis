package com.personal.jarvis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommandInterpreterTest {
    @Test
    fun parsesFirstCameraControlGoalPhrases() {
        assertEquals(CommandBus.COMMAND_OPEN_CAMERA, CommandInterpreter.parse("자비스, 카메라 실행"))
        assertEquals(CommandBus.COMMAND_OPEN_FRONT_CAMERA, CommandInterpreter.parse("자비스, 셀피 모드"))
        assertEquals(CommandBus.COMMAND_OPEN_REAR_CAMERA, CommandInterpreter.parse("자비스, 후면 모드"))
        assertEquals(CommandBus.COMMAND_OPEN_REAR_CAMERA, CommandInterpreter.parse("자비스, 후면으로 전환"))
        assertEquals(CommandBus.COMMAND_SWITCH_CAMERA, CommandInterpreter.parse("자비스, 카메라 전환"))
        assertEquals(CommandBus.COMMAND_SWITCH_CAMERA, CommandInterpreter.parse("자비스, 전후면 전환"))
        assertEquals(CommandBus.COMMAND_OPEN_CAMERA_AND_TAKE_PHOTO, CommandInterpreter.parse("자비스, 사진 찍기"))
        assertEquals(CommandBus.COMMAND_TAKE_PHOTO, CommandInterpreter.parse("자비스, 찍어"))
        assertEquals(CommandBus.COMMAND_HOME, CommandInterpreter.parse("자비스, 카메라 종료"))
    }

    @Test
    fun ignoresCommandsWithoutWakeWordByDefault() {
        assertNull(CommandInterpreter.parse("찍어"))
        assertEquals(CommandBus.COMMAND_TAKE_PHOTO, CommandInterpreter.parse("찍어", requireWakeWord = false))
    }
}
