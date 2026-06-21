package com.personal.jarvis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandInterpreterTest {
    @Test
    fun parsesFirstCameraControlGoalPhrases() {
        assertEquals(CommandBus.COMMAND_OPEN_CAMERA, CommandInterpreter.parse("자비스, 카메라 실행"))
        assertEquals(CommandBus.COMMAND_OPEN_FRONT_CAMERA, CommandInterpreter.parse("자비스, 셀피"))
        assertEquals(CommandBus.COMMAND_OPEN_FRONT_CAMERA, CommandInterpreter.parse("자비스, 전면"))
        assertEquals(CommandBus.COMMAND_OPEN_FRONT_CAMERA, CommandInterpreter.parse("자비스, 셀피 모드"))
        assertEquals(CommandBus.COMMAND_OPEN_REAR_CAMERA, CommandInterpreter.parse("자비스, 후면"))
        assertEquals(CommandBus.COMMAND_OPEN_REAR_CAMERA, CommandInterpreter.parse("자비스, 후면 모드"))
        assertEquals(CommandBus.COMMAND_OPEN_REAR_CAMERA, CommandInterpreter.parse("자비스, 후면으로 전환"))
        assertEquals(CommandBus.COMMAND_SWITCH_CAMERA, CommandInterpreter.parse("자비스, 카메라 전환"))
        assertEquals(CommandBus.COMMAND_SWITCH_CAMERA, CommandInterpreter.parse("자비스, 전후면 전환"))
        assertEquals(CommandBus.COMMAND_OPEN_CAMERA_AND_TAKE_PHOTO, CommandInterpreter.parse("자비스, 사진 찍기"))
        assertEquals(CommandBus.COMMAND_TAKE_PHOTO, CommandInterpreter.parse("자비스, 찍어"))
        assertEquals(CommandBus.COMMAND_HOME, CommandInterpreter.parse("자비스, 카메라 종료"))
        assertEquals(CommandBus.COMMAND_HOME, CommandInterpreter.parse("자비스, 종료"))
        assertEquals(CommandBus.COMMAND_WAKE_SCREEN, CommandInterpreter.parse("자비스, 화면 켜"))
        assertEquals(CommandBus.COMMAND_WAKE_SCREEN, CommandInterpreter.parse("자비스, 디스플레이 켜줘"))
        assertEquals(CommandBus.COMMAND_WAKE_SCREEN, CommandInterpreter.parse("자비스, 폰 깨워"))
        assertEquals(CommandBus.COMMAND_SLEEP_SCREEN, CommandInterpreter.parse("자비스, 화면 꺼"))
        assertEquals(CommandBus.COMMAND_SLEEP_SCREEN, CommandInterpreter.parse("자비스, 디스플레이 꺼줘"))
        assertEquals(CommandBus.COMMAND_SLEEP_SCREEN, CommandInterpreter.parse("자비스, 폰 잠가"))
    }

    @Test
    fun ignoresCommandsWithoutWakeWordByDefault() {
        assertNull(CommandInterpreter.parse("찍어"))
        assertEquals(CommandBus.COMMAND_TAKE_PHOTO, CommandInterpreter.parse("찍어", requireWakeWord = false))
        assertEquals(CommandBus.COMMAND_OPEN_FRONT_CAMERA, CommandInterpreter.parse("셀피", requireWakeWord = false))
        assertEquals(CommandBus.COMMAND_OPEN_FRONT_CAMERA, CommandInterpreter.parse("전면", requireWakeWord = false))
        assertEquals(CommandBus.COMMAND_OPEN_REAR_CAMERA, CommandInterpreter.parse("후면 모드", requireWakeWord = false))
        assertEquals(CommandBus.COMMAND_HOME, CommandInterpreter.parse("종료", requireWakeWord = false))
        assertEquals(CommandBus.COMMAND_HOME, CommandInterpreter.parse("전면 종료", requireWakeWord = false))
        assertEquals(CommandBus.COMMAND_WAKE_SCREEN, CommandInterpreter.parse("화면 켜", requireWakeWord = false))
        assertEquals(CommandBus.COMMAND_SLEEP_SCREEN, CommandInterpreter.parse("화면 꺼", requireWakeWord = false))
    }

    @Test
    fun activatesOnlyOnExplicitJarvisWakePhrase() {
        assertTrue(CommandInterpreter.isActivationWake("자비스 깨어나"))
        assertTrue(CommandInterpreter.isActivationWake("자베스 깨어나"))
        assertTrue(CommandInterpreter.isActivationWake("쟈비스 깨어나"))
        assertTrue(CommandInterpreter.isActivationWake("잡비스 깨어나."))
        assertTrue(CommandInterpreter.isActivationWake("잡스 깨어나."))
        assertFalse(CommandInterpreter.isActivationWake("헤이 자비스 깨어나"))
        assertFalse(CommandInterpreter.isActivationWake("자비스 실행"))
        assertFalse(CommandInterpreter.isActivationWake("자비스"))
        assertFalse(CommandInterpreter.isActivationWake("헤이 자비스"))
        assertFalse(CommandInterpreter.isActivationWake("자비스 찍어"))
        assertFalse(CommandInterpreter.isActivationWake("자비스 카메라 실행"))
        assertFalse(CommandInterpreter.isActivationWake("자비스 게임"))
        assertFalse(CommandInterpreter.isActivationWake("카메라 실행"))
        assertNull(CommandInterpreter.parse("자비스 깨어나"))
        assertNull(CommandInterpreter.parse("자비스, 깨어나."))
    }

    @Test
    fun acceptsLocalActivationAsrEquivalentWithoutChangingExplicitWakePhrase() {
        assertTrue(CommandInterpreter.isActivationWakeAsrEquivalent("자비스 깨어나"))
        assertTrue(CommandInterpreter.isActivationWakeAsrEquivalent("자비스게임?"))
        assertTrue(CommandInterpreter.isActivationWakeAsrEquivalent("잡비스 게임"))
        assertTrue(CommandInterpreter.isActivationWakeAsrEquivalent("다비스때어나"))
        assertTrue(CommandInterpreter.isActivationWakeAsrEquivalent("아에스에어나"))
        assertFalse(CommandInterpreter.isActivationWake("자비스게임?"))
        assertFalse(CommandInterpreter.isActivationWake("다비스 때어나"))
        assertFalse(CommandInterpreter.isActivationWakeAsrEquivalent("자비스 실행"))
        assertFalse(CommandInterpreter.isActivationWakeAsrEquivalent("자비스"))
        assertFalse(CommandInterpreter.isActivationWakeAsrEquivalent("헤이 자비스"))
        assertFalse(CommandInterpreter.isActivationWakeAsrEquivalent("헤이 자비스 깨어나"))
        assertFalse(CommandInterpreter.isActivationWakeAsrEquivalent("헤이 자비스 게임"))
        assertFalse(CommandInterpreter.isActivationWakeAsrEquivalent("자비스 카메라 실행"))
        assertFalse(CommandInterpreter.isActivationWakeAsrEquivalent("자비스 깨어나 카메라 실행"))
        assertFalse(CommandInterpreter.isActivationWakeAsrEquivalent("카메라 실행"))
        assertNull(CommandInterpreter.parse("다비스 카메라 실행"))
    }

    @Test
    fun detectsRepeatedWakePhraseForOwnerEnrollmentOnly() {
        assertTrue(CommandInterpreter.containsActivationWake("잡스 깨어나 자비스 깨어나 자베스 깨어나"))
        assertTrue(CommandInterpreter.containsActivationWake("잡비스깨어나."))
        assertTrue(CommandInterpreter.containsActivationWake("자비스게임?"))
        assertTrue(CommandInterpreter.containsActivationWake("다비스때어나"))
        assertTrue(CommandInterpreter.containsActivationWake("자비스깨어나자베스깨어나"))
        assertFalse(CommandInterpreter.isActivationWake("자비스깨어나자베스깨어나"))
        assertFalse(CommandInterpreter.containsActivationWake("자비스 실행"))
    }

    @Test
    fun appNavigationCommandsKeepCommandWindowOpen() {
        assertTrue(JarvisCommandExecutor.shouldKeepCommandWindowOpen(CommandBus.COMMAND_OPEN_CAMERA))
        assertTrue(JarvisCommandExecutor.shouldKeepCommandWindowOpen(CommandBus.COMMAND_OPEN_FRONT_CAMERA))
        assertTrue(JarvisCommandExecutor.shouldKeepCommandWindowOpen(CommandBus.COMMAND_OPEN_REAR_CAMERA))
        assertTrue(JarvisCommandExecutor.shouldKeepCommandWindowOpen(CommandBus.COMMAND_TAKE_PHOTO))
        assertTrue(JarvisCommandExecutor.shouldKeepCommandWindowOpen(CommandBus.COMMAND_HOME))
        assertTrue(JarvisCommandExecutor.shouldKeepCommandWindowOpen(CommandBus.COMMAND_BACK))
        assertFalse(JarvisCommandExecutor.shouldKeepCommandWindowOpen(CommandBus.COMMAND_STOP_LISTENING))
    }

    @Test
    fun stopListeningClosesOnlyCurrentCommandWindow() {
        assertEquals(CommandBus.COMMAND_STOP_LISTENING, CommandInterpreter.parse("자비스, 잠들어"))
        assertEquals(CommandBus.COMMAND_STOP_LISTENING, CommandInterpreter.parse("잠들어", requireWakeWord = false))
        assertEquals(CommandBus.COMMAND_STOP_LISTENING, CommandInterpreter.parse("자비스, 멈춰"))
        assertEquals(CommandBus.COMMAND_STOP_LISTENING, CommandInterpreter.parse("멈춰", requireWakeWord = false))
        assertFalse(JarvisCommandExecutor.shouldStopVoiceService(CommandBus.COMMAND_STOP_LISTENING))
    }

    @Test
    fun latencySensitiveSystemCommandsUseFastPartialPath() {
        assertTrue(CommandBus.COMMAND_WAKE_SCREEN in JarvisCommandExecutor.FAST_PARTIAL_COMMANDS)
        assertTrue(CommandBus.COMMAND_SLEEP_SCREEN in JarvisCommandExecutor.FAST_PARTIAL_COMMANDS)
        assertTrue(CommandBus.COMMAND_STOP_LISTENING in JarvisCommandExecutor.FAST_PARTIAL_COMMANDS)
    }
}
