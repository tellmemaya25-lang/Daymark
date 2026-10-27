package com.sabihon.todo.data.ai

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline step splitting (`docs/ai-task-prioritization-and-suggestions-plan.md` Phase 3).
 *
 * Deliberately narrow. It does only the one thing that can be done without understanding
 * the sentence: when the title already names several steps - "pack, print the tickets and
 * charge the camera" - those steps are already in the text, so it hands them back. It
 * never invents one.
 *
 * The alternative was a template engine answering every task with "Plan it", "Do it",
 * "Review it". That is worse than nothing: those steps are true of every task, so they
 * say nothing, and they bury the ones that would have been real. When this class has
 * nothing honest to say it returns an empty list and the UI says so in words.
 */
@Singleton
class LocalSubTaskHeuristics @Inject constructor() {

    /**
     * Splits [title] on the separators people actually type and keeps the result only
     * when the split is credible.
     */
    fun split(title: String, existingSubTasks: List<String> = emptyList()): List<String> {
        val cleaned = title.trim().trimEnd('.', '!', '?')
        if (cleaned.isEmpty()) return emptyList()

        // A bare "and" joins two objects as often as it joins two actions: "buy milk and
        // bread" is one errand, and splitting it would offer "bread" as a step. Commas
        // are the reliable signal that the writer was listing things to do, so a split
        // needs either a comma or three or more parts before it is trusted.
        val hasComma = cleaned.contains(',') || cleaned.contains(';')
        val parts = cleaned
            .split(SEPARATORS)
            .map { it.trim().trim(',', ';', ':', '.') }
            .filter { it.isNotEmpty() }

        if (parts.size < 2) return emptyList()
        if (!hasComma && parts.size < 3) return emptyList()
        if (parts.any { it.length < MIN_PART_CHARS }) return emptyList()

        val seen = existingSubTasks.mapTo(mutableSetOf()) { it.normalized() }
        return parts
            .filter { it.length <= MAX_STEP_CHARS }
            .filter { seen.add(it.normalized()) }
            .take(MAX_STEPS)
    }

    private fun String.normalized(): String = trim().lowercase().replace(WHITESPACE, " ")

    private companion object {
        /** Comma, semicolon, and the conjunctions that join two real actions. */
        val SEPARATORS = Regex(
            """\s*,\s*|\s*;\s*|\s+and\s+|\s+then\s+|\s*&\s*|\s+\+\s+""",
            RegexOption.IGNORE_CASE
        )
        val WHITESPACE = Regex("""\s+""")
        const val MIN_PART_CHARS = 4
        const val MAX_STEP_CHARS = 80
        const val MAX_STEPS = 6
    }
}
