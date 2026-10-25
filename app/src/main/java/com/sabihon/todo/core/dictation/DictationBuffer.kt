package com.sabihon.todo.core.dictation

/**
 * Puts a dictated phrase into a field without destroying what was already there.
 *
 * Every render starts from [originalText] rather than from the last thing published,
 * which is what makes the partial -> final handoff idempotent: the recogniser sends
 * "Call mom", then "Call mom tomorrow", and the second one replaces the first instead
 * of appending to it.
 *
 * Kept free of Android imports on purpose - this is the half of dictation that can be
 * tested properly on the JVM.
 */
class DictationBuffer(
    private val original: String,
    /** Descriptions grow down the page; titles grow along one line. */
    private val multiline: Boolean = false,
    /**
     * A form appends to what is there; a search box does not. Saying "groceries" and
     * then "dentist" is a new search, not a longer one - but Cancel still has to put
     * the old query back, which is why [originalText] survives either way.
     */
    private val replace: Boolean = false
) {

    /** Exactly what the field held before dictation started. Cancel puts this back. */
    val originalText: String get() = original

    fun render(phrase: String): String {
        // Speech services like to hand back leading, trailing and doubled spaces.
        val spoken = phrase.trim().replace(WHITESPACE, " ")
        if (spoken.isEmpty()) return original
        if (replace) return spoken
        if (original.isBlank()) return spoken

        val separator = if (multiline) "\n" else " "
        return original.trimEnd() + separator + spoken
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
