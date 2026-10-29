package com.sabihon.todo

import com.sabihon.todo.data.ai.AiProviderPolicy
import com.sabihon.todo.domain.ai.AiProvider
import com.sabihon.todo.domain.ai.AiProviderPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which provider answers a request, and what happens to a pasted key on its way in.
 *
 * The ordering rules exist because the two failure modes are opposite: attempting Claude
 * without a key makes every AI call fail twice, and giving up on Gemini because the Claude
 * preference is set turns a working setup into an offline one.
 */
class AiProviderPolicyTest {

    private val gemini = AiProvider.GEMINI
    private val claude = AiProvider.CLAUDE

    /* ------------------------------- the chain ------------------------------- */

    @Test
    fun `automatic means gemini first, claude as backup`() {
        assertEquals(
            listOf(gemini, claude),
            AiProviderPolicy.chain(AiProviderPreference.AUTO, geminiUsable = true, hasClaudeKey = true)
        )
    }

    /** The common case: no Claude key, so no fallback attempt and no wasted round trip. */
    @Test
    fun `without a claude key the chain is gemini alone`() {
        assertEquals(
            listOf(gemini),
            AiProviderPolicy.chain(AiProviderPreference.AUTO, geminiUsable = true, hasClaudeKey = false)
        )
        assertEquals(
            listOf(gemini),
            AiProviderPolicy.chain(AiProviderPreference.GEMINI, geminiUsable = true, hasClaudeKey = true)
        )
    }

    @Test
    fun `claude first reverses the order but keeps gemini as a backstop`() {
        assertEquals(
            listOf(claude, gemini),
            AiProviderPolicy.chain(AiProviderPreference.CLAUDE, geminiUsable = true, hasClaudeKey = true)
        )
    }

    @Test
    fun `asking for claude without a key must not disable cloud AI`() {
        assertEquals(
            listOf(gemini),
            AiProviderPolicy.chain(AiProviderPreference.CLAUDE, geminiUsable = true, hasClaudeKey = false)
        )
    }

    @Test
    fun `an empty chain means use the on-device rules`() {
        assertTrue(
            AiProviderPolicy.chain(AiProviderPreference.AUTO, geminiUsable = false, hasClaudeKey = false).isEmpty()
        )
        assertEquals(
            listOf(claude),
            AiProviderPolicy.chain(AiProviderPreference.AUTO, geminiUsable = false, hasClaudeKey = true)
        )
    }

    /* ------------------------------ key handling ----------------------------- */

    @Test
    fun `a clean key is left alone`() {
        val key = "sk-ant-api03-AbC123"
        assertEquals(key, AiProviderPolicy.normalizeApiKey(key))
    }

    /**
     * Every one of these shapes has arrived from a password manager, a chat app or a
     * `local.properties` line. Trimming only spaces leaves an invisible defect that fails
     * authentication with no explanation.
     */
    @Test
    fun `pasted decoration is stripped`() {
        val key = "sk-ant-api03-AbC123"
        listOf(
            "  $key  ",
            "\n$key\n",
            "\"$key\"",
            "'$key'",
            "\"$key\" ",
            "Bearer $key",
            "bearer $key",
            "CLAUDE_API_KEY=$key",
            "export CLAUDE_API_KEY=\"$key\"",
            "claude_api_key=$key",
            "sk-ant-api03-AbC\n123",
            "$key\u200B",
            "sk-ant-api03-AbC123\uFEFF"
        ).forEach { raw ->
            assertEquals("failed to clean <$raw>", key, AiProviderPolicy.normalizeApiKey(raw))
        }
    }

    @Test
    fun `a missing key normalizes to empty rather than to junk`() {
        assertEquals("", AiProviderPolicy.normalizeApiKey(""))
        assertEquals("", AiProviderPolicy.normalizeApiKey("   \n\t "))
        assertEquals("", AiProviderPolicy.normalizeApiKey("\"\""))
    }

    @Test
    fun `the prefix check is a hint, not a gate`() {
        assertTrue(AiProviderPolicy.looksLikeClaudeKey("sk-ant-api03-x"))
        assertTrue(AiProviderPolicy.looksLikeClaudeKey("  \"sk-ant-api03-x\" "))
        assertFalse(AiProviderPolicy.looksLikeClaudeKey("AIzaSyGeminiKey"))
        assertFalse(AiProviderPolicy.looksLikeClaudeKey(""))
    }

    /* -------------------------------- the copy ------------------------------- */

    @Test
    fun `the settings summary tells the truth about the current setup`() {
        val withKey = AiProviderPolicy.describe(AiProviderPreference.AUTO, hasClaudeKey = true)
        assertEquals("Gemini first, Claude as backup", withKey)

        val withoutKey = AiProviderPolicy.describe(AiProviderPreference.AUTO, hasClaudeKey = false)
        assertTrue(withoutKey.contains("Claude key"))

        val wantsClaudeButHasNone = AiProviderPolicy.describe(AiProviderPreference.CLAUDE, hasClaudeKey = false)
        assertTrue(wantsClaudeButHasNone.contains("Gemini"))
    }

