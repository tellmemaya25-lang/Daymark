package com.sabihon.todo.core.dictation

/**
 * Recognizes the trailing spoken completion command in an Add/Edit title dictation.
 *
 * A standalone "done" at the end of the newly spoken text is a command, not part of the
 * task title. Matching only the appended speech means an existing title ending in "done"
 * does not trigger the command by itself.
 */
object VoiceTaskCommand {

    data class MarkDone(val title: String)

    /** Returns the cleaned title when the appended voice phrase ends with standalone "done". */
    fun parseMarkDone(baseTitle: String, renderedTitle: String): MarkDone? {
        val base = baseTitle.trimEnd()
        val spoken = if (base.isBlank()) {
            renderedTitle.trim()
        } else {
            renderedTitle.removePrefix(base).trim()
        }
        val command = DONE_SUFFIX.find(spoken) ?: return null
        val cleanSpoken = spoken.substring(0, command.range.first)
            .trimEnd(' ', '.', ',', ';', ':', '!', '?')
        val title = listOf(base, cleanSpoken)
            .filter(String::isNotBlank)
            .joinToString(" ")
        return MarkDone(title)
    }

    private val DONE_SUFFIX = Regex("(?:^|\\s)done[.!?,;:]*$", RegexOption.IGNORE_CASE)
}
