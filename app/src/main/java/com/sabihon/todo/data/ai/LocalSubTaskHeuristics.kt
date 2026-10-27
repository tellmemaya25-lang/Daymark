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

    /**
     * Richer local step breakdown used by the interactive UI when Cloud AI is switched
     * off, offline, or cooling down.
     *
     * Tries explicit separator splitting first, then extracts bullet/line items from
     * [description], and finally offers domain-aware concrete checklist steps grounded
     * in the task's keywords so tapping "Suggest steps" always produces actionable chips.
     */
    fun suggestFallbackSteps(
        title: String,
        description: String? = null,
        existingSubTasks: List<String> = emptyList()
    ): List<String> {
        val directSplit = split(title, existingSubTasks)
        if (directSplit.isNotEmpty()) return directSplit

        val seen = existingSubTasks.mapTo(mutableSetOf()) { it.normalized() }
        val cleanTitle = title.trim().trimEnd('.', '!', '?')
        if (cleanTitle.isEmpty()) return emptyList()
        seen.add(cleanTitle.normalized())

        // Extract explicit lines or bullets from the task description if present.
        if (!description.isNullOrBlank()) {
            val fromNotes = description
                .split(Regex("""[\r\n]+|;\s+"""))
                .map { line ->
                    line.trim()
                        .replace(LEADING_BULLET, "")
                        .trim()
                }
                .filter { it.length in MIN_PART_CHARS..MAX_STEP_CHARS }
                .filter { seen.add(it.normalized()) }
                .take(MAX_STEPS)
            if (fromNotes.size >= 2) return fromNotes
        }

        val lower = cleanTitle.lowercase()
        val domainSteps = when {
            lower.containsAny("call", "phone", "dentist", "doctor", "clinic", "appointment", "book", "schedule") -> listOf(
                "Find contact number or booking link",
                "Check open times on calendar",
                "Confirm appointment details and set reminder"
            )
            lower.containsAny("buy", "shop", "grocer", "market", "order", "pick up", "errand", "store") -> listOf(
                "List exact items and quantities needed",
                "Check what is already at home",
                "Pick up items and put them away"
            )
            lower.containsAny("study", "exam", "quiz", "homework", "assignment", "lecture", "chapter", "learn", "read") -> listOf(
                "Gather notes and key topics to cover",
                "Do a focused 25-minute review block",
                "Practice key questions and note weak spots"
            )
            lower.containsAny("bug", "fix", "code", "implement", "refactor", "build", "deploy", "test", "api", "app", "pr") -> listOf(
                "Reproduce issue and check relevant files",
                "Implement the core change cleanly",
                "Test edge cases and verify build"
            )
            lower.containsAny("write", "draft", "report", "slides", "deck", "presentation", "proposal", "email", "essay", "doc") -> listOf(
                "Outline the key points and structure",
                "Write the first complete draft",
                "Proofread and send or submit"
            )
            lower.containsAny("pack", "trip", "travel", "flight", "hotel", "vacation", "airport") -> listOf(
                "Confirm tickets, bookings, and itinerary",
                "Pack clothes, chargers, and essentials",
                "Check ID/documents and departure time"
            )
            lower.containsAny("clean", "tidy", "wash", "laundry", "organize", "kitchen", "room", "house", "declutter") -> listOf(
                "Clear clutter and gather cleaning supplies",
                "Clean the main surfaces and floor",
                "Put everything back in its place"
            )
            lower.containsAny("pay", "bill", "invoice", "tax", "budget", "bank", "rent", "expense") -> listOf(
                "Verify amount due and payment details",
                "Submit payment before due date",
                "Save receipt or confirmation"
            )
            lower.containsAny("dinner", "lunch", "breakfast", "cook", "meal", "recipe", "bake", "prep food") -> listOf(
                "Check recipe and ingredients on hand",
                "Prep and chop ingredients",
                "Cook main dish and tidy kitchen"
            )
            lower.containsAny("webinar", "meeting", "workshop", "seminar", "conference", "zoom", "interview", "standup") -> listOf(
                "Confirm meeting link, time, and audio setup",
                "Review agenda or talking points beforehand",
                "Capture key takeaways and follow-up actions"
            )
            lower.containsAny("thesis", "dissertation", "research", "paper", "capstone", "manuscript", "literature") -> listOf(
                "Gather key references and outline section goals",
                "Draft the next focused section (45 min block)",
                "Review citations, notes, and advisor feedback"
            )
            lower.containsAny("workout", "gym", "run", "walk", "exercise", "yoga", "stretch", "train") -> listOf(
                "Set out workout gear and water",
                "Warm up for 5 minutes",
                "Complete main session and cool down"
            )
            else -> {
                val baseSubject = cleanTitle
                    .replace(Regex("""^(?:follow up on|plan the next step for|gather details needed for)\s+""", RegexOption.IGNORE_CASE), "")
                    .trim()
                    .ifEmpty { cleanTitle }
                    .take(36)
                    .trim()
                listOf(
                    "Outline what's needed for $baseSubject",
                    "Complete the main work block",
                    "Review and wrap up $baseSubject"
                )
            }
        }

        return domainSteps
            .map { it.take(MAX_STEP_CHARS) }
            .filter { seen.add(it.normalized()) }
            .take(MAX_STEPS)
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { this.contains(it) }

    private fun String.normalized(): String = trim().lowercase().replace(WHITESPACE, " ")

    private companion object {
        /** Comma, semicolon, and the conjunctions that join two real actions. */
        val SEPARATORS = Regex(
            """\s*,\s*|\s*;\s*|\s+and\s+|\s+then\s+|\s*&\s*|\s+\+\s+""",
            RegexOption.IGNORE_CASE
        )
        val LEADING_BULLET = Regex("""^(?:[-*•]+|\d+[.)])\s*""")
        val WHITESPACE = Regex("""\s+""")
        const val MIN_PART_CHARS = 4
        const val MAX_STEP_CHARS = 80
        const val MAX_STEPS = 6
    }
}
