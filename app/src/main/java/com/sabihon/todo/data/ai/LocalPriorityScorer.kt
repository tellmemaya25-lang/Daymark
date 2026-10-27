package com.sabihon.todo.data.ai

import com.sabihon.todo.domain.ai.AiModelTier
import com.sabihon.todo.domain.ai.AiSource
import com.sabihon.todo.domain.ai.PrioritizationPlan
import com.sabihon.todo.domain.ai.TaskPriorityRecommendation
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.RepeatRule
import com.sabihon.todo.domain.model.Task
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rung 0 of the intelligence ladder (`docs/02-daymark-ai-system-design.md` §7):
 * pure, clock-injected, deterministic task scoring (`0` tokens, `< 2 ms`, 100% offline).
 *
 * Everyopen task `t` receives `S(t) = U(t) + P(t) + H(t) + M(t) + A(t)`:
 *  - `U(t)` Deadline urgency (overdue -> due within 3h -> today -> tomorrow -> week -> undated -> distant)
 *  - `P(t)` Explicit priority weight (`URGENT` .. `LOW`)
 *  - `H(t)` Habit streak protection when a recurring task is due today or overdue
 *  - `M(t)` Quick-win momentum for checklists already underway
 *  - `A(t)` Anti-starvation age boost for long-standing undated tasks
 */
@Singleton
class LocalPriorityScorer @Inject constructor() {

