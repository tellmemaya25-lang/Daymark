package com.sabihon.todo.data.ai

/**
 * The Anthropic Messages wire format, as data.
 *
 * Deliberately free of Android, `HttpsURLConnection` and kotlinx-serialization, so every
 * decision here is compiled by `tools/typecheck.py` and executed by the JVM tests. The
 * transport that actually opens a socket (`ClaudeAiTransport`) stays thin enough to be
 * reviewed by eye, and this file carries the parts that can silently be *wrong*:
 *
 *  - **escaping.** The request body is assembled by hand, so a title containing a quote or
 *    a newline must not be able to break the JSON. [escape] is tested against every
 *    control character and both surrogate halves.
 *  - **text extraction.** Current Claude models emit `thinking` blocks *before* the answer
 *    block in the same `content` array. Concatenating every block would prepend the
 *    model's reasoning to the JSON we are about to parse, so [textFromBlocks] keeps only
 *    `text` blocks — this is not hypothetical, it is the default for the 5.x generation.
 *  - **fence stripping.** Unlike Gemini, the Messages API has no JSON response mode: the
 *    model can wrap its answer in ```json fences or a sentence of prose. [extractJsonObject]
 *    finds the first *complete* object, so a fenced or chatty reply still parses.
 */
object ClaudeMessages {

    const val ENDPOINT = "https://api.anthropic.com/v1/messages"

    /** Pinned API version; Anthropic requires the header on every request. */
    const val API_VERSION = "2023-06-01"

    /**
     * Cheapest current model, and the doc's own recommendation for "classification,
     * extraction and routing" - which is exactly what this app asks a model to do.
     */
    const val DEFAULT_MODEL = "claude-haiku-5-5"

    /** Answer budget. The replies here are a handful of short JSON objects. */
    const val MAX_TOKENS = 1_024

    /** Longest model id worth sending; anything longer is a paste accident. */
    const val MAX_MODEL_ID_CHARS = 64

    /**
     * Reinforces the instruction every prompt in this package already ends with. Claude has
     * no `responseMimeType`, so the system turn is the only place that can say "JSON only".
     */
    const val SYSTEM_PREAMBLE =
        "You are a terse assistant inside an Android todo app. Reply with a single JSON " +
            "object and nothing else: no prose, no markdown, no code fences."

    /**
     * Model ids to try, in order.
     *
     * Anthropic retires model ids on a schedule - the 3.x generation is already gone - so a
     * single hard-coded constant would eventually 404 every request. The first entry is the
     * user's override (Admin > AI & Config), then the current default, then two older ids
     * that are still served. Only a 404 advances to the next candidate; see
     * [isRetryableModelError].
     */
    fun modelCandidates(preferred: String?): List<String> {
        val explicit = preferred
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_MODEL_ID_CHARS }
        return (listOfNotNull(explicit) + listOf(DEFAULT_MODEL, "claude-sonnet-5-5", "claude-haiku-4-5"))
            .distinct()
    }

    /** Headers for one Messages call. The key is never logged or persisted by this file. */
    fun headers(apiKey: String): Map<String, String> = mapOf(
        "x-api-key" to apiKey.trim(),
        "anthropic-version" to API_VERSION,
        "content-type" to "application/json; charset=UTF-8",
        "accept" to "application/json"
    )

    /** The full request body for a single-turn prompt. */
    fun requestBody(
        model: String,
        prompt: String,
        maxTokens: Int = MAX_TOKENS
    ): String {
        val safeModel = model.trim().ifEmpty { DEFAULT_MODEL }
        val budget = maxTokens.coerceIn(64, 8_192)
        return buildString(prompt.length + 256) {
            append('{')
            append("\"model\":\"").append(escape(safeModel)).append("\",")
            append("\"max_tokens\":").append(budget).append(',')
            append("\"system\":\"").append(escape(SYSTEM_PREAMBLE)).append("\",")
            append("\"messages\":[{\"role\":\"user\",\"content\":\"")
            append(escape(prompt))
            append("\"}]}")
        }
    }

    /**
     * Escapes a Kotlin string into a JSON string literal (without the surrounding quotes).
     *
     * Two cases exist because a Kotlin `String` can hold things JSON cannot:
     *  - control characters below U+0020 must be escaped, or the body is invalid JSON;
     *  - a **lone surrogate** cannot be encoded as UTF-8 at all. Sending one would throw in
     *    the HTTP layer or produce a body the server rejects, so each unpaired half becomes
     *    U+FFFD. A well-formed pair passes through untouched.
     */
    fun escape(value: String): String {
        val out = StringBuilder(value.length + 16)
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch == '\b' -> out.append("\\b")
                ch == '\u000C' -> out.append("\\f")
                ch.code < 0x20 -> out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                ch.isHighSurrogate() -> {
                    val next = value.getOrNull(index + 1)
                    if (next != null && next.isLowSurrogate()) {
                        out.append(ch).append(next)
                        index++
                    } else {
                        out.append('\uFFFD')
                    }
                }
                ch.isLowSurrogate() -> out.append('\uFFFD')
                else -> out.append(ch)
            }
            index++
        }
        return out.toString()
    }

    /** One `content` block, reduced to the two fields that matter. */
    data class Block(val type: String, val text: String?)

    /**
     * The answer text, from the `text` blocks only.
     *
     * `thinking` and `redacted_thinking` blocks are dropped: they carry the model's
     * reasoning, and prepending them to the JSON would make every downstream parse fail in
     * a way that looks like the model misbehaved.
     */
    fun textFromBlocks(blocks: List<Block>): String? {
        val text = blocks
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("\n")
            .trim()
        return text.ifEmpty { null }
    }

    /**
     * The first complete JSON object in [raw], or null.
     *
     * Brace-counted rather than "substring from the first `{` to the last `}`": truncated
     * output (the model hit `max_tokens`) and trailing commentary both produce a slice that
     * looks plausible and then fails to parse. Returning null instead lets the caller treat
     * it as a failed attempt and fall back to on-device rules.
     */
    fun extractJsonObject(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val start = raw.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (index in start until raw.length) {
            val ch = raw[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return raw.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    /** True when the response was cut off by [MAX_TOKENS], so its JSON is incomplete. */
    fun isTruncated(stopReason: String?): Boolean = stopReason == "max_tokens"

    /** A 404 means this model id is retired: try the next candidate, not "give up". */
    fun isRetryableModelError(httpCode: Int, body: String): Boolean {
        if (httpCode == 404) return true
        val lower = body.lowercase()
        return "not_found_error" in lower && "model" in lower
    }

    /** Bad key. Retrying other models would just spend more failed requests. */
    fun isAuthError(httpCode: Int): Boolean = httpCode == 401 || httpCode == 403

    /** Message shown to the user when the fallback itself fails. */
    fun friendlyError(httpCode: Int, body: String): String {
        val snippet = body.trim().take(160)
        return when (httpCode) {
            401, 403 ->
                "Claude rejected the API key ($httpCode). Update it in Admin > AI & Config."
            404 ->
                "Claude model unavailable ($httpCode). Set a model id in Admin > AI & Config."
            429 ->
                "Claude rate or credit limit reached ($httpCode)."
            in 500..599 ->
                "Claude is temporarily unavailable ($httpCode)."
            400 ->
                "Claude rejected the request ($httpCode): $snippet"
            else ->
                "Claude request failed ($httpCode): $snippet"
        }
    }
}
