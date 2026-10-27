package com.sabihon.todo.data.ai

import com.sabihon.todo.domain.ai.AiTaskDraft
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.RepeatRule
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * The offline half of quick add: dates, times, priorities, `@projects`, "every mon wed".
 *
 * This is not a poor cousin of the model call. It is what makes the button work in a
 * tunnel, with the feature switched off, and on the day the API is down - and because it
 * is ordinary Kotlin over `java.time`, it is the part that can actually be tested.
 *
 * `today` and `zone` are parameters rather than reads so the tests never depend on when
 * they run.
 *
 * What it deliberately cannot do: "sometime after lunch", "when I'm free", "the usual".
 * That is the model's job, and the reason both exist.
 */
class QuickAddParser @Inject constructor() {

    fun parse(
        input: String,
        categories: List<Category>,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): AiTaskDraft {
        val words = input.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return AiTaskDraft()

        // A word is "used" once it has been read as something other than the title, so
        // "pay rent friday" keeps "pay rent" and drops "friday" from the title.
        val used = BooleanArray(words.size)

        val priority = readPriority(words, used)
        val categoryId = readCategory(words, used, categories)
        val repeat = readRepeat(words, used)

        val date = readDate(words, used, today)
        val time = readTime(words, used)

        val dueAt = resolveDueAt(date, time, today, zone)
        val title = remainingTitle(words, used, input)

        return AiTaskDraft(
            title = title,
            dueAt = dueAt,
            priority = priority,
            categoryId = categoryId,
            repeat = repeat.first,
            repeatDays = repeat.second
        )
    }

    /* ---------------------------------------------------------------- */
    /* Readers                                                          */
    /* ---------------------------------------------------------------- */

    private fun readPriority(words: List<String>, used: BooleanArray): Priority? {
        words.forEachIndexed { index, word ->
            val clean = word.clean()
            val match = PRIORITIES[clean] ?: return@forEachIndexed
            if (used[index]) return@forEachIndexed
            used[index] = true
            return match
        }
        return null
    }

    private fun readCategory(
        words: List<String>,
        used: BooleanArray,
        categories: List<Category>
    ): String? {
        words.forEachIndexed { index, word ->
            if (used[index] || !word.startsWith("@")) return@forEachIndexed
            val needle = word.removePrefix("@").clean()
            if (needle.isEmpty()) return@forEachIndexed
            // Longest name first, so "@workout" cannot be swallowed by a list called "Work".
            val match = categories
                .sortedByDescending { it.name.length }
                .firstOrNull { it.name.lowercase().replace(" ", "").startsWith(needle) }
                ?: return@forEachIndexed
            used[index] = true
            return match.id
        }
        return null
    }

    private fun readRepeat(words: List<String>, used: BooleanArray): Pair<RepeatRule, List<Int>> {
        val everyIndex = words.indexOfFirst { it.clean() in EVERY_WORDS }
        if (everyIndex >= 0) {
            used[everyIndex] = true
            val days = mutableListOf<Int>()
            var scan = everyIndex + 1
            while (scan < words.size && !used[scan]) {
                val day = WEEKDAYS[words[scan].clean()] ?: break
                days += day
                used[scan] = true
                scan++
            }
            if (days.isNotEmpty()) return RepeatRule.WEEKLY to days.distinct().sorted()

            // "every day" / "every week" - the word after "every" is a unit, not a weekday.
            val unitIndex = (everyIndex + 1).takeIf { it < words.size && !used[it] }
            val unit = unitIndex?.let { words[it].clean() }.orEmpty()
            if (unitIndex != null && unit in ALL_UNITS) used[unitIndex] = true

            return when (unit) {
                in DAILY_UNITS -> RepeatRule.DAILY to emptyList()
                in WEEKDAY_UNITS -> RepeatRule.WEEKLY to listOf(1, 2, 3, 4, 5)
                // "every month" / "every year" have no model here; say nothing rather
                // than pretending the task is daily.
                in UNSUPPORTED_UNITS -> RepeatRule.NONE to emptyList()
                else -> RepeatRule.DAILY to emptyList()
            }
        }

        val weekdaysIndex = words.indexOfFirst { it.clean() == "weekdays" }
        if (weekdaysIndex >= 0) {
            used[weekdaysIndex] = true
            return RepeatRule.WEEKLY to listOf(1, 2, 3, 4, 5)
        }

        val dailyIndex = words.indexOfFirst { it.clean() in DAILY_WORDS }
        if (dailyIndex >= 0) {
            used[dailyIndex] = true
            return RepeatRule.DAILY to emptyList()
        }

        return RepeatRule.NONE to emptyList()
    }

    private fun readDate(words: List<String>, used: BooleanArray, today: LocalDate): LocalDate? {
        words.forEachIndexed { index, raw ->
            if (used[index]) return@forEachIndexed
            val word = raw.clean()

            RELATIVE_DAYS[word]?.let { offset ->
                used[index] = true
                return today.plusDays(offset.toLong())
            }

            val day = WEEKDAYS[word] ?: return@forEachIndexed

            // Pull in "on" / "this" / "next" / "by" so they do not survive into the title.
            var isNext = false
            var scan = index - 1
            while (scan >= 0 && !used[scan] && words[scan].clean() in DAY_MODIFIERS) {
                isNext = isNext || words[scan].clean() in NEXT_WORDS
                used[scan] = true
                scan--
            }

            var ahead = (day - today.dayOfWeek.value + 7) % 7
            // "monday" on a Monday means today; "next monday" always means a week out.
            if (isNext && ahead == 0) ahead = 7
            used[index] = true
            return today.plusDays(ahead.toLong())
        }
        return null
    }

