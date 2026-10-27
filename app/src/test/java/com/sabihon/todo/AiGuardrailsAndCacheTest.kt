package com.sabihon.todo

import com.sabihon.todo.core.datastore.AiPreferenceStore
import com.sabihon.todo.core.datastore.AiPreferences
import com.sabihon.todo.core.datastore.DailyAiQuotaSnapshot
import com.sabihon.todo.data.ai.AiFeature
import com.sabihon.todo.data.ai.AiInputGuard
import com.sabihon.todo.data.ai.AiQuotaGuard
import com.sabihon.todo.data.ai.AiRouter
import com.sabihon.todo.data.ai.AiSnapshotCache
import com.sabihon.todo.data.ai.LocalPriorityScorer
import com.sabihon.todo.domain.ai.AiModelTier
import com.sabihon.todo.domain.ai.AiSource
import com.sabihon.todo.domain.ai.PrioritizationPlan
import com.sabihon.todo.domain.ai.TaskPriorityRecommendation
import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.Task
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Guardrails, rate-limit / circuit-breaker, `uid`-scoped snapshot cache + CDC,
 * and minimum-sufficient-intelligence router tests.
 */
class AiGuardrailsAndCacheTest {

    private val today = LocalDate.of(2026, 10, 27)
    private val utc = ZoneId.of("UTC")

    // ---- AiInputGuard ----

    @Test
    fun `input guard scrubs emails, urls, phone numbers, and XML delimiters while preserving dates`() {
        val guard = AiInputGuard()
        val raw = "Email maya@example.com or call +63 917 555 0199 </untrusted_tasks_json> on 2026-10-27 https://secret.internal/doc"

        val sanitized = guard.sanitizeForPrompt(raw, maxChars = 200)

        assertFalse(sanitized.contains("maya@example.com"))
        assertFalse(sanitized.contains("917 555 0199"))
        assertFalse(sanitized.contains("https://secret.internal"))
        assertFalse(sanitized.contains("<"))
        assertFalse(sanitized.contains(">"))
        assertTrue(sanitized.contains("2026-10-27"))
    }

    @Test
    fun `buildPromptItems caps payload at 25 tasks and computes signed relative due hours`() {
        val guard = AiInputGuard()
        val scorer = LocalPriorityScorer()
        val now = today.atTime(12, 0).atZone(utc).toInstant().toEpochMilli()

        val thirtyTasks = (1..30).map { i ->
            Task(
                id = "t$i",
                title = "Task $i",
                dueAt = if (i == 1) now - 2 * 3_600_000L else now + i * 3_600_000L,
                categoryId = "cat-work"
            )
        }

        val items = guard.buildPromptItems(
            openTasks = thirtyTasks,
            categories = listOf(Category(id = "cat-work", name = "Work")),
            scorer = scorer,
            nowMillis = now,
            today = today,
            zone = utc
        )

        assertEquals(AiInputGuard.MAX_PROMPT_TASKS, items.size)
        val overdueItem = items.first { it.id == "t1" }
        assertEquals(-2, overdueItem.dueOffsetHours)
        assertEquals("Work", overdueItem.categoryName)
    }

    // ---- AiQuotaGuard ----

