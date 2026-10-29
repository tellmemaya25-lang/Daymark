package com.sabihon.todo.data.ai

import com.sabihon.todo.domain.ai.AiProvider
import com.sabihon.todo.domain.ai.AiProviderPreference
import java.util.Locale

/**
 * Which providers to try, and in what order.
 *
 * The rule that matters most: **Claude is never attempted without a key.** Cloud AI is
 * allowed to be on with only Gemini configured (Firebase AI works without a pasted key),
 * and in that state an extra fallback attempt would cost a failed HTTPS round trip on
 * every single request for no benefit.
 *
 * Pure and framework-free so the ordering is decided in a test rather than discovered in
 * an ANR report.
 */
object AiProviderPolicy {

    /** Characters that never appear in a real key and are usually paste artefacts. */
    private val INVISIBLE = setOf('\u200B', '\u200C', '\u200D', '\uFEFF')

    fun chain(
        preference: AiProviderPreference,
        geminiUsable: Boolean,
        hasClaudeKey: Boolean
    ): List<AiProvider> {
        val gemini = if (geminiUsable) listOf(AiProvider.GEMINI) else emptyList()
        val claude = if (hasClaudeKey) listOf(AiProvider.CLAUDE) else emptyList()

        return when (preference) {
            AiProviderPreference.AUTO -> (gemini + claude).distinct()
            AiProviderPreference.GEMINI -> gemini
            // A "Claude first" preference with no key must not disable cloud AI outright,
            // so Gemini stays as the backstop rather than the chain collapsing to empty.
            AiProviderPreference.CLAUDE -> (claude + gemini).distinct()
        }
    }

    /** How much of today's allowance one provider has already spent. */
    data class ProviderBudget(val calls: Int, val estimatedTokens: Int) {
        companion object {
            val EMPTY = ProviderBudget(calls = 0, estimatedTokens = 0)
        }
    }

    /**
     * The providers that may still serve today, in the user's preferred order.
     *
     * [chain] answers "what is configured"; this answers "what is configured *and* still has
     * budget". They are separate because the failure the user hits is one spent day on one
     * provider: a free Gemini cap must route the request to a paid Claude key rather than
     * switch Smart AI off for the rest of the day.
     *
     * @param budgets today's usage per provider. Missing entries mean untouched.
     */
    fun providersWithRoom(
        preference: AiProviderPreference,
        hasClaudeKey: Boolean,
        budgets: Map<AiProvider, ProviderBudget>,
        dailyCallCap: Int,
        dailyTokenCap: Int,
        estimatedTokens: Int
    ): List<AiProvider> {
        val callCap = dailyCallCap.coerceIn(1, MAX_DAILY_CALLS)
        val callsNeeded = estimatedTokens.coerceAtLeast(1)

        fun hasRoom(provider: AiProvider): Boolean {
            val used = budgets[provider] ?: ProviderBudget.EMPTY
            return used.calls < callCap && used.estimatedTokens + callsNeeded <= dailyTokenCap
        }

        return chain(
            preference = preference,
            geminiUsable = true,
            hasClaudeKey = hasClaudeKey
        ).filter(::hasRoom)
    }

    /** Matches the ceiling the admin config is clamped to. */
    const val MAX_DAILY_CALLS = 10_000

    /**
     * Cleans a pasted key.
     *
     * Keys arrive from password managers, chat apps and `key=value` lines in shell output,
     * so the surrounding quotes, a `Bearer ` prefix, a `CLAUDE_API_KEY=` label and stray
     * newlines all survive a plain copy. Trimming only spaces would leave a key that fails
     * authentication for a reason the user cannot see.
     */
    fun normalizeApiKey(raw: String): String {
        var value = raw.trim().removeSurrounding("\"").removeSurrounding("'").trim()

        // "CLAUDE_API_KEY=sk-ant-..." or "export CLAUDE_API_KEY=..."
        val assignment = value.indexOf('=')
        if (assignment in 1 until value.length - 1 &&
            value.take(assignment).trim().uppercase(Locale.ROOT).endsWith("API_KEY")
        ) {
            value = value.substring(assignment + 1).trim().removeSurrounding("\"").removeSurrounding("'").trim()
        }

        if (value.startsWith("Bearer ", ignoreCase = true)) {
            value = value.substring(7).trim()
        }

        return value.filterNot { it.isWhitespace() || it in INVISIBLE }
    }

    /**
     * A cheap shape check, used for a UI hint and never to reject a key.
     *
     * Anthropic keys are prefixed `sk-ant-`, but a key is the user's business: a future
     * prefix change must not lock them out of their own fallback.
     */
    fun looksLikeClaudeKey(key: String): Boolean =
        normalizeApiKey(key).startsWith("sk-ant-", ignoreCase = true)

    /** The one-line summary Settings shows, e.g. "Gemini first, Claude as backup". */
    fun describe(
        preference: AiProviderPreference,
        hasClaudeKey: Boolean
    ): String = when {
        preference == AiProviderPreference.AUTO && !hasClaudeKey ->
            "Gemini only \u2014 add a Claude key in Admin to enable the backup"
        preference == AiProviderPreference.CLAUDE && !hasClaudeKey ->
            "Claude first, but no Claude key is set \u2014 using Gemini"
        else -> preference.description
    }
}