    // ---- who may serve today (the "AI not working" bug, 2026-10-29) ------------------

    private fun budgets(vararg spent: Pair<AiProvider, Int>): Map<AiProvider, AiProviderPolicy.ProviderBudget> =
        spent.associate { (provider, calls) ->
            provider to AiProviderPolicy.ProviderBudget(calls = calls, estimatedTokens = calls * 100)
        }

    @Test
    fun `a spent gemini day hands the request to claude instead of switching ai off`() {
        val available = AiProviderPolicy.providersWithRoom(
            preference = AiProviderPreference.AUTO,
            hasClaudeKey = true,
            budgets = budgets(AiProvider.GEMINI to 25),
            dailyCallCap = 25,
            dailyTokenCap = 40_000,
            estimatedTokens = 500
        )

        assertEquals(listOf(AiProvider.CLAUDE), available)
    }

    @Test
    fun `a spent gemini day still blocks when no claude key is configured`() {
        val available = AiProviderPolicy.providersWithRoom(
            preference = AiProviderPreference.AUTO,
            hasClaudeKey = false,
            budgets = budgets(AiProvider.GEMINI to 25),
            dailyCallCap = 25,
            dailyTokenCap = 40_000,
            estimatedTokens = 500
        )

        assertTrue(available.isEmpty())
    }

    @Test
    fun `gemini only means exactly that, even with a claude key waiting`() {
        val available = AiProviderPolicy.providersWithRoom(
            preference = AiProviderPreference.GEMINI,
            hasClaudeKey = true,
            budgets = budgets(AiProvider.GEMINI to 25),
            dailyCallCap = 25,
            dailyTokenCap = 40_000,
            estimatedTokens = 500
        )

        assertTrue(available.isEmpty())
    }

    @Test
    fun `claude first spends claude before touching the free gemini allowance`() {
        val available = AiProviderPolicy.providersWithRoom(
            preference = AiProviderPreference.CLAUDE,
            hasClaudeKey = true,
            budgets = budgets(AiProvider.GEMINI to 25),
            dailyCallCap = 25,
            dailyTokenCap = 40_000,
            estimatedTokens = 500
        )

        assertEquals(listOf(AiProvider.CLAUDE), available)
    }

    @Test
    fun `claude spending its own budget leaves gemini untouched`() {
        val available = AiProviderPolicy.providersWithRoom(
            preference = AiProviderPreference.AUTO,
            hasClaudeKey = true,
            budgets = budgets(AiProvider.CLAUDE to 25),
            dailyCallCap = 25,
            dailyTokenCap = 40_000,
            estimatedTokens = 500
        )

        assertEquals(listOf(AiProvider.GEMINI), available)
    }

    @Test
    fun `both providers spent means nothing can serve`() {
        val available = AiProviderPolicy.providersWithRoom(
            preference = AiProviderPreference.CLAUDE,
            hasClaudeKey = true,
            budgets = budgets(AiProvider.GEMINI to 25, AiProvider.CLAUDE to 25),
            dailyCallCap = 25,
            dailyTokenCap = 40_000,
            estimatedTokens = 500
        )

        assertTrue(available.isEmpty())
    }

    @Test
    fun `an untouched day offers both providers in preference order`() {
        assertEquals(
            listOf(AiProvider.GEMINI, AiProvider.CLAUDE),
            AiProviderPolicy.providersWithRoom(
                preference = AiProviderPreference.AUTO,
                hasClaudeKey = true,
                budgets = emptyMap(),
                dailyCallCap = 25,
                dailyTokenCap = 40_000,
                estimatedTokens = 500
            )
        )
        assertEquals(
            listOf(AiProvider.CLAUDE, AiProvider.GEMINI),
            AiProviderPolicy.providersWithRoom(
                preference = AiProviderPreference.CLAUDE,
                hasClaudeKey = true,
                budgets = emptyMap(),
                dailyCallCap = 25,
                dailyTokenCap = 40_000,
                estimatedTokens = 500
            )
        )
    }

    @Test
    fun `the token budget closes a provider before its call count does`() {
        val available = AiProviderPolicy.providersWithRoom(
            preference = AiProviderPreference.AUTO,
            hasClaudeKey = false,
            budgets = mapOf(
                AiProvider.GEMINI to AiProviderPolicy.ProviderBudget(calls = 2, estimatedTokens = 39_800)
            ),
            dailyCallCap = 25,
            dailyTokenCap = 40_000,
            estimatedTokens = 500
        )

        assertTrue(available.isEmpty())
    }

    @Test
    fun `an admin cap of zero is treated as one, never as unlimited`() {
        val available = AiProviderPolicy.providersWithRoom(
            preference = AiProviderPreference.AUTO,
            hasClaudeKey = false,
            budgets = budgets(AiProvider.GEMINI to 1),
            dailyCallCap = 0,
            dailyTokenCap = 40_000,
            estimatedTokens = 500
        )

        assertTrue(available.isEmpty())
    }
}