    fun score(
        task: Task,
        nowMillis: Long = System.currentTimeMillis(),
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Int {
        val u = urgencyPoints(task, nowMillis, today, zone)
        val p = priorityPoints(task.priority)
        val h = habitStreakPoints(task, today, zone)
        val m = momentumPoints(task)
        val a = agePoints(task, nowMillis)
        return u + p + h + m + a
    }

    /**
     * Orders open tasks from highest focus (#1) to lowest.
     *
     * When [aiFocusRanks] contains validated ranks for tasks in the current snapshot,
     * those ranks lead and [score] breaks ties and places newly added unranked tasks.
     */
    fun rankTasks(
        openTasks: List<Task>,
        nowMillis: Long = System.currentTimeMillis(),
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        aiFocusRanks: Map<String, Int> = emptyMap()
    ): List<Task> {
        if (openTasks.size <= 1) return openTasks
        val scores = openTasks.associate { it.id to score(it, nowMillis, today, zone) }
        return openTasks.sortedWith(
            Comparator { left, right ->
                val leftAi = aiFocusRanks[left.id]
                val rightAi = aiFocusRanks[right.id]
                if (leftAi != null && rightAi != null && leftAi != rightAi) {
                    return@Comparator leftAi.compareTo(rightAi)
                }
                val scoreDiff = (scores[right.id] ?: 0).compareTo(scores[left.id] ?: 0)
                if (scoreDiff != 0) return@Comparator scoreDiff

                val leftDue = left.dueAt
                val rightDue = right.dueAt
                when {
                    leftDue != null && rightDue != null && leftDue != rightDue ->
                        leftDue.compareTo(rightDue)
                    leftDue != null && rightDue == null -> -1
                    leftDue == null && rightDue != null -> 1
                    else -> left.createdAt.compareTo(right.createdAt)
                }
            }
        )
    }

    /**
     * Full deterministic fallback plan used at `t = 0 ms` before cloud refinement and
     * whenever the device is offline, Cloud AI is switched off, or quota is exhausted.
     */
    fun buildLocalPlan(
        openTasks: List<Task>,
        nowMillis: Long = System.currentTimeMillis(),
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        collisionWarning: String? = null
    ): PrioritizationPlan {
        val active = openTasks.filter { !it.isCompleted && !it.isDeleted }
        if (active.isEmpty()) {
            return PrioritizationPlan(
                recommendations = emptyList(),
                summaryHeadline = "Nothing open to prioritize right now.",
                source = AiSource.LOCAL_FALLBACK,
                modelTier = AiModelTier.LOCAL,
                generatedAtMillis = nowMillis
            )
        }

        val ranked = rankTasks(active, nowMillis, today, zone)
        val topFocusLimit = minOf(MAX_TOP_FOCUS, ranked.size)

        val recommendations = ranked.mapIndexed { index, task ->
            val taskScore = score(task, nowMillis, today, zone)
            TaskPriorityRecommendation(
                taskId = task.id,
                currentPriority = task.priority,
                suggestedPriority = suggestOfflinePriority(task, taskScore, nowMillis, today, zone),
                focusRank = index + 1,
                isTopFocus = index < topFocusLimit,
                reason = explainOffline(task, nowMillis, today, zone),
                localScore = taskScore
            )
        }

        val startOfToday = startOfDay(today, zone)
        val overdueCount = active.count { it.dueAt != null && it.dueAt < minOf(nowMillis, startOfToday) }
        val streakCount = active.count { isHabitDueOrOverdue(it, today, zone) && it.streak >= 2 }

        val headline = collisionWarning ?: when {
            overdueCount > 0 && streakCount > 0 ->
                "$overdueCount overdue ${if (overdueCount == 1) "task" else "tasks"} and an active streak need attention first."
            overdueCount > 0 ->
                "$overdueCount overdue ${if (overdueCount == 1) "task" else "tasks"} moved to the top of your focus."
            streakCount > 0 ->
                "Protecting your active habit ${if (streakCount == 1) "streak" else "streaks"} alongside today's deadlines."
            else ->
                "Ranked by deadline proximity, priority, and checklist momentum."
        }

        return PrioritizationPlan(
            recommendations = recommendations,
            summaryHeadline = headline,
            source = AiSource.LOCAL_FALLBACK,
            modelTier = AiModelTier.LOCAL,
            generatedAtMillis = nowMillis
        )
    }

    /**
     * Conservative offline priority calibration: upgrades tasks whose deadline or streak
     * urgency outstrips a low/medium label, without blindly downgrading user-set priorities
     * whose semantic weight only a person or language model can judge.
     */
    private fun suggestOfflinePriority(
        task: Task,
        totalScore: Int,
        nowMillis: Long,
        today: LocalDate,
        zone: ZoneId
    ): Priority {
        val due = task.dueAt
        val startOfToday = startOfDay(today, zone)
        val endOfToday = endOfDay(today, zone)
        val isOverdue = due != null && (due < startOfToday || due < nowMillis)
        val isDueSoon = due != null && due in nowMillis..(nowMillis + THREE_HOURS_MS)
        val isDueToday = due != null && due in startOfToday..endOfToday

        val floor = when {
            isOverdue && totalScore >= 72 -> Priority.URGENT
            isOverdue || isDueSoon -> Priority.HIGH
            isDueToday && task.repeat != RepeatRule.NONE && task.streak >= 3 -> Priority.HIGH
            isDueToday && task.priority == Priority.LOW -> Priority.MEDIUM
            else -> task.priority
        }
        return if (floor.ordinal > task.priority.ordinal) floor else task.priority
    }

    fun explainOffline(
        task: Task,
        nowMillis: Long = System.currentTimeMillis(),
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val parts = mutableListOf<String>()
        val due = task.dueAt
        val startOfToday = startOfDay(today, zone)
        val endOfToday = endOfDay(today, zone)
        val endOfTomorrow = endOfDay(today.plusDays(1), zone)

        when {
            due != null && due < startOfToday -> {
                val daysLate = ((startOfToday - due) / DAY_MS).toInt() + 1
                parts += if (daysLate <= 1) "Overdue since yesterday" else "Overdue by ${daysLate}d"
            }
            due != null && due < nowMillis -> parts += "Past due today"
            due != null && due <= nowMillis + THREE_HOURS_MS -> parts += "Due within 3 hours"
            due != null && due in startOfToday..endOfToday -> parts += "Due today"
            due != null && due <= endOfTomorrow -> parts += "Due tomorrow"
        }

        if (task.repeat != RepeatRule.NONE && task.streak >= 2 && isHabitDueOrOverdue(task, today, zone)) {
            parts += "${task.streak}-day streak at risk"
        }

        val totalSubs = task.subTasks.size
        val doneSubs = task.subTasks.count { it.isDone }
        if (totalSubs > 0 && doneSubs > 0 && doneSubs < totalSubs) {
            parts += "$doneSubs/$totalSubs subtasks done"
        }

        if (parts.size < 2) {
            when (task.priority) {
                Priority.URGENT -> parts += "Marked urgent"
                Priority.HIGH -> parts += "High priority"
                Priority.MEDIUM -> if (parts.isEmpty()) parts += "Medium priority"
                Priority.LOW -> if (parts.isEmpty()) parts += "Low urgency"
            }
        }

        if (parts.isEmpty()) {
            parts += "Open on your list"
        }

        return parts.take(2).joinToString(" \u00B7 ").take(MAX_REASON_CHARS)
    }

    private fun urgencyPoints(
        task: Task,
        nowMillis: Long,
        today: LocalDate,
        zone: ZoneId
    ): Int {
        val due = task.dueAt ?: return 8
        val startOfToday = startOfDay(today, zone)
        val endOfToday = endOfDay(today, zone)
        val endOfTomorrow = endOfDay(today.plusDays(1), zone)
        val endOfWeek = endOfDay(today.plusDays(7), zone)

        return when {
            due < startOfToday || due < nowMillis -> 50
            due <= nowMillis + THREE_HOURS_MS -> 42
            due <= endOfToday -> 34
            due <= endOfTomorrow -> 22
            due <= endOfWeek -> 12
            else -> 4
        }
    }

    private fun priorityPoints(priority: Priority): Int = when (priority) {
        Priority.URGENT -> 35
        Priority.HIGH -> 24
        Priority.MEDIUM -> 12
        Priority.LOW -> 0
    }

    private fun habitStreakPoints(task: Task, today: LocalDate, zone: ZoneId): Int {
        if (!isHabitDueOrOverdue(task, today, zone)) return 0
        return when {
            task.streak >= 3 -> 18
            task.streak in 1..2 -> 10
            else -> 0
        }
    }

    private fun isHabitDueOrOverdue(task: Task, today: LocalDate, zone: ZoneId): Boolean {
        if (task.repeat == RepeatRule.NONE) return false
        val due = task.dueAt ?: return true
        return due <= endOfDay(today, zone)
    }

    private fun momentumPoints(task: Task): Int {
        val total = task.subTasks.size
        if (total == 0) return 0
        val done = task.subTasks.count { it.isDone }
        if (done == 0) return 0
        return if (done * 2 >= total) 12 else 6
    }

    private fun agePoints(task: Task, nowMillis: Long): Int {
        if (task.dueAt != null) return 0
        val ageMs = (nowMillis - task.createdAt).coerceAtLeast(0L)
        return when {
            ageMs >= 14 * DAY_MS -> 12
            ageMs >= 7 * DAY_MS -> 8
            else -> 0
        }
    }

    private fun startOfDay(date: LocalDate, zone: ZoneId): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun endOfDay(date: LocalDate, zone: ZoneId): Long =
        date.atTime(23, 59, 59, 999_000_000).atZone(zone).toInstant().toEpochMilli()

    companion object {
        const val MAX_TOP_FOCUS = 3
        const val MAX_REASON_CHARS = 90
        private const val THREE_HOURS_MS = 3 * 60 * 60 * 1000L
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
