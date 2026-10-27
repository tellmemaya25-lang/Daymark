package com.sabihon.todo.domain.ai

import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Priority
import java.security.MessageDigest

/**
 * A task the app thinks you might want next, and why.
 *
 * Suggestions are never tasks. Nothing is written until the user taps `+ Add`, and
 * [suggestionId] is what a `×` writes down so the same idea is not offered again -
 * a dismissal the user cannot take back is a reason to stop reading the card.
 */
data class SuggestedTaskCandidate(
    /** Stable across runs: a hash of the title, never the title itself. */
    val suggestionId: String,
    val title: String,
    /** One of the user's own lists, or null. Validated in the use case, not here. */
    val categoryId: String? = null,
    val priority: Priority = Priority.MEDIUM,
    /** Hours from now, or null for undated. Clamped before it is ever shown. */
    val dueOffsetHours: Int? = null,
    /** Short, plain reason printed under the title: "Overdue 3 days". */
    val reason: String = ""
) {
    companion object {
        /**
         * Deterministic id for one suggestion.
         *
         * Hashed rather than used verbatim: dismissals are persisted, and the setting
         * file should not become a list of the user's task titles in plain text.
         */
        fun idFor(title: String, categoryId: String?): String {
            val raw = "${title.trim().lowercase()}::${categoryId.orEmpty()}"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(ID_LENGTH)
        }

        private const val ID_LENGTH = 16
    }
}

/**
 * The privacy-safe picture the model is allowed to see when it is asked what to do
 * next: what is still open, what the user just finished, and the names of their own
 * lists. No ids, no notes, no timestamps - there is nothing in here it needs.
 */
data class SuggestionContext(
    /** At most 10 open titles, most pressing first. */
    val openTitles: List<String> = emptyList(),
    /** At most 8 recently completed titles - what the user has been working on. */
    val recentCompletedTitles: List<String> = emptyList(),
    val categories: List<Category> = emptyList()
)
