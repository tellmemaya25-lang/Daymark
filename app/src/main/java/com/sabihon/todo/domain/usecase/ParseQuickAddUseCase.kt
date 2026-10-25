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

    suspend operator fun invoke(input: String, categories: List<Category>): QuickAddResult {
        val sentence = input.trim()
        val offline = parser.parse(sentence, categories)

        val cloud = withTimeoutOrNull(TIMEOUT_MS) {
            aiTaskService.parseQuickAdd(sentence, categories)
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

        return draft.copy(
            title = draft.title.trim().take(MAX_TITLE),
            dueAt = plausibleDueAt,
            categoryId = validCategory,
            repeatDays = validDays,
            repeat = if (validDays.isEmpty() && draft.repeat == RepeatRule.WEEKLY) {
                RepeatRule.NONE
            } else {
                draft.repeat
            }
        )
    }

    /** Cloud first, offline as the backstop for anything it left empty. */
    private fun merge(cloud: AiTaskDraft, offline: AiTaskDraft): AiTaskDraft = cloud.copy(
        title = cloud.title.ifBlank { offline.title },
        dueAt = cloud.dueAt ?: offline.dueAt,
        priority = cloud.priority ?: offline.priority,
        categoryId = cloud.categoryId ?: offline.categoryId,
        repeat = if (cloud.repeat != RepeatRule.NONE) cloud.repeat else offline.repeat,
        repeatDays = cloud.repeatDays.ifEmpty { offline.repeatDays }
    )

    private companion object {
        /** Short enough that a stuck request does not hold the editor. */
        const val TIMEOUT_MS = 12_000L
        const val MAX_TITLE = 120
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val FIVE_YEARS_MS = 5L * 365 * 24 * 60 * 60 * 1000
    }
}
