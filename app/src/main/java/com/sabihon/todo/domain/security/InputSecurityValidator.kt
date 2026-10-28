package com.sabihon.todo.domain.security

/**
 * Pure, framework-free input validation & injection defense guardrails across Daymark.
 *
 * Defends against:
 *  - SQL / SQLite query injection & unparameterized query templates
 *  - Firestore NoSQL document-path traversal (`/`, `\`, `.`, `..`, `__*__`)
 *  - Local filesystem cache directory traversal (`../`)
 *  - Null-byte / ASCII control-character smuggling
 *  - Unbounded string / WorkManager SQLite blob / keyword-array amplification DoS
 */
object InputSecurityValidator {

    const val MAX_DOC_ID_LENGTH = 128
    const val MAX_FILE_SEGMENT_LENGTH = 80
    const val MAX_TITLE_LENGTH = 300
    const val MAX_DESCRIPTION_LENGTH = 4_000
    const val MAX_SUBTASK_TITLE_LENGTH = 200
    const val MAX_CATEGORY_NAME_LENGTH = 80
    const val MAX_DISPLAY_NAME_LENGTH = 100
    const val MAX_SEARCH_QUERY_LENGTH = 200
    const val MAX_WORK_TITLE_LENGTH = 200
    const val MAX_WORK_DESCRIPTION_LENGTH = 500

    /**
     * Bounds for [Task][com.sabihon.todo.domain.model.Task.durationMinutes].
     *
     * 5 minutes is the shortest block that can still show a title on the timeline;
     * 12 hours is the longest a same-day block can be. Enforced on write so an
     * imported or hand-edited document cannot produce a block that runs off the grid.
     */
    const val MIN_DURATION_MINUTES = 5
    const val MAX_DURATION_MINUTES = 720

    private val SAFE_DOC_ID_CHAR_REGEX = Regex("""^[A-Za-z0-9._:\-]+$""")
    private val HEX_SQL_PAYLOAD_REGEX = Regex("""^0x[0-9a-f]{12,}$""")
    private val HEX_COLOR_REGEX = Regex("""^#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$""")
    private val MULTI_SPACE_REGEX = Regex("""[^\S\r\n]+""")
    private val ANY_WHITESPACE_REGEX = Regex("""\s+""")

    private val SQL_INJECTION_SIGNATURES = listOf(
        // Tautology must compare *identical* operands (`'1'='1`, `OR x = x`). Matching a
        // bare `AND column = value` flagged ordinary predicates such as
        // `WHERE uid = ? AND deleted = 0`, so the shape is pinned with backreferences.
        Regex("""(?i)(?:^|['"\s;)])\b(?:OR|AND)\b\s+(['"]?)(\w+)\1\s*=\s*\1\2\1"""),
        // Mixed quoting around numeric tautologies (`'1'=1`, `OR 1 = '1'`).
        Regex("""(?i)(?:^|['"\s;)])\b(?:OR|AND)\b\s+['"]?\d+['"]?\s*=\s*['"]?\d+['"]?"""),
        Regex("""(?i);\s*(?:DROP|DELETE|TRUNCATE|ALTER|UPDATE|INSERT|EXEC|EXECUTE|ATTACH)\b"""),
        Regex("""(?i)\bUNION\s+(?:ALL\s+)?SELECT\b"""),
        Regex("""(?i)\b(?:WAITFOR\s+DELAY|PG_SLEEP|BENCHMARK)\b|\bSLEEP\s*\("""),
        Regex("""(?i)(?:['"]\s*--|/\*[\s\S]*?\*/)"""),
        Regex("""(?i)\b0x[0-9a-f]{6,}\b""")
    )

    /**
     * Validates that [id] is a safe single-segment identifier suitable for Firestore
     * `.document(id)` and WorkManager unique work names / tags.
     *
     * Rejects:
     *  - null, empty, whitespace-only, or > [MAX_DOC_ID_LENGTH] strings
     *  - path separators (`/`, `\`), dot segments (`.`, `..`), and reserved `__*__` names
     *  - SQL/NoSQL metacharacters (`'`, `"`, `;`, `$`, `--`) and block-comment
     *    delimiters, plus control bytes
     */
    fun isValidFirestoreDocId(id: String?): Boolean {
        if (id.isNullOrEmpty() || id.length > MAX_DOC_ID_LENGTH) return false
        if (id == "." || id == "..") return false
        if (id.length >= 4 && id.startsWith("__") && id.endsWith("__")) return false
        if (id.contains("--")) return false
        if (HEX_SQL_PAYLOAD_REGEX.matches(id)) return false
        return SAFE_DOC_ID_CHAR_REGEX.matches(id)
    }

    /**
     * Returns [id] if [isValidFirestoreDocId] passes; otherwise throws [IllegalArgumentException].
     */
    fun requireSafeDocId(id: String?, fieldName: String = "documentId"): String {
        val candidate = id?.trim().orEmpty()
        require(isValidFirestoreDocId(candidate)) {
            "Invalid or unsafe $fieldName"
        }
        return candidate
    }