    private fun readTime(words: List<String>, used: BooleanArray): LocalTime? {
        words.forEachIndexed { index, raw ->
            if (used[index]) return@forEachIndexed
            val match = TIME_PATTERN.matchEntire(raw.lowercase()) ?: return@forEachIndexed

            val hour = match.groupValues[1].toIntOrNull() ?: return@forEachIndexed
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            var meridiem = match.groupValues[3]
            val hasColon = match.groupValues[2].isNotEmpty()
            val hasPreposition = index > 0 && words[index - 1].clean() in TIME_PREPOSITIONS

            // "8:00 p.m." and "at 8 pm" write the meridiem as its own word. Without
            // this, "8:00 p.m." was read as 08:00 in the morning and the "p.m." was
            // left sitting in the title.
            var meridiemIndex: Int? = null
            if (meridiem.isEmpty()) {
                val next = (index + 1).takeIf { it < words.size && !used[it] }
                val nextMeridiem = next?.let { MERIDIEMS[words[it].clean()] }
                if (nextMeridiem != null) {
                    meridiem = nextMeridiem
                    meridiemIndex = next
                }
            }

            // A bare number is only a time when it is said as one: "at 5" yes, the 2 in
            // "buy 2 apples" no - and a trailing "pm" is enough to say it.
            if (meridiem.isEmpty() && !hasColon && !hasPreposition) return@forEachIndexed

            val hour24 = when {
                meridiem == "pm" && hour < 12 -> hour + 12
                meridiem == "am" && hour == 12 -> 0
                else -> hour
            }
            if (hour24 !in 0..23 || minute !in 0..59) return@forEachIndexed

            used[index] = true
            // "around 8 p.m." leaves neither the preposition nor the meridiem behind.
            if (hasPreposition) used[index - 1] = true
            meridiemIndex?.let { used[it] = true }
            return LocalTime.of(hour24, minute)
        }
        return null
    }

    /* ---------------------------------------------------------------- */
    /* Assembly                                                         */
    /* ---------------------------------------------------------------- */

    private fun resolveDueAt(
        date: LocalDate?,
        time: LocalTime?,
        today: LocalDate,
        zone: ZoneId
    ): Long? {
        val day = date ?: if (time != null) today else null
        if (day == null) return null
        // A date with no time lands at 9am - the same default the date picker uses.
        val at = time ?: DEFAULT_TIME
        return day.atTime(at).atZone(zone).toInstant().toEpochMilli()
    }

    private fun remainingTitle(words: List<String>, used: BooleanArray, fallback: String): String {
        val kept = words.filterIndexed { index, _ -> !used[index] }
        val joined = kept.joinToString(" ")
            .replace(WHITESPACE, " ")
            .trim()
            .trim(' ', ',', '-', '.', ':', ';')
        // "friday 5pm" has no title in it; keep the user's words rather than an empty box.
        val resolved = joined.ifBlank { fallback.trim() }
        return resolved.replaceFirstChar { it.uppercase() }
    }

    private fun String.clean(): String = trim('.', ',', '!', '?', ':', ';').lowercase()

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val TIME_PATTERN = Regex("(\\d{1,2})(?::(\\d{2}))?(am|pm)?")

        val DEFAULT_TIME: LocalTime = LocalTime.of(9, 0)

        /** Meridiems written as a separate word, with or without the dots. */
        val MERIDIEMS = mapOf(
            "am" to "am", "a.m" to "am",
            "pm" to "pm", "p.m" to "pm"
        )

        /** Words that introduce a time, consumed with it so they do not linger. */
        val TIME_PREPOSITIONS = setOf("at", "around", "about", "by", "@")

        val PRIORITIES = mapOf(
            "urgent" to Priority.URGENT,
            "asap" to Priority.URGENT,
            "p1" to Priority.URGENT,
            "high" to Priority.HIGH,
            "p2" to Priority.HIGH,
            "medium" to Priority.MEDIUM,
            "normal" to Priority.MEDIUM,
            "p3" to Priority.MEDIUM,
            "low" to Priority.LOW,
            "p4" to Priority.LOW
        )

        /** 1 = Monday … 7 = Sunday, matching `Task.repeatDays`. */
        val WEEKDAYS = mapOf(
            "monday" to 1, "mon" to 1,
            "tuesday" to 2, "tue" to 2, "tues" to 2,
            "wednesday" to 3, "wed" to 3,
            "thursday" to 4, "thu" to 4, "thur" to 4, "thurs" to 4,
            "friday" to 5, "fri" to 5,
            "saturday" to 6, "sat" to 6,
            "sunday" to 7, "sun" to 7
        )

        val RELATIVE_DAYS = mapOf("today" to 0, "tomorrow" to 1, "tmrw" to 1, "tomorow" to 1)

        val EVERY_WORDS = setOf("every", "each")
        val DAILY_WORDS = setOf("daily", "everyday")

        val DAILY_UNITS = setOf("day", "days", "morning", "evening", "night")
        val WEEKDAY_UNITS = setOf("weekday", "weekdays")
        val UNSUPPORTED_UNITS = setOf("week", "weeks", "month", "months", "year", "years")
        val ALL_UNITS = DAILY_UNITS + WEEKDAY_UNITS + UNSUPPORTED_UNITS
        val DAY_MODIFIERS = setOf("on", "this", "next", "coming", "by")
        val NEXT_WORDS = setOf("next", "coming")
    }
}
