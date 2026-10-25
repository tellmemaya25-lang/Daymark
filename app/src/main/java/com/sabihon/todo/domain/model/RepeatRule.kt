package com.sabihon.todo.domain.model

/**
 * How often a task comes back.
 *
 * Persisted by name in Firestore (`repeat`), so the order of these values is not
 * meaningful and renaming one is a data migration.
 */
enum class RepeatRule {
    /** A plain one-off task. Every field added for habits is ignored on these. */
    NONE,

    /** Every single day. */
    DAILY,

    /** Only on the days listed in [Task.repeatDays] (1 = Monday … 7 = Sunday). */
    WEEKLY;

    val isRecurring: Boolean get() = this != NONE

    companion object {
        /**
         * Lenient parse for documents written before this field existed, or by an
         * older client. Anything unrecognised is treated as a one-off task rather
         * than as a habit that silently spawns copies of itself.
         */
        fun fromString(value: String?): RepeatRule {
            return when (value?.uppercase()?.trim()) {
                "DAILY" -> DAILY
                "WEEKLY" -> WEEKLY
                "NONE" -> NONE
                else -> NONE
            }
        }
    }
}
