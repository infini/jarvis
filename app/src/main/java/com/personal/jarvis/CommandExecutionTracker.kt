package com.personal.jarvis

internal class CommandExecutionTracker(
    private val nowMs: () -> Long,
) {
    class Reservation internal constructor(
        val id: Long,
        val command: String,
    )

    private var nextId = 0L
    private var activeReservation: Reservation? = null
    private var lastSuccessfulCommand: String? = null
    private var lastSuccessfulAtMs = 0L

    @Synchronized
    fun reserve(command: String, cooldownMs: Long): Reservation? {
        if (activeReservation != null) return null
        val now = nowMs()
        if (lastSuccessfulCommand == command && now - lastSuccessfulAtMs < cooldownMs) return null

        return Reservation(++nextId, command).also { activeReservation = it }
    }

    @Synchronized
    fun complete(reservation: Reservation, succeeded: Boolean) {
        if (activeReservation != reservation) return
        activeReservation = null
        if (succeeded) {
            lastSuccessfulCommand = reservation.command
            lastSuccessfulAtMs = nowMs()
        }
    }

    @Synchronized
    fun cancelActive() {
        activeReservation = null
    }

    @Synchronized
    fun hasActiveCommand(): Boolean = activeReservation != null

    @Synchronized
    fun isActive(command: String): Boolean = activeReservation?.command == command
}
