package com.personal.jarvis

object SpeechCommandSelector {
    const val SOURCE_FINAL = "final"
    const val SOURCE_FINAL_FAST_PARTIAL = "final_fast_partial"
    const val SOURCE_PARTIAL = "partial"

    data class Selection(
        val command: String,
        val source: String,
        val candidateIndex: Int,
        val text: String,
    )

    fun selectFinal(
        results: List<String>,
        requireWakeWord: Boolean = true,
    ): Selection? {
        if (results.isEmpty() || results.any(CommandInterpreter::isCommandNegated)) return null

        for ((index, candidate) in results.withIndex()) {
            val strictCommand = CommandInterpreter.parse(
                text = candidate,
                requireWakeWord = requireWakeWord,
            )
            if (strictCommand != null) {
                return Selection(
                    command = strictCommand,
                    source = SOURCE_FINAL,
                    candidateIndex = index + 1,
                    text = candidate,
                )
            }
        }

        for ((index, candidate) in results.withIndex()) {
            val fastCommand = fastPartialCommand(candidate, requireWakeWord) ?: continue
            return Selection(
                command = fastCommand,
                source = SOURCE_FINAL_FAST_PARTIAL,
                candidateIndex = index + 1,
                text = candidate,
            )
        }
        return null
    }

    fun selectPartial(
        results: List<String>,
        requireWakeWord: Boolean = true,
    ): Selection? {
        val candidate = results.firstOrNull() ?: return null
        val command = fastPartialCommand(candidate, requireWakeWord) ?: return null
        return Selection(
            command = command,
            source = SOURCE_PARTIAL,
            candidateIndex = 1,
            text = candidate,
        )
    }

    private fun fastPartialCommand(
        candidate: String,
        requireWakeWord: Boolean,
    ): String? {
        val command = CommandInterpreter.parseFastPartial(
            text = candidate,
            requireWakeWord = requireWakeWord,
        ) ?: return null

        return command.takeIf(CommandCatalog::supportsFastPartial)
    }
}
