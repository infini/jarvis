package com.personal.jarvis

enum class JarvisVoiceState {
    WAKE_WAITING,
    OWNER_VERIFYING,
    COMMAND_READY,
    COMMAND_PROCESSING,
    COMMAND_HANDLED,
    COMMAND_FAILED,
    IDLE,
}
