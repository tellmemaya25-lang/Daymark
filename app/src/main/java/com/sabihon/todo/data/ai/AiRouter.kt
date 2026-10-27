package com.sabihon.todo.data.ai

import com.sabihon.todo.domain.ai.AiModelTier
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.Task
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimum-Sufficient-Intelligence Router (`docs/02-daymark-ai-system-design.md` §7):
 *
 *  - **Rung 0 (`LOCAL`)**: Cloud disabled, offline, or `< 2` open tasks (1 task needs 0 tokens).
 *  - **Rung 1 (`FLASH_LITE`)**: Default fast tier for `2..15` tasks or structured lists.
 *  - **Rung 2 (`FLASH`)**: Escalated only for dense lists (`>= 16` tasks across `>= 3`
 *    projects with `>= 5` undated/default-priority items) where semantic trade-offs matter.
 *  - **Rung 3 (Human schedule-conflict callout)**: Flags when `>= 4` tasks collide in
 *    the next 2 hours so the user chooses what to defer rather than letting AI guess.
 */
@Singleton
class AiRouter @Inject constructor() {

    fun chooseTier(
        openTasks: List<Task>,
        categories: List<Category>,
        cloudEnabled: Boolean
    ): AiModelTier {
        val active = openTasks.filter { !it.isCompleted && !it.isDeleted }
        if (!cloudEnabled || active.size < MIN_CLOUD_TASKS) {
            return AiModelTier.LOCAL
        }

        val usedCategories = active.mapNotNull { it.categoryId }.toSet()
            .intersect(categories.map { it.id }.toSet())
        val ambiguousCount = active.count { it.dueAt == null || it.priority == Priority.MEDIUM }

        val needsDeeperSynthesis = active.size >= ESCALATION_TASK_THRESHOLD &&
            usedCategories.size >= ESCALATION_CATEGORY_THRESHOLD &&
            ambiguousCount >= ESCALATION_AMBIGUOUS_THRESHOLD

        return if (needsDeeperSynthesis) AiModelTier.FLASH else AiModelTier.FLASH_LITE
    }

    /**
     * Rung 3 human escalation: detects when too many deadlines collide imminently so
     * the plan explicitly invites the user to reschedule rather than pretending all
     * of them fit into two hours.
     */
    fun detectScheduleCollision(
        openTasks: List<Task>,
        nowMillis: Long = System.currentTimeMillis()
    ): String? {
        val imminentCount = openTasks.count { task ->
            !task.isCompleted &&
                !task.isDeleted &&
                task.dueAt != null &&
                task.dueAt in (nowMillis - HOUR_MS)..(nowMillis + TWO_HOURS_MS)
        }
        return if (imminentCount >= COLLISION_THRESHOLD) {
            "Heads up: $imminentCount tasks are due within 2 hours \u2014 pick one to lead and move the rest."
        } else {
            null
        }
    }

    companion object {
        const val MIN_CLOUD_TASKS = 2
        const val ESCALATION_TASK_THRESHOLD = 16
        const val ESCALATION_CATEGORY_THRESHOLD = 3
        const val ESCALATION_AMBIGUOUS_THRESHOLD = 5
        const val COLLISION_THRESHOLD = 4
        private const val HOUR_MS = 60 * 60 * 1000L
        private const val TWO_HOURS_MS = 2 * 60 * 60 * 1000L
    }
}
