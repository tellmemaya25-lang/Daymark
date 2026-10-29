package com.sabihon.todo.domain.usecase

import android.util.Log
import com.sabihon.todo.core.datastore.AiPreferenceStore
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.data.ai.AiFeature
import com.sabihon.todo.data.ai.AiInputGuard
import com.sabihon.todo.data.ai.AiQuotaGuard
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
 *  - when Cloud AI is switched off in Settings or daily quota is reached, only the
 *    offline parser runs;
 *  - PII (emails, phone numbers) is scrubbed from the prompt copy before cloud egress;
 *  - the model is given a hard time limit, and a timeout is not an error the user sees;
 *  - whatever the model returns is *validated* before it is trusted;
 *  - the two are merged, so a field the model missed ("p1") is still filled in.
 *
 * Nothing here writes anything. The result is handed to the editor, where the user can
 * change it or press undo before saving.
 */
class ParseQuickAddUseCase private constructor(
    private val aiTaskService: AiTaskService,
    private val parser: QuickAddParser,
    private val inputGuard: AiInputGuard,
    /**
     * The two dependencies that only exist when the cloud half is wired up, bundled so
     * there is exactly one nullable parameter.
     *
     * Their being separate nullable parameters is what broke the build: Kotlin erases
     * `AiQuotaGuard?` to `AiQuotaGuard`, so this constructor and the injected one below
     * had the same JVM signature and the compiler rejected the pair. One nullable
     * parameter of its own type cannot collide with anything.
     */
    private val cloud: CloudGate?
) {

    /** Dagger's way in: the cloud half is always present in the app. */
    @Inject
    constructor(
        aiTaskService: AiTaskService,
        parser: QuickAddParser,
        inputGuard: AiInputGuard,
        quotaGuard: AiQuotaGuard,
        prefs: AiPreferenceStore
    ) : this(
        aiTaskService = aiTaskService,
        parser = parser,
        inputGuard = inputGuard,
        cloud = CloudGate(quotaGuard, prefs)
    )

    /** Preserved for unit tests that wire only [AiTaskService] and [QuickAddParser]. */
    constructor(
        aiTaskService: AiTaskService,
        parser: QuickAddParser
    ) : this(
        aiTaskService = aiTaskService,
        parser = parser,
        inputGuard = AiInputGuard(),
        cloud = null
    )

    /** The rate limiter and the cloud switch, held together so neither is ever half-set. */
    internal class CloudGate(
        val quotaGuard: AiQuotaGuard,
        val prefs: AiPreferenceStore
    )

    suspend operator fun invoke(
        input: String,
        categories: List<Category>,
        uid: String? = null
    ): QuickAddResult {
        val sentence = input.trim()
        val offline = parser.parse(sentence, categories)

        val gate = cloud
        if (gate != null && !gate.prefs.isCloudEnabledNow()) {
            Log.i(TAG, "quick add: cloud AI is switched off, using the on-device reading")
            return QuickAddResult(offline, QuickAddResult.Source.LOCAL)
        }

        val scrubbedInput = inputGuard.sanitizeForPrompt(sentence, MAX_PROMPT_SENTENCE)
            .ifBlank {
                Log.w(TAG, "quick add: nothing survived PII scrubbing, using the on-device reading")
                return QuickAddResult(offline, QuickAddResult.Source.LOCAL)
            }
        val estimatedTokens = inputGuard.estimateTokens(
            promptChars = 950 + scrubbedInput.length,
            expectedOutputChars = 240
        )

        val cloudResult: Result<AiTaskDraft>? = if (gate != null) {
            val outcome = gate.quotaGuard.runIfPermitted(
                uid = uid,
                feature = AiFeature.QUICK_ADD,
                estimatedTokens = estimatedTokens,
                enforceCooldown = false
            ) {
                withTimeoutOrNull(TIMEOUT_MS) {
                    aiTaskService.parseQuickAdd(scrubbedInput, categories, uid)
                }
            }
            when (outcome) {
                is AiQuotaGuard.ExecutionOutcome.Blocked -> {
                    Log.w(TAG, "quick add: cloud call blocked by quota guard (${outcome.reason})")
                    null
                }
                is AiQuotaGuard.ExecutionOutcome.Executed -> {
                    val res = outcome.value
                    if (res is Result.Error) {
                        // The one line that turns "smart AI isn't working" into a
                        // diagnosis. Without it every failure looked identical to the
                        // feature being switched off.
                        Log.w(TAG, "quick add: the model call failed", res.exception)
                        // The service records a successful call itself: it is the only
                        // layer that knows which provider answered, and the budget is per
                        // provider. Failures are the guard's business, not the chain's.
                        gate.quotaGuard.recordFailure(res.exception)
                    }
                    res
                }
            }
        } else {
            withTimeoutOrNull(TIMEOUT_MS) {
                aiTaskService.parseQuickAdd(scrubbedInput, categories, uid)
            }
        }

        val draft = (cloudResult as? Result.Success<AiTaskDraft>)?.data
            ?.let { sanitize(it, categories) }
            ?.takeIf { it.title.isNotBlank() }
            ?.let { merge(it, offline) }

        return if (draft != null) {
            Log.i(TAG, "quick add: the model answered")
            QuickAddResult(draft, QuickAddResult.Source.CLOUD)
        } else {
            Log.i(TAG, "quick add: falling back to the on-device reading")
            QuickAddResult(offline, QuickAddResult.Source.LOCAL)
        }
    }

    /**
     * Keeps only values this app can actually store.
     */
    private fun sanitize(draft: AiTaskDraft, categories: List<Category>): AiTaskDraft {
        val validCategory = draft.categoryId?.takeIf { id -> categories.any { it.id == id } }
        val validDays = draft.repeatDays.filter { it in 1..7 }.distinct().sorted()
        val now = System.currentTimeMillis()
        val plausibleDueAt = draft.dueAt?.takeIf { it in (now - DAY_MS)..(now + FIVE_YEARS_MS) }

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
        reminder = cloud.reminder ?: offline.reminder,
        reminderLeadMinutes = cloud.reminderLeadMinutes ?: offline.reminderLeadMinutes
    )

    private companion object {
        const val TAG = "QuickAdd"
        const val TIMEOUT_MS = 12_000L
        const val MAX_PROMPT_SENTENCE = 300
        const val MAX_TITLE = 120
        const val MAX_DESCRIPTION = 500
        const val MAX_SUBTASK = 80
        const val MAX_SUB_TASKS = 8

        const val MAX_LEAD_MINUTES = 7 * 24 * 60
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val FIVE_YEARS_MS = 5L * 365 * 24 * 60 * 60 * 1000
    }
}
