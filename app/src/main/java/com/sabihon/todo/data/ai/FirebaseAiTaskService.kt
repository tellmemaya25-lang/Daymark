package com.sabihon.todo.data.ai

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.generationConfig
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.domain.ai.AiTaskDraft
import com.sabihon.todo.domain.ai.AiTaskService
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.RepeatRule
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only file that talks to Gemini.
 *
 * Deliberately thin: it builds a prompt, asks for JSON, and maps the answer. Whether
 * that answer is trustworthy - whether the category exists, whether the date is sane -
 * is decided by [com.sabihon.todo.domain.usecase.ParseQuickAddUseCase], so this class
 * can stay replaceable and the validation can be tested without a network.
 *
 * No API key lives here. Firebase AI Logic authorises the request through the Firebase
 * project, which is why App Check has to be installed before the first call (see
 * `SabihonApp`).
 */
@Singleton
class FirebaseAiTaskService @Inject constructor() : AiTaskService {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Built on first use, not at construction: this class is injected into a ViewModel
     * that also runs in previews and unit tests, where there is no Firebase to talk to.
     */
    private val model by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = MODEL_ID,
            generationConfig = generationConfig {
                // Ask for JSON rather than prose; the prompt pins the shape.
                responseMimeType = "application/json"
            }
        )
    }

    override suspend fun parseQuickAdd(
        input: String,
        categories: List<Category>
    ): Result<AiTaskDraft> {
        if (input.isBlank()) return Result.Error(IllegalArgumentException("Nothing to read"))

        return try {
            val response = model.generateContent(prompt(input, categories))
            val text: String? = response.text
            val payload = text?.let(::jsonObject)
            if (payload.isNullOrBlank()) {
                Result.Error(IllegalStateException("The model returned nothing usable"))
            } else {
                Result.Success(json.decodeFromString<CloudDraft>(payload).toDraft())
            }
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Builds the instruction.
     *
     * Three things earn their words here. The category list is the entire closed set -
     * the model may choose one or none, never invent one. "Now" is spelled out in the
     * device's own locale so the model does not have to guess the time zone from an
     * epoch. And the rules are ordered by authority, because a model that is told
     * "answer null when unsure" *after* a list of examples will keep inventing answers.
     */
    private fun prompt(input: String, categories: List<Category>): String {
        val now = Date()
        val stamp = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(now)
        val lists = if (categories.isEmpty()) {
            "(the user has no lists yet - always null)"
        } else {
            categories.joinToString("\n") { "- ${it.id}: ${it.name}" }
        }

        return """
            You convert one line typed into a todo app into JSON fields.
            Reply with a single JSON object and nothing else. No prose, no code fences.

            {"title":string,"description":string|null,"dueAt":number|null,"priority":"LOW"|"MEDIUM"|"HIGH"|"URGENT"|null,"categoryId":string|null,"repeat":"NONE"|"DAILY"|"WEEKLY","repeatDays":[number],"subTasks":[string],"reminder":boolean|null,"reminderLeadMinutes":number|null}

            Read the way a person would, not the way a parser would:
            - Ignore filler that carries no meaning: "around", "about", "sometime", "maybe", "later", "please", "I need to", "remind me to".
            - "8:00 p.m.", "8 pm", "eight in the evening" and "2000" all mean the same thing.
            - A part of a day is a time: morning 09:00, afternoon 14:00, evening 18:00, night 20:00, noon 12:00, midnight 00:00. A weekday plus a part of a day uses both.
            - "tonight" means today at 20:00. "end of the week" means Friday 17:00. "before lunch" means 11:30, "after lunch" means 13:30.
            - "in two hours", "in 30 minutes", "in three days" are offsets from now.
            - Narrow it if the meaning is clear; otherwise null. Never invent a date from a vaguer phrase than the examples here.

            Field rules:
            - title: the task itself, with the date, priority and reminder words removed. Sentence case, max 80 characters. This is the only required field.
            - description: only when the line clearly separates a note from the task, e.g. after " - ", a dash, or "note:". Max 300 characters. Otherwise null.
            - dueAt: epoch milliseconds, in the device's time zone. Now is $stamp (${now.time}).
            - priority: only when the wording implies one ("urgent", "p1", "when you get a chance" implies LOW). Otherwise null.
            - categoryId: one of the ids below, or null. Never invent an id.
            - repeat: DAILY for "every day"; WEEKLY with repeatDays for named weekdays; NONE otherwise.
            - repeatDays: 1 = Monday … 7 = Sunday. Empty unless WEEKLY.
            - subTasks: split out the steps the sentence already names, e.g. "pack, print the tickets and charge the camera" is three. Max 8, each max 80 characters, sentence case, no leading verbs repeated. Empty array when the line names no steps.
            - reminder: true only when the line asks for one ("remind me", "don't let me forget", "alert") or when the task is time-bound enough that missing it would matter; false when the line says not to; null when it says nothing either way.
            - reminderLeadMinutes: minutes before dueAt, only when stated ("30 minutes before"). Otherwise null.
            - If there is no due date, reminder must be null - never true.

            The user's lists:
            $lists

            The line:
            $input
        """.trimIndent()
    }

    /** Tolerates a stray code fence or a sentence around the object. */
    private fun jsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else null
    }

    /** The wire shape. Every field is optional: a partial answer is still usable. */
    @Serializable
    private data class CloudDraft(
        val title: String? = null,
        val description: String? = null,
        val dueAt: Long? = null,
        val priority: String? = null,
        val categoryId: String? = null,
        val repeat: String? = null,
        val repeatDays: List<Int>? = null,
        val subTasks: List<String>? = null,
        val reminder: Boolean? = null,
        val reminderLeadMinutes: Int? = null
    ) {
        fun toDraft(): AiTaskDraft = AiTaskDraft(
            title = title.orEmpty().trim(),
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            dueAt = dueAt?.takeIf { it > 0 },
            priority = priority?.let { parsePriority(it) },
            categoryId = categoryId?.trim()?.takeIf { it.isNotEmpty() },
            repeat = repeat?.let { parseRepeat(it) } ?: RepeatRule.NONE,
            repeatDays = repeatDays.orEmpty(),
            // Blank strings arrive from a model that answered with "" instead of []; they
            // are dropped here rather than becoming empty sub-task rows in the editor.
            subTasks = subTasks.orEmpty()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(MAX_SUB_TASKS),
            reminder = reminder,
            reminderLeadMinutes = reminderLeadMinutes?.takeIf { it >= 0 }
        )
    }

    private companion object {
        /**
         * The cheap tier on purpose: this is classification, not reasoning, and it is
         * free on the Gemini Developer API's no-cost plan. One constant, so moving to
         * `gemini-3.8-flash` is a one-line change if the parsing disappoints.
         */
        const val MODEL_ID = "gemini-3.5-flash-lite"

        /** A sentence with more named steps than this is not a sentence any more. */
        const val MAX_SUB_TASKS = 8
    }
}

/* File-private on purpose: the nested wire type needs them, and that is easier to
 * reason about than a companion reached through an outer class. */

private fun parsePriority(value: String): Priority? = runCatching {
    Priority.valueOf(value.trim().uppercase(Locale.ROOT))
}.getOrNull()

private fun parseRepeat(value: String): RepeatRule? = runCatching {
    RepeatRule.valueOf(value.trim().uppercase(Locale.ROOT))
}.getOrNull()