    /**
     * Converts an untrusted identifier into a strict filesystem-safe path segment
     * containing only `[A-Za-z0-9._-]` and never `.` or `..`.
     */
    fun sanitizeFileSegment(raw: String?, fallback: String = "default"): String {
        if (raw.isNullOrBlank()) return fallback
        val cleaned = buildString(raw.length.coerceAtMost(MAX_FILE_SEGMENT_LENGTH)) {
            for (ch in raw) {
                if (length >= MAX_FILE_SEGMENT_LENGTH) break
                when {
                    ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_' || ch == '-' -> append(ch)
                    else -> append('_')
                }
            }
        }.trim('_')
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") fallback else cleaned
    }

    /**
     * Strips null bytes and non-printable control characters, normalizes whitespace,
     * and enforces [maxLength]. Preserves printable characters so user text containing
     * quotes or punctuation is stored as inert literal text rather than corrupted.
     */
    fun sanitizeUserText(
        raw: String,
        maxLength: Int = MAX_TITLE_LENGTH,
        allowMultiline: Boolean = false
    ): String {
        if (raw.isEmpty()) return ""
        val withoutControls = buildString(raw.length) {
            for (ch in raw) {
                val code = ch.code
                val isAllowedWhitespace = ch == ' ' || ch == '\t' || (allowMultiline && (ch == '\n' || ch == '\r'))
                val isPrintable = code >= 0x20 && code != 0x7F && code != 0xFFFE && code != 0xFFFF
                if (isAllowedWhitespace || isPrintable) {
                    append(ch)
                } else if (!allowMultiline && (ch == '\n' || ch == '\r')) {
                    append(' ')
                }
            }
        }
        val collapsed = if (allowMultiline) {
            withoutControls.replace("\r\n", "\n").replace('\r', '\n').replace(MULTI_SPACE_REGEX, " ")
        } else {
            withoutControls.replace(ANY_WHITESPACE_REGEX, " ")
        }
        return collapsed.trim().take(maxLength.coerceAtLeast(1))
    }

    /**
     * Sanitizes and bounds a search query so control bytes, null bytes, and megabyte
     * query payloads cannot trigger regex/memory stalls.
     */
    fun sanitizeSearchQuery(raw: String): String =
        sanitizeUserText(raw, maxLength = MAX_SEARCH_QUERY_LENGTH, allowMultiline = false)

    fun isSafeHexColor(hex: String?): Boolean =
        !hex.isNullOrBlank() && HEX_COLOR_REGEX.matches(hex.trim())

    /**
     * Clamps an untrusted duration into [MIN_DURATION_MINUTES]..[MAX_DURATION_MINUTES].
     *
     * Null survives as null: "no duration given" is a valid state that the timeline
     * renders with its default block length, and must not be silently turned into a
     * number here - that would make a task look like it was scheduled for 5 minutes.
     */
    fun clampDurationMinutes(raw: Int?): Int? =
        raw?.coerceIn(MIN_DURATION_MINUTES, MAX_DURATION_MINUTES)

    /**
     * Detects classic SQL injection metacharacter/tautology/stacked-query patterns for
     * query-template auditing and security testing.
     */
    fun containsSqlInjectionPattern(raw: String): Boolean {
        if (raw.isBlank()) return false
        if (raw.indexOf('\u0000') >= 0) return true
        return SQL_INJECTION_SIGNATURES.any { it.containsMatchIn(raw) }
    }

    /**
     * Verifies that a SQLite / Room SQL template uses `?` positional or `:named` bind
     * parameters and contains no raw string-concatenated literals, comments, or stacked
     * `;` statements. Throws [SecurityException] if unsafe SQL is detected.
     */
    fun assertParameterizedSqlTemplate(sql: String): String {
        val trimmed = sql.trim()
        if (trimmed.isEmpty()) {
            throw SecurityException("SQL template cannot be empty")
        }
        // Disallow SQL comments and stacked statements
        if (trimmed.contains("--") || trimmed.contains("/*") || trimmed.contains("*/")) {
            throw SecurityException("SQL comments are forbidden in query templates")
        }
        val withoutTrailingSemicolon = trimmed.removeSuffix(";").trim()
        if (withoutTrailingSemicolon.contains(';')) {
            throw SecurityException("Stacked SQL statements are forbidden")
        }
        // Disallow quoted string literals inside dynamic WHERE/VALUES templates (must use ? or :param)
        if (withoutTrailingSemicolon.contains('\'') || withoutTrailingSemicolon.contains('"')) {
            throw SecurityException("Unparameterized string literals are forbidden; use '?' bind parameters")
        }
        if (containsSqlInjectionPattern(withoutTrailingSemicolon)) {
            throw SecurityException("Unsafe SQL injection pattern detected in template")
        }
        return trimmed
    }
}
