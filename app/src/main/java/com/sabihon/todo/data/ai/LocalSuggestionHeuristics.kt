package com.sabihon.todo.data.ai

import com.sabihon.todo.domain.ai.SuggestedTaskCandidate
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.RepeatRule
import com.sabihon.todo.domain.model.Task
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Suggestions without the network (`docs/ai-task-prioritization-and-suggestions-plan.md`
 * Phase 4).
 *
 * Unlike the step splitter, this layer *does* invent tasks - that is the whole point of
 * a "what next?" card. So every rule here is grounded in something already in the
 * database rather than in a guess about the user's life, and each one is narrow enough
 * to state in a sentence:
 *
 *  - **Slipped:** a task that was due more than two days ago and is still open. The
 *    suggestion is not "do it now" - it is "decide about it", because work this stale
 *    needs a new date more than another reminder.
 *  - **Streak:** a habit that has been going for at least three days and is not open
 *    today. Breaking a streak costs more than a day's work, and the app already knows
 *    the number.
 *  - **Neglected list:** a project where the user has finished at least three things
 *    and has nothing open. Something is clearly happening there; the next step is worth
 *    naming.
 *
 * Ordered by how much the user loses by ignoring them: streak first, then the list they
 * are actively working, then the thing that slipped.
 */
@Singleton
class LocalSuggestionHeuristics @Inject constructor() {

    fun suggest(
        openTasks: List<Task>,
        recentCompleted: List<Task>,
        categories: List<Category>,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        limit: Int = MAX_SUGGESTIONS
    ): List<SuggestedTaskCandidate> {
        val open = openTasks.filter { !it.isCompleted && !it.isDeleted }
        val openTitles = open.mapTo(mutableSetOf()) { it.title.normalized() }

        val candidates = mutableListOf<SuggestedTaskCandidate>()

        streakSuggestions(open, today).forEach { candidates += it }
        neglectedListSuggestions(open, recentCompleted, categories).forEach { candidates += it }
        slippedSuggestions(open, today, zone).forEach { candidates += it }

        return candidates
            .filter { it.title.normalized() !in openTitles }
            .distinctBy { it.suggestionId }
            .take(limit)
    }

    /**
     * A habit with a streak worth protecting that is not on today's list. Repeating
     * tasks that are already open today are skipped by the caller's title filter, so
     * this only fires for a habit the user has not been shown yet.
     */
    private fun streakSuggestions(open: List<Task>, today: LocalDate): List<SuggestedTaskCandidate> =
        open.asSequence()
            .filter { it.repeat != RepeatRule.NONE && it.streak >= MIN_STREAK }
            .filter { it.lastCompletedDay != today.toEpochDay() }
            .sortedByDescending { it.streak }
            .map { habit ->
                val title = "Do ${habit.title.trim()} today"
                SuggestedTaskCandidate(
                    suggestionId = SuggestedTaskCandidate.idFor(title, habit.categoryId),
                    title = title.take(MAX_TITLE_CHARS),
                    categoryId = habit.categoryId,
                    priority = Priority.HIGH,
                    dueOffsetHours = EVENING_OFFSET_HOURS,
                    reason = "${habit.streak}-day streak - don't break it"
                )
            }
            .toList()

    /**
     * A project with real recent activity and nothing planned. Counted from the recent
     * completions rather than all history, so the suggestion tracks what the user is
     * working on this week, not what they finished in March.
     */
    private fun neglectedListSuggestions(
        open: List<Task>,
        recentCompleted: List<Task>,
        categories: List<Category>
    ): List<SuggestedTaskCandidate> {
        if (categories.isEmpty()) return emptyList()

        val openPerCategory = open.mapNotNull { it.categoryId }
            .groupingBy { it }
            .eachCount()
        val donePerCategory = recentCompleted
            .filter { it.isCompleted && !it.isDeleted }
            .mapNotNull { it.categoryId }
            .groupingBy { it }
            .eachCount()

        return categories.asSequence()
            // Only lists with real recent activity: an unused list is not neglected, it
            // is simply not interesting yet. One filter, not two - the first draft had
            // both and the looser one made the stricter one dead code.
            .filter { (donePerCategory[it.id] ?: 0) >= MIN_COMPLETIONS }
            .filter { (openPerCategory[it.id] ?: 0) == 0 }
            .map { category ->
                val finished = donePerCategory[category.id] ?: 0
                val title = "Plan the next step for ${category.name}"
                SuggestedTaskCandidate(
                    suggestionId = SuggestedTaskCandidate.idFor(title, category.id),
                    title = title.take(MAX_TITLE_CHARS),
                    categoryId = category.id,
                    priority = Priority.MEDIUM,
                    dueOffsetHours = null,
                    reason = "$finished finished, nothing planned"
                )
            }
            .toList()
    }

    /** Open work that is stale enough that a new date is the real next action. */
    private fun slippedSuggestions(
        open: List<Task>,
        today: LocalDate,
        zone: ZoneId
    ): List<SuggestedTaskCandidate> =
        open.asSequence()
            .filter { it.dueAt != null }
            .mapNotNull { task -> task to daysOverdue(task.dueAt!!, today, zone) }
            .filter { (_, days) -> days >= MIN_DAYS_OVERDUE }
            .sortedByDescending { (_, days) -> days }
            .map { (task, days) ->
                val title = "Reschedule ${task.title.trim()}"
                SuggestedTaskCandidate(
                    suggestionId = SuggestedTaskCandidate.idFor(title, task.categoryId),
                    title = title.take(MAX_TITLE_CHARS),
                    categoryId = task.categoryId,
                    // Not HIGH: the point of this suggestion is to make a decision, and
                    // marking it urgent is how you get the same stale task shouted about
                    // for another week.
                    priority = Priority.MEDIUM,
                    dueOffsetHours = TODAY_OFFSET_HOURS,
                    reason = "Overdue by $days ${if (days == 1L) "day" else "days"} - pick a new date"
                )
            }
            .toList()

    private fun daysOverdue(dueAt: Long, today: LocalDate, zone: ZoneId): Long {
        val dueDate = Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate()
        return today.toEpochDay() - dueDate.toEpochDay()
    }

    private fun String.normalized(): String = trim().lowercase()

    companion object {
        const val MAX_SUGGESTIONS = 3
        const val MIN_STREAK = 3
        const val MIN_COMPLETIONS = 3
        const val MIN_DAYS_OVERDUE = 2L

        private const val MAX_TITLE_CHARS = 60
        private const val EVENING_OFFSET_HOURS = 8
        private const val TODAY_OFFSET_HOURS = 2
    }
}
