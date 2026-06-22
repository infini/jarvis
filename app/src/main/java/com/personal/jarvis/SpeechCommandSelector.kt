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
        return selectFastPartial(
            results = results,
            source = SOURCE_PARTIAL,
            requireWakeWord = requireWakeWord,
        )
    }

    private fun selectFastPartial(
        results: List<String>,
        source: String,
        requireWakeWord: Boolean,
    ): Selection? {
        for ((index, candidate) in results.withIndex()) {
            val command = fastPartialCommand(candidate, requireWakeWord) ?: continue

            return Selection(
                command = command,
                source = source,
                candidateIndex = index + 1,
                text = candidate,
            )
        }
        return null
    }

    private fun fastPartialCommand(
        candidate: String,
        requireWakeWord: Boolean,
    ): String? {
        val command = CommandInterpreter.parseFastPartial(
            text = candidate,
            requireWakeWord = requireWakeWord,
        ) ?: return null

        return command.takeIf { it in JarvisCommandExecutor.FAST_PARTIAL_COMMANDS }
    }
}
