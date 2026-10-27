package com.sabihon.todo.domain.usecase

import com.sabihon.todo.core.util.Result
import com.sabihon.todo.data.ai.QuickAddParser
import com.sabihon.todo.domain.ai.AiTaskDraft
import com.sabihon.todo.domain.ai.AiTaskService
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.RepeatRule
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * A parsed sentence and where it came from - the UI says "AI" or "on-device" rather
 * than pretending both are the same thing.
 */
data class QuickAddResult(
    val draft: AiTaskDraft,
    val source: Source
) {
    enum class Source { CLOUD, LOCAL }
}

/**
 * One sentence in, editor fields out.
 *
 * The rules, in order:
 *  - the offline parser always runs, so there is always something to fall back on;
 *  - the model is given a hard time limit, and a timeout is not an error the user sees;
 *  - whatever the model returns is *validated* before it is trusted - an invented
 *    category id, a date in 1970, or a weekday of 9 is discarded rather than applied;
 *  - the two are merged, so a field the model missed but the words plainly contained
 *    ("p1") is still filled in.
 *
 * Nothing here writes anything. The result is handed to the editor, where the user can
 * change it or press undo before saving.
 */
class ParseQuickAddUseCase @Inject constructor(
    private val aiTaskService: AiTaskService,
    private val parser: QuickAddParser
) {

    /**
     * @param allowCloud false when the user has switched AI off in Settings. The offline
     *   parser still runs - dates, times, priorities, repeats, reminders and spoken
     *   sub-task separators all still fill in. Only a description and prose sub-tasks,
     *   which need a model, stop.
     */
    suspend operator fun invoke(
        input: String,
        categories: List<Category>,
        allowCloud: Boolean = true
    ): QuickAddResult {
        val sentence = input.trim()
        val offline = parser.parse(sentence, categories)

        // Not even attempted when switched off: no request, no round-trip, no silent
        // 12-second wait for a call that was never allowed to happen.
        val cloud = if (allowCloud) {
            withTimeoutOrNull(TIMEOUT_MS) {
                aiTaskService.parseQuickAdd(sentence, categories)
            }
        } else {
            null
        }

        val draft = (cloud as? Result.Success<AiTaskDraft>)?.data
            ?.let { sanitize(it, categories) }
            ?.takeIf { it.title.isNotBlank() }
            ?.let { merge(it, offline) }

        return if (draft != null) {
            QuickAddResult(draft, QuickAddResult.Source.CLOUD)
        } else {
            QuickAddResult(offline, QuickAddResult.Source.LOCAL)
        }
    }

    /**
     * Keeps only values this app can actually store.
     *
     * This is the whole defence against a confident hallucination: an id that does not
     * exist would silently detach the task from its list, and a nonsense date would be
     * worse than no date at all.
     */
    private fun sanitize(draft: AiTaskDraft, categories: List<Category>): AiTaskDraft {
        val validCategory = draft.categoryId?.takeIf { id -> categories.any { it.id == id } }
        val validDays = draft.repeatDays.filter { it in 1..7 }.distinct().sorted()
        val now = System.currentTimeMillis()
        val plausibleDueAt = draft.dueAt?.takeIf { it in (now - DAY_MS)..(now + FIVE_YEARS_MS) }

        // A reminder without a due date has nothing to hang from. Dropping it here is the
        // difference between "the user gets no notification" and "the reminder fires at
        // midnight because the field was empty".
        val reminderWanted = draft.reminder == true && plausibleDueAt != null
        val lead = draft.reminderLeadMinutes
            ?.coerceIn(0, MAX_LEAD_MINUTES)
            ?.takeIf { reminderWanted }

        return draft.copy(
            title = draft.title.trim().take(MAX_TITLE),
            description = draft.description?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_DESCRIPTION),
            dueAt = plausibleDueAt,
            categoryId = validCategory,
            repeatDays = validDays,
            repeat = if (validDays.isEmpty() && draft.repeat == RepeatRule.WEEKLY) {
                RepeatRule.NONE
            } else {
                draft.repeat
            },
            subTasks = draft.subTasks
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { it.take(MAX_SUBTASK) }
                .distinctBy { it.lowercase() }
                .take(MAX_SUB_TASKS),
            // false survives (the user said "no reminder"); true without a date does not.
            reminder = when {
                draft.reminder == true -> reminderWanted
                draft.reminder == false -> false
                else -> null
            },
            reminderLeadMinutes = lead
        )
    }

    /** Cloud first, offline as the backstop for anything it left empty. */
    private fun merge(cloud: AiTaskDraft, offline: AiTaskDraft): AiTaskDraft = cloud.copy(
        title = cloud.title.ifBlank { offline.title },
        description = cloud.description ?: offline.description,
        dueAt = cloud.dueAt ?: offline.dueAt,
        priority = cloud.priority ?: offline.priority,
        categoryId = cloud.categoryId ?: offline.categoryId,
        repeat = if (cloud.repeat != RepeatRule.NONE) cloud.repeat else offline.repeat,
        repeatDays = cloud.repeatDays.ifEmpty { offline.repeatDays },
        subTasks = cloud.subTasks.ifEmpty { offline.subTasks },
        // null means "the model said nothing", so the offline reading gets its say. An
        // explicit false is the model disagreeing, and it wins.
        reminder = cloud.reminder ?: offline.reminder,
        reminderLeadMinutes = cloud.reminderLeadMinutes ?: offline.reminderLeadMinutes
    )

    private companion object {
        /** Short enough that a stuck request does not hold the editor. */
        const val TIMEOUT_MS = 12_000L
        const val MAX_TITLE = 120
        const val MAX_DESCRIPTION = 500
        const val MAX_SUBTASK = 80
        const val MAX_SUB_TASKS = 8

        /** A week is the longest lead worth honouring; anything longer is a typo. */
        const val MAX_LEAD_MINUTES = 7 * 24 * 60
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val FIVE_YEARS_MS = 5L * 365 * 24 * 60 * 60 * 1000
    }
}
