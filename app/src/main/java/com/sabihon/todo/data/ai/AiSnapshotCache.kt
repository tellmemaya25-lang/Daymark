package com.sabihon.todo.data.ai

import com.sabihon.todo.domain.ai.AiSource
import com.sabihon.todo.domain.ai.PrioritizationPlan
import com.sabihon.todo.domain.ai.TaskSuggestionsResult
import com.sabihon.todo.domain.model.Task
import java.security.MessageDigest
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `uid`-namespaced snapshot & semantic cache with real-time CDC surgical eviction
 * (`docs/02-daymark-ai-system-design.md` §4 & §5):
 *
 *  1. **Prioritization Snapshot Cache**: Exact canonical task-set SHA-256 hash (`30 min` TTL)
 *     plus surgical CDC eviction when tasks are completed or deleted.
 *  2. **Sub-task Semantic Cache (`Phase 3`)**: Normalized token-set Jaccard similarity
 *     (`>= 0.85`, `24 h` TTL, bounded LRU of `40` entries) so reworded titles hit in `< 0.1 ms`.
 *  3. **Contextual Next-Task Cache (`Phase 4`)**: Exact project + open/completed context
 *     SHA-256 hash (`2 h` TTL), invalidated automatically when tasks change.
 *  4. **Multi-Account Isolation**: Every partition is scoped by `uid` and wiped on `signOut()`.
 */
