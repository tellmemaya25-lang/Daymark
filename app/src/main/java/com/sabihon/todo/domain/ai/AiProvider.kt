package com.sabihon.todo.domain.ai

/** A cloud model provider this app can talk to. */
enum class AiProvider(val label: String) {
    /** The default: Google AI Studio REST, the Firebase AI SDK, or both. */
    GEMINI("Gemini"),

    /** Anthropic Messages API, used when Gemini is not configured or fails. */
    CLAUDE("Claude")
}

/**
 * Which provider the user wants to lead a request.
 *
 * Lives in `domain` rather than `data` because it is a user preference that the datastore
 * persists - `core.datastore` must not have to depend on the network layer to name a
 * setting.
 */
enum class AiProviderPreference(val label: String, val description: String) {
    /** Gemini first, Claude only if Gemini cannot answer. The default. */
    AUTO("Automatic", "Gemini first, Claude as backup"),

    /** Gemini only, even when a Claude key is configured. */
    GEMINI("Gemini only", "Never fall back to Claude"),

    /** Claude first, Gemini only if Claude fails. */
    CLAUDE("Claude first", "Use Claude, Gemini as backup");

    companion object {
        /** Anything unrecognised resolves to [AUTO], never to "cloud AI off". */
        fun fromName(value: String?): AiProviderPreference =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}
