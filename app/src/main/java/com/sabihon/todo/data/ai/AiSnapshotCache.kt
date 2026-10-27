package com.sabihon.todo.data.ai

import com.sabihon.todo.domain.ai.AiSource
import com.sabihon.todo.domain.ai.PrioritizationPlan
import com.sabihon.todo.domain.model.Task
import java.security.MessageDigest
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `uid`-namespaced snapshot cache with real-time CDC surgical eviction
 * (`docs/02-daymark-ai-system-design.md` §4 & §5).
 *
 *  - Exact canonical task-set hash avoids repeat Gemini calls when switching tabs or
 *    reopening Home without changing open tasks (`TTL = 30 min` + date rollover).
 *  - Completing or deleting a task surgically removes only that task from the active
 *    focus plan and compacts `focusRank` (`1..N-1`) in `< 1 ms`.
 *  - Signing out purges the user's partition so shared phones never leak cached plans.
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

    private val lock = Any()
    private val entriesByUid = LinkedHashMap<String, CachedEntry>(4, 0.75f, true)

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
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
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
            // Update the stored fingerprint only when the set shrank or priorities
            // synced; if a brand-new task arrived, keep the old fingerprint so the
            // next explicit "Prioritize" tap knows to include the new task.
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

    fun evictUser(uid: String?) {
        val safeUid = uid?.takeIf { it.isNotBlank() } ?: return
        synchronized(lock) {
            entriesByUid.remove(safeUid)
        }
    }

    fun clearAll() {
        synchronized(lock) {
            entriesByUid.clear()
        }
    }

    companion object {
        const val TTL_MS = 30 * 60 * 1000L
        private const val TEN_MINUTES_MS = 10 * 60 * 1000L
        private const val MAX_CACHED_USERS = 4
    }
}