@Singleton
class AiSnapshotCache internal constructor(
    private val clock: () -> Long
) {

    @Inject
    constructor() : this(clock = System::currentTimeMillis)

    private data class CachedEntry(
        val uid: String,
        val epochDay: Long,
        val fingerprint: String,
        val storedAtMillis: Long,
        val plan: PrioritizationPlan
    )

    private data class CachedSubTaskEntry(
        val uid: String,
        val tokens: Set<String>,
        val steps: List<String>,
        val storedAtMillis: Long
    )

    private data class CachedNextTasksEntry(
        val uid: String,
        val epochDay: Long,
        val fingerprint: String,
        val storedAtMillis: Long,
        val result: TaskSuggestionsResult
    )

    private val lock = Any()
    private val entriesByUid = LinkedHashMap<String, CachedEntry>(4, 0.75f, true)
    private val subTaskEntries = LinkedHashMap<String, CachedSubTaskEntry>(16, 0.75f, true)
    private val nextTaskEntries = LinkedHashMap<String, CachedNextTasksEntry>(8, 0.75f, true)

    fun computeFingerprint(
        uid: String,
        today: LocalDate,
        openTasks: List<Task>
    ): String {
        val canonical = openTasks
            .filter { !it.isCompleted && !it.isDeleted }
            .sortedBy { it.id }
            .joinToString("\n") { task ->
                val dueBucket = task.dueAt?.div(TEN_MINUTES_MS) ?: -1L
                val doneSubs = task.subTasks.count { it.isDone }
                "${task.id}|${task.title.trim().lowercase()}|${task.priority.name}|" +
                    "$dueBucket|$doneSubs/${task.subTasks.size}|${task.repeat.name}|${task.streak}"
            }
        val raw = "${uid.ifBlank { "anon" }}::${today.toEpochDay()}::$canonical"
        return sha256Hex(raw)
    }

    fun get(
        uid: String?,
        today: LocalDate,
        openTasks: List<Task>
    ): PrioritizationPlan? {
        val safeUid = uid?.takeIf { it.isNotBlank() } ?: return null
        val expectedFingerprint = computeFingerprint(safeUid, today, openTasks)
        val now = clock()

        synchronized(lock) {
            val entry = entriesByUid[safeUid] ?: return null
            val expired = entry.epochDay != today.toEpochDay() || (now - entry.storedAtMillis) > TTL_MS
            if (expired) {
                entriesByUid.remove(safeUid)
                return null
            }
            if (entry.fingerprint != expectedFingerprint) {
                return null
            }
            return entry.plan.copy(source = AiSource.CACHE)
        }
    }

    fun put(
        uid: String?,
        today: LocalDate,
        openTasks: List<Task>,
        plan: PrioritizationPlan
    ) {
        val safeUid = uid?.takeIf { it.isNotBlank() } ?: return
        val fingerprint = computeFingerprint(safeUid, today, openTasks)
        synchronized(lock) {
            entriesByUid[safeUid] = CachedEntry(
                uid = safeUid,
                epochDay = today.toEpochDay(),
                fingerprint = fingerprint,
                storedAtMillis = clock(),
                plan = plan
            )
            while (entriesByUid.size > MAX_CACHED_USERS) {
                val eldestKey = entriesByUid.keys.firstOrNull() ?: break
                entriesByUid.remove(eldestKey)
            }
        }
    }

    /**
     * Real-time CDC hook invoked whenever Firestore emits a fresh task snapshot.
     *
     *  - Removes tasks that were completed or soft-deleted and compacts `focusRank`.
     *  - Syncs `currentPriority` on surviving tasks so an applied priority change
     *    immediately stops showing as a pending diff.
     */
    fun onTasksSnapshotChanged(
        uid: String?,
        today: LocalDate,
        allTasks: List<Task>
    ): PrioritizationPlan? {
        val safeUid = uid?.takeIf { it.isNotBlank() } ?: return null
        val openTasks = allTasks.filter { !it.isCompleted && !it.isDeleted }
        val openById = openTasks.associateBy { it.id }

        synchronized(lock) {
            val current = entriesByUid[safeUid] ?: return null
            if (current.epochDay != today.toEpochDay()) {
                entriesByUid.remove(safeUid)
                return null
            }

            val updatedRecs = current.plan.recommendations
                .filter { it.taskId in openById }
                .sortedBy { it.focusRank }
                .mapIndexed { index, rec ->
                    val latestTask = openById.getValue(rec.taskId)
                    rec.copy(
                        currentPriority = latestTask.priority,
                        focusRank = index + 1,
                        isTopFocus = rec.isTopFocus && index < LocalPriorityScorer.MAX_TOP_FOCUS
                    )
                }

            val updatedPlan = current.plan.copy(recommendations = updatedRecs)
            val coveredAllOpen = updatedRecs.size == openTasks.size
            val nextFingerprint = if (coveredAllOpen) {
                computeFingerprint(safeUid, today, openTasks)
            } else {
                current.fingerprint
            }
            entriesByUid[safeUid] = current.copy(
                fingerprint = nextFingerprint,
                plan = updatedPlan
            )
            return updatedPlan
        }
    }

    /** Returns the active focus rank map for [uid], or empty if none is cached. */
    fun getActiveFocusRanks(uid: String?, today: LocalDate = LocalDate.now()): Map<String, Int> {
        val safeUid = uid?.takeIf { it.isNotBlank() } ?: return emptyMap()
        val now = clock()
        synchronized(lock) {
            val entry = entriesByUid[safeUid] ?: return emptyMap()
            if (entry.epochDay != today.toEpochDay() || (now - entry.storedAtMillis) > TTL_MS) {
                entriesByUid.remove(safeUid)
                return emptyMap()
            }
            return entry.plan.focusRankMap
        }
    }

    // ---- Phase 3: Semantic Sub-task Breakdown Cache (Token Jaccard >= 0.85) ----

    fun getSubTasks(
        uid: String?,
        title: String,
        description: String = ""
    ): List<String>? {
        val safeUid = uid?.takeIf { it.isNotBlank() } ?: "anon"
        val queryTokens = normalizeSemanticTokens("$title $description")
        if (queryTokens.isEmpty()) return null
        val now = clock()

        synchronized(lock) {
            val expiredKeys = mutableListOf<String>()
            var bestMatchKey: String? = null
            var bestSimilarity = 0.0

            for ((key, entry) in subTaskEntries) {
                if ((now - entry.storedAtMillis) > SUBTASK_TTL_MS) {
                    expiredKeys += key
                    continue
                }
                if (entry.uid != safeUid) continue
                val sim = jaccardSimilarity(queryTokens, entry.tokens)
                if (sim >= SUBTASK_JACCARD_THRESHOLD && sim > bestSimilarity) {
                    bestSimilarity = sim
                    bestMatchKey = key
                }
            }
            expiredKeys.forEach { subTaskEntries.remove(it) }

            return bestMatchKey?.let { subTaskEntries[it]?.steps }
        }
    }

    fun putSubTasks(
        uid: String?,
        title: String,
        description: String = "",
        steps: List<String>
    ) {
        if (steps.isEmpty()) return
        val safeUid = uid?.takeIf { it.isNotBlank() } ?: "anon"
        val tokens = normalizeSemanticTokens("$title $description")
        if (tokens.isEmpty()) return
        val cacheKey = "$safeUid::${tokens.sorted().joinToString(" ")}"

        synchronized(lock) {
            subTaskEntries[cacheKey] = CachedSubTaskEntry(
                uid = safeUid,
                tokens = tokens,
                steps = steps,
                storedAtMillis = clock()
            )
            while (subTaskEntries.size > MAX_CACHED_SUBTASK_ENTRIES) {
                val eldest = subTaskEntries.keys.firstOrNull() ?: break
                subTaskEntries.remove(eldest)
            }
        }
    }

    // ---- Phase 4: Contextual Next-Task Suggestions Cache ----

    fun computeNextTasksFingerprint(
        uid: String,
        today: LocalDate,
        targetCategoryId: String?,
        openTasks: List<Task>,
        completedTasks: List<Task>
    ): String {
        val openSignature = openTasks
            .filter { !it.isCompleted && !it.isDeleted }
            .sortedBy { it.id }
            .joinToString(",") { "${it.id}:${it.title.trim().lowercase()}" }
        val completedSignature = completedTasks
            .filter { it.isCompleted && !it.isDeleted }
            .sortedByDescending { it.completedAt ?: it.updatedAt }
            .take(8)
            .joinToString(",") { "${it.id}:${it.title.trim().lowercase()}" }
        val scope = targetCategoryId?.trim().orEmpty().ifBlank { "all" }
        val raw = "${uid.ifBlank { "anon" }}::$scope::${today.toEpochDay()}::$openSignature::$completedSignature"
        return sha256Hex(raw)
    }

    fun getNextTasks(
        uid: String?,
        today: LocalDate,
        targetCategoryId: String?,
        openTasks: List<Task>,
        completedTasks: List<Task>
    ): TaskSuggestionsResult? {
        val safeUid = uid?.takeIf { it.isNotBlank() } ?: return null
        val scopeKey = "$safeUid::${targetCategoryId?.trim().orEmpty().ifBlank { "all" }}"
        val expectedFingerprint = computeNextTasksFingerprint(
            uid = safeUid,
            today = today,
            targetCategoryId = targetCategoryId,
            openTasks = openTasks,
            completedTasks = completedTasks
        )
        val now = clock()

        synchronized(lock) {
            val entry = nextTaskEntries[scopeKey] ?: return null
            val expired = entry.epochDay != today.toEpochDay() ||
                (now - entry.storedAtMillis) > NEXT_TASKS_TTL_MS
            if (expired) {
                nextTaskEntries.remove(scopeKey)
                return null
            }
            if (entry.fingerprint != expectedFingerprint) {
                return null
            }
            return entry.result.copy(source = AiSource.CACHE)
        }
    }

    fun putNextTasks(
        uid: String?,
        today: LocalDate,
        targetCategoryId: String?,
        openTasks: List<Task>,
        completedTasks: List<Task>,
        result: TaskSuggestionsResult
    ) {
        val safeUid = uid?.takeIf { it.isNotBlank() } ?: return
        val scopeKey = "$safeUid::${targetCategoryId?.trim().orEmpty().ifBlank { "all" }}"
        val fingerprint = computeNextTasksFingerprint(
            uid = safeUid,
            today = today,
            targetCategoryId = targetCategoryId,
            openTasks = openTasks,
            completedTasks = completedTasks
        )
        synchronized(lock) {
            nextTaskEntries[scopeKey] = CachedNextTasksEntry(
                uid = safeUid,
                epochDay = today.toEpochDay(),
                fingerprint = fingerprint,
                storedAtMillis = clock(),
                result = result
            )
            while (nextTaskEntries.size > MAX_CACHED_NEXT_TASK_SCOPES) {
                val eldest = nextTaskEntries.keys.firstOrNull() ?: break
                nextTaskEntries.remove(eldest)
            }
        }
    }

    fun evictUser(uid: String?) {
        val safeUid = uid?.takeIf { it.isNotBlank() } ?: return
        synchronized(lock) {
            entriesByUid.remove(safeUid)
            subTaskEntries.entries.removeAll { it.value.uid == safeUid }
            nextTaskEntries.entries.removeAll { it.value.uid == safeUid }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            entriesByUid.clear()
            subTaskEntries.clear()
            nextTaskEntries.clear()
        }
    }

    companion object {
        const val TTL_MS = 30 * 60 * 1000L
        const val SUBTASK_TTL_MS = 24 * 60 * 60 * 1000L
        const val NEXT_TASKS_TTL_MS = 2 * 60 * 60 * 1000L
        const val SUBTASK_JACCARD_THRESHOLD = 0.85
        private const val TEN_MINUTES_MS = 10 * 60 * 1000L
        private const val MAX_CACHED_USERS = 4
        private const val MAX_CACHED_SUBTASK_ENTRIES = 40
        private const val MAX_CACHED_NEXT_TASK_SCOPES = 12

        private val TOKEN_SPLIT_REGEX = Regex("[^a-z0-9]+")
        private val STOP_WORDS = setOf(
            "a", "an", "and", "at", "by", "for", "from", "in", "into",
            "my", "of", "on", "or", "our", "the", "to", "up", "with"
        )

        internal fun normalizeSemanticTokens(raw: String): Set<String> =
            raw.lowercase()
                .split(TOKEN_SPLIT_REGEX)
                .map { it.trim() }
                .filter { it.length >= 2 && it !in STOP_WORDS }
                .map(::lightStem)
                .toSet()

        internal fun jaccardSimilarity(left: Set<String>, right: Set<String>): Double {
            if (left.isEmpty() || right.isEmpty()) return 0.0
            val intersection = left.count { it in right }
            val union = left.size + right.size - intersection
            return if (union == 0) 0.0 else intersection.toDouble() / union.toDouble()
        }

        private fun lightStem(token: String): String = when {
            token.length > 6 && token.endsWith("ation") -> token.dropLast(5)
            token.length > 5 && token.endsWith("ing") -> token.dropLast(3)
            token.length > 4 && token.endsWith("ed") -> token.dropLast(2)
            token.length > 4 && token.endsWith("es") -> token.dropLast(2)
            token.length > 4 && token.endsWith("e") -> token.dropLast(1)
            token.length > 3 && token.endsWith("s") && !token.endsWith("ss") -> token.dropLast(1)
            else -> token
        }

        private fun sha256Hex(raw: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