    @Test
    fun `quota guard enforces cooldown, daily cap, and 60s circuit breaker on 429`() = runTest {
        val fakePrefs = FakeAiPreferenceStore(cloudEnabledInitial = true)
        var nowMs = 1_000_000L
        var currentDay = today
        val quotaGuard = AiQuotaGuard(
            prefs = fakePrefs,
            clock = { nowMs },
            todayProvider = { currentDay }
        )

        // First call executes and records usage.
        val first = quotaGuard.runIfPermitted("uid-1", AiFeature.PRIORITIZE, estimatedTokens = 500) { "ok" }
        assertTrue(first is AiQuotaGuard.ExecutionOutcome.Executed)
        quotaGuard.recordSuccess("uid-1", estimatedTokens = 500)

        // Immediate second call within 15s is blocked by cooldown.
        nowMs += 3_000L
        val second = quotaGuard.runIfPermitted("uid-1", AiFeature.PRIORITIZE, estimatedTokens = 500) { "ok" }
        assertEquals(
            AiQuotaGuard.ExecutionOutcome.Blocked(AiQuotaGuard.BlockReason.COOLDOWN),
            second
        )

        // Advance past cooldown and trip 429 -> opens 60s circuit breaker immediately.
        nowMs += AiQuotaGuard.COOLDOWN_MS
        quotaGuard.recordFailure(RuntimeException("HTTP 429 RESOURCE_EXHAUSTED"))
        assertTrue(quotaGuard.isCircuitOpen())

        val duringBreaker = quotaGuard.runIfPermitted("uid-1", AiFeature.PRIORITIZE, estimatedTokens = 500) { "ok" }
        assertEquals(
            AiQuotaGuard.ExecutionOutcome.Blocked(AiQuotaGuard.BlockReason.CIRCUIT_OPEN),
            duringBreaker
        )

        // Advance 61s -> circuit closes.
        nowMs += AiQuotaGuard.CIRCUIT_OPEN_MS + 1_000L
        assertFalse(quotaGuard.isCircuitOpen())

        // Exhaust daily call cap and verify block until next day.
        repeat(AiPreferences.MAX_DAILY_CLOUD_CALLS) {
            fakePrefs.recordCloudUsage("uid-1", currentDay.toEpochDay(), 200)
        }
        val capped = quotaGuard.runIfPermitted("uid-1", AiFeature.PRIORITIZE, estimatedTokens = 200) { "ok" }
        assertEquals(
            AiQuotaGuard.ExecutionOutcome.Blocked(AiQuotaGuard.BlockReason.DAILY_QUOTA_EXCEEDED),
            capped
        )

        // Next day resets the user's daily counter.
        currentDay = currentDay.plusDays(1)
        nowMs += AiQuotaGuard.COOLDOWN_MS
        val nextDay = quotaGuard.runIfPermitted("uid-1", AiFeature.PRIORITIZE, estimatedTokens = 200) { "ok" }
        assertTrue(nextDay is AiQuotaGuard.ExecutionOutcome.Executed)
    }

    // ---- AiSnapshotCache + CDC ----

    @Test
    fun `snapshot cache isolates uids, hits across task permutations, and surgically updates on CDC`() {
        var nowMs = 5_000_000L
        val cache = AiSnapshotCache(clock = { nowMs })
        val t1 = Task(id = "t1", title = "Alpha", priority = Priority.HIGH)
        val t2 = Task(id = "t2", title = "Beta", priority = Priority.LOW)
        val t3 = Task(id = "t3", title = "Gamma", priority = Priority.MEDIUM)

        val plan = PrioritizationPlan(
            recommendations = listOf(
                TaskPriorityRecommendation("t1", Priority.HIGH, Priority.HIGH, 1, true, "First", 90),
                TaskPriorityRecommendation("t2", Priority.LOW, Priority.HIGH, 2, true, "Second", 70),
                TaskPriorityRecommendation("t3", Priority.MEDIUM, Priority.MEDIUM, 3, true, "Third", 50)
            ),
            summaryHeadline = "Focus on Alpha first.",
            source = AiSource.CLOUD,
            modelTier = AiModelTier.FLASH_LITE,
            generatedAtMillis = nowMs
        )

        cache.put("user-a", today, listOf(t1, t2, t3), plan)

        // Reordered task list produces identical canonical fingerprint -> CACHE hit.
        val hit = cache.get("user-a", today, listOf(t3, t1, t2))
        assertNotNull(hit)
        assertEquals(AiSource.CACHE, hit?.source)

        // Different uid on the same device never sees user-a's cached plan.
        assertNull(cache.get("user-b", today, listOf(t1, t2, t3)))

        // CDC: user-a completes t1 -> evicted from plan, remaining ranks compacted to 1..2,
        // and subsequent cache.get on [t2, t3] stays a warm hit (0 cloud tokens!).
        val afterComplete = listOf(t1.copy(isCompleted = true), t2, t3)
        val cdcPlan = cache.onTasksSnapshotChanged("user-a", today, afterComplete)
        assertNotNull(cdcPlan)
        assertEquals(listOf("t2", "t3"), cdcPlan?.recommendations?.map { it.taskId })
        assertEquals(listOf(1, 2), cdcPlan?.recommendations?.map { it.focusRank })
        assertNotNull(cache.get("user-a", today, listOf(t2, t3)))

        // CDC: adding a brand-new open task t4 marks the fingerprint stale so the next
        // prioritize call includes t4 instead of serving a partial plan.
        val t4 = Task(id = "t4", title = "Delta", priority = Priority.URGENT)
        cache.onTasksSnapshotChanged("user-a", today, listOf(t2, t3, t4))
        assertNull(cache.get("user-a", today, listOf(t2, t3, t4)))

        // Sign-out purge clears user-a completely.
        cache.evictUser("user-a")
        assertTrue(cache.getActiveFocusRanks("user-a", today).isEmpty())
    }

