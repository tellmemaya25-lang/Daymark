package com.sabihon.todo.domain.ai

import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.RepeatRule

/**
 * What one sentence collapses into.
 *
 * Every field is nullable (or has a neutral default) because "don't touch this" and
 * "set this to empty" are different instructions: the editor keeps whatever the user
 * already typed wherever the draft has nothing to say.
 */
data class AiTaskDraft(
    val title: String = "",
    /** Epoch millis; null means the sentence carried no date and no time. */
    val dueAt: Long? = null,
    val priority: Priority? = null,
    /** Always one of the categories the user actually has, or null. Never invented. */
    val categoryId: String? = null,
    val repeat: RepeatRule = RepeatRule.NONE,
    /** 1 = Monday … 7 = Sunday, only meaningful for [RepeatRule.WEEKLY]. */
    val repeatDays: List<Int> = emptyList()
) {
    /** True when the sentence produced something worth showing a summary for. */
    val hasDetails: Boolean
        get() = dueAt != null ||
            priority != null ||
            categoryId != null ||
            repeat != RepeatRule.NONE
}