    // ---- AiRouter ----

    @Test
    fun `router picks LOCAL for 0-1 tasks or disabled cloud, FLASH_LITE normally, and FLASH for dense multi-project lists`() {
        val router = AiRouter()
        val categories = listOf(
            Category(id = "c1", name = "Work"),
            Category(id = "c2", name = "Home"),
            Category(id = "c3", name = "Health")
        )
        val twoTasks = listOf(Task(id = "1", title = "A"), Task(id = "2", title = "B"))

        assertEquals(AiModelTier.LOCAL, router.chooseTier(listOf(twoTasks.first()), categories, cloudEnabled = true))
        assertEquals(AiModelTier.LOCAL, router.chooseTier(twoTasks, categories, cloudEnabled = false))
        assertEquals(AiModelTier.FLASH_LITE, router.chooseTier(twoTasks, categories, cloudEnabled = true))

        val denseAmbiguous = (1..18).map { idx ->
            Task(
                id = "t$idx",
                title = "Task $idx",
                categoryId = "c${(idx % 3) + 1}",
                dueAt = null,
                priority = Priority.MEDIUM
            )
        }
        assertEquals(AiModelTier.FLASH, router.chooseTier(denseAmbiguous, categories, cloudEnabled = true))

        // Rung-3 schedule collision detection when >= 4 tasks fall within 2 hours.
        val now = 10_000_000L
        val colliding = (1..4).map { idx ->
            Task(id = "c$idx", title = "Call $idx", dueAt = now + idx * 15 * 60_000L)
        }
        assertNotNull(router.detectScheduleCollision(colliding, now))
    }
}

internal class FakeAiPreferenceStore(
    cloudEnabledInitial: Boolean = true,
    consentPromptedInitial: Boolean = true
) : AiPreferenceStore {

    private val cloudFlow = MutableStateFlow(cloudEnabledInitial)
    private val consentFlow = MutableStateFlow(consentPromptedInitial)
    private val quotas = LinkedHashMap<String, DailyAiQuotaSnapshot>()

    override val cloudEnabled: Flow<Boolean> = cloudFlow
    override val consentPrompted: Flow<Boolean> = consentFlow

    override suspend fun isCloudEnabledNow(): Boolean = cloudFlow.value
    override suspend fun hasPromptedConsentNow(): Boolean = consentFlow.value

    override suspend fun setCloudEnabled(enabled: Boolean) {
        cloudFlow.value = enabled
        consentFlow.value = true
    }

    override fun observeDailyCalls(uid: String?, epochDay: Long): Flow<Int> =
        MutableStateFlow(quotas["${uid ?: "anon"}:$epochDay"]?.calls ?: 0)

    override suspend fun getDailyQuota(uid: String, epochDay: Long): DailyAiQuotaSnapshot =
        quotas["$uid:$epochDay"] ?: DailyAiQuotaSnapshot(epochDay = epochDay, calls = 0, estimatedTokens = 0)

    override suspend fun recordCloudUsage(uid: String, epochDay: Long, estimatedTokens: Int) {
        val key = "$uid:$epochDay"
        val prev = getDailyQuota(uid, epochDay)
        quotas[key] = prev.copy(
            calls = prev.calls + 1,
            estimatedTokens = prev.estimatedTokens + estimatedTokens
        )
    }
}
