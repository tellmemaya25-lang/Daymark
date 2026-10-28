package com.sabihon.todo.data.repository

import com.google.firebase.Timestamp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.data.ai.AiQuotaGuard
import com.sabihon.todo.data.remote.dto.CategoryDto
import com.sabihon.todo.data.remote.dto.TaskDto
import com.sabihon.todo.data.remote.dto.toDomain
import com.sabihon.todo.di.IoDispatcher
import com.sabihon.todo.domain.admin.AdminAccessPolicy
import com.sabihon.todo.domain.admin.AdminAiDailyStats
import com.sabihon.todo.domain.admin.AdminAuditEntry
import com.sabihon.todo.domain.admin.AdminCategoryStat
import com.sabihon.todo.domain.admin.AdminExportFormatter
import com.sabihon.todo.domain.admin.AdminExportPayload
import com.sabihon.todo.domain.admin.AdminExportType
import com.sabihon.todo.domain.admin.AdminPlatformMetrics
import com.sabihon.todo.domain.admin.AdminRepository
import com.sabihon.todo.domain.admin.AdminSessionMode
import com.sabihon.todo.domain.admin.AdminSystemConfig
import com.sabihon.todo.domain.admin.AdminTaskRecord
import com.sabihon.todo.domain.admin.AdminUserDetail
import com.sabihon.todo.domain.admin.AdminUserSummary
import com.sabihon.todo.domain.admin.DailyTaskBucket
import com.sabihon.todo.domain.admin.UserRole
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.Task
import com.sabihon.todo.domain.repository.AuthRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdminRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val authRepository: AuthRepository,
    private val aiQuotaGuard: AiQuotaGuard,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : AdminRepository {

    override suspend fun isCurrentUserAdmin(): Boolean =
        authRepository.isCurrentUserAdmin()

    override suspend fun getSessionMode(): AdminSessionMode = withContext(ioDispatcher) {
        val fbUser = auth.currentUser
        if (fbUser != null && !fbUser.isAnonymous && !fbUser.email.isNullOrBlank()) {
            AdminSessionMode.FIREBASE_NATIVE
        } else {
            AdminSessionMode.HYBRID_OWNER_SESSION
        }
    }

    override fun observeUsers(): Flow<Result<List<AdminUserSummary>>> = callbackFlow {
        val reg = firestore.collection("users")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    val selfFallback = currentAdminFallbackSummary()
                    if (selfFallback != null) {
                        trySend(Result.Success(listOf(selfFallback)))
                    } else {
                        trySend(Result.Error(error, error.localizedMessage ?: "Couldn't load users"))
                    }
                    return@addSnapshotListener
                }
                val users = snapshot?.documents
                    ?.mapNotNull { doc -> doc.toAdminUserSummary() }
                    ?.sortedWith(
                        compareByDescending<AdminUserSummary> { it.isPrimaryOwner }
                            .thenByDescending { it.role == UserRole.ADMIN }
                            .thenByDescending { it.createdAtMillis ?: 0L }
                    )
                    .orEmpty()
                trySend(Result.Success(users))
            }
        awaitClose { reg.remove() }
    }

    override fun observeSystemConfig(): Flow<AdminSystemConfig> = callbackFlow {
        val reg = firestore.collection("system_config")
            .document("global")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null || !snapshot.exists()) {
                    trySend(AdminSystemConfig())
                    return@addSnapshotListener
                }
                val quota = (snapshot.getLong("dailyAiQuotaPerUser") ?: 50L).toInt().coerceIn(5, 1_000)
                aiQuotaGuard.runtimeMaxDailyCalls = quota
                trySend(
                    AdminSystemConfig(
                        cloudAiGlobalEnabled = snapshot.getBoolean("cloudAiGlobalEnabled") ?: true,
                        prioritizeEnabled = snapshot.getBoolean("prioritizeEnabled") ?: true,
                        suggestionsEnabled = snapshot.getBoolean("suggestionsEnabled") ?: true,
                        dailyAiQuotaPerUser = quota,
                        maintenanceMode = snapshot.getBoolean("maintenanceMode") ?: false,
                        announcementBanner = snapshot.getString("announcementBanner").orEmpty(),
                        updatedByEmail = snapshot.getString("updatedByEmail").orEmpty(),
                        updatedAtMillis = snapshot.getTimestamp("updatedAt")?.toDate()?.time
                    )
                )
            }
        awaitClose { reg.remove() }
    }

    override fun observeAuditLogs(limit: Int): Flow<List<AdminAuditEntry>> = callbackFlow {
        val reg = firestore.collection("admin_audit_logs")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit.toLong())
            .addSnapshotListener { snapshot, _ ->
                val entries = snapshot?.documents?.map { doc ->
                    AdminAuditEntry(
                        id = doc.id,
                        actorUid = doc.getString("actorUid").orEmpty(),
                        actorEmail = doc.getString("actorEmail").orEmpty(),
                        action = doc.getString("action").orEmpty(),
                        targetId = doc.getString("targetId").orEmpty(),
                        summary = doc.getString("summary").orEmpty(),
                        createdAtMillis = doc.getTimestamp("createdAt")?.toDate()?.time
                            ?: System.currentTimeMillis()
                    )
                }.orEmpty()
                trySend(entries)
            }
        awaitClose { reg.remove() }
    }

    override fun observeTodayAiStats(): Flow<AdminAiDailyStats> = callbackFlow {
        val todayKey = LocalDate.now().toString()
        val reg = firestore.collection("ai_telemetry_daily")
            .document(todayKey)
            .addSnapshotListener { snapshot, _ ->
                trySend(snapshot.toAiDailyStats(todayKey))
            }
        awaitClose { reg.remove() }
    }

    override suspend fun loadPlatformMetrics(): Result<AdminPlatformMetrics> =
        withContext(ioDispatcher) {
            if (!isCurrentUserAdmin()) {
                return@withContext Result.Error(
                    SecurityException("Unauthorized"),
                    "Admin privileges required."
                )
            }

            var permissionLimited = false
            var diagnosticHint: String? = null
            val sessionMode = getSessionMode()

            val users: List<AdminUserSummary> = try {
                firestore.collection("users").get().await()
                    .documents
                    .mapNotNull { it.toAdminUserSummary() }
            } catch (_: Exception) {
                permissionLimited = true
                diagnosticHint = if (sessionMode == AdminSessionMode.HYBRID_OWNER_SESSION) {
                    "Signed in via Hybrid Owner Session because tellmemaya25@gmail.com is linked to Google Sign-In in Firebase. " +
                        "Open Security -> 'Connect Google Firebase Token' (and ensure `firebase deploy --only firestore:rules` is deployed) to unlock live cross-user cloud data."
                } else {
                    "Cross-user Firestore rules not yet deployed. Run `firebase deploy --only firestore:rules` for full platform-wide analytics."
                }
                listOfNotNull(currentAdminFallbackSummary())
            }

            // Build category lookup (ownerUid:catId -> name) and count total categories
            val categoryNameMap = mutableMapOf<String, String>()
            val totalCategories: Int = try {
                val catDocs = firestore.collectionGroup("categories").get().await().documents
                for (doc in catDocs) {
                    val ownerUid = doc.reference.parent.parent?.id.orEmpty()
                    val name = doc.getString("name")?.takeIf { it.isNotBlank() } ?: continue
                    categoryNameMap["$ownerUid:${doc.id}"] = name
                }
                catDocs.size
            } catch (_: Exception) {
                var sum = 0
                for (u in users) {
                    val docs = runCatching {
                        firestore.collection("users")
                            .document(u.uid)
                            .collection("categories")
                            .get()
                            .await()
                            .documents
                    }.getOrDefault(emptyList())
                    for (doc in docs) {
                        val name = doc.getString("name")?.takeIf { it.isNotBlank() }
                        if (name != null) categoryNameMap["${u.uid}:${doc.id}"] = name
                    }
                    sum += docs.size
                }
                sum
            }

            data class OwnedTask(val ownerUid: String, val task: Task)

            val allTasks: List<OwnedTask> = try {
                firestore.collectionGroup("tasks").get().await()
                    .documents
                    .mapNotNull { doc ->
                        val ownerUid = doc.reference.parent.parent?.id.orEmpty()
                        val domain = doc.toObject(TaskDto::class.java)?.copy(id = doc.id)?.toDomain()
                            ?: return@mapNotNull null
                        if (domain.isDeleted) null else OwnedTask(ownerUid, domain)
                    }
            } catch (_: Exception) {
                val collected = mutableListOf<OwnedTask>()
                for (u in users) {
                    val docs = runCatching {
                        firestore.collection("users")
                            .document(u.uid)
                            .collection("tasks")
                            .get()
                            .await()
                            .documents
                    }.getOrDefault(emptyList())
                    for (doc in docs) {
                        val domain = doc.toObject(TaskDto::class.java)?.copy(id = doc.id)?.toDomain()
                        if (domain != null && !domain.isDeleted) {
                            collected.add(OwnedTask(u.uid, domain))
                        }
                    }
                }
                collected
            }

            val now = System.currentTimeMillis()
            val sevenDaysAgoMillis = now - 7L * 24L * 3_600_000L
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val startOfTodayMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()

            val openList = allTasks.map { it.task }.filter { !it.isCompleted }
            val completedList = allTasks.map { it.task }.filter { it.isCompleted }
            val overdueCount = openList.count { it.dueAt != null && it.dueAt < startOfTodayMillis }
            val activeHabits = openList.count { it.repeat.isRecurring }
            val maxStreak = allTasks.maxOfOrNull { it.task.streak } ?: 0

            val priorityMap = Priority.entries.associateWith { p ->
                openList.count { it.priority == p }
            }

            val themeMap = listOf("SYSTEM", "LIGHT", "DARK").associateWith { pref ->
                users.count { it.themePref.equals(pref, ignoreCase = true) }
            }

            val categoryCounts = mutableMapOf<String, Int>()
            for (ot in allTasks) {
                val catId = ot.task.categoryId
                val catName = if (!catId.isNullOrBlank()) {
                    categoryNameMap["${ot.ownerUid}:$catId"] ?: "Uncategorized"
                } else {
                    "Inbox (No Project)"
                }
                categoryCounts[catName] = (categoryCounts[catName] ?: 0) + 1
            }
            val topCategories = categoryCounts.entries
                .sortedByDescending { it.value }
                .take(5)
                .map { AdminCategoryStat(name = it.key, taskCount = it.value) }

            val dayFormatter = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
            val velocity = (6 downTo 0).map { daysBack ->
                val day = today.minusDays(daysBack.toLong())
                val createdOnDay = allTasks.count { (_, task) ->
                    Instant.ofEpochMilli(task.createdAt).atZone(zone).toLocalDate() == day
                }
                val completedOnDay = completedList.count { task ->
                    val ts = task.completedAt ?: task.updatedAt
                    Instant.ofEpochMilli(ts).atZone(zone).toLocalDate() == day
                }
                DailyTaskBucket(
                    label = day.format(dayFormatter),
                    createdCount = createdOnDay,
                    completedCount = completedOnDay
                )
            }

            val sevenDayAiTrend = (6 downTo 0).map { daysBack ->
                val dayKey = today.minusDays(daysBack.toLong()).toString()
                runCatching {
                    val snap = firestore.collection("ai_telemetry_daily")
                        .document(dayKey)
                        .get()
                        .await()
                    snap.toAiDailyStats(dayKey)
                }.getOrDefault(AdminAiDailyStats(dateKey = dayKey))
            }

            val activeUsers7d = users.count { u ->
                val lastSeen = u.lastLoginAtMillis ?: u.createdAtMillis
                lastSeen != null && lastSeen >= sevenDaysAgoMillis
            }

            Result.Success(
                AdminPlatformMetrics(
                    totalUsers = users.size,
                    activeUsersLast7Days = activeUsers7d,
                    activeAdmins = users.count { it.role == UserRole.ADMIN && !it.isDisabled },
                    suspendedUsers = users.count { it.isDisabled },
                    newUsersLast7Days = users.count {
                        val created = it.createdAtMillis
                        created != null && created >= sevenDaysAgoMillis
                    },
                    totalTasks = allTasks.size,
                    openTasks = openList.size,
                    completedTasks = completedList.size,
                    overdueOpenTasks = overdueCount,
                    totalCategories = totalCategories,
                    activeHabitTasks = activeHabits,
                    maxHabitStreak = maxStreak,
                    priorityBreakdown = priorityMap,
                    themeBreakdown = themeMap,
                    topCategories = topCategories,
                    sevenDayVelocity = velocity,
                    sevenDayAiTrend = sevenDayAiTrend,
                    permissionLimited = permissionLimited,
                    diagnosticHint = diagnosticHint
                )
            )
        }

    override suspend fun loadAllPlatformTasks(): Result<List<AdminTaskRecord>> =
        withContext(ioDispatcher) {
            if (!isCurrentUserAdmin()) {
                return@withContext Result.Error(
                    SecurityException("Unauthorized"),
                    "Admin privileges required."
                )
            }

            val usersByUid: Map<String, AdminUserSummary> = runCatching {
                firestore.collection("users").get().await()
                    .documents
                    .mapNotNull { it.toAdminUserSummary() }
                    .associateBy { it.uid }
            }.getOrElse {
                val self = currentAdminFallbackSummary()
                if (self != null) mapOf(self.uid to self) else emptyMap()
            }

            val categoryMap = mutableMapOf<String, String>()
            runCatching {
                firestore.collectionGroup("categories").get().await().documents.forEach { doc ->
                    val ownerUid = doc.reference.parent.parent?.id.orEmpty()
                    val name = doc.getString("name")?.takeIf { it.isNotBlank() }
                    if (name != null) categoryMap["$ownerUid:${doc.id}"] = name
                }
            }.onFailure {
                for (uid in usersByUid.keys) {
                    runCatching {
                        firestore.collection("users").document(uid)
                            .collection("categories").get().await().documents.forEach { doc ->
                                val name = doc.getString("name")?.takeIf { it.isNotBlank() }
                                if (name != null) categoryMap["$uid:${doc.id}"] = name
                            }
                    }
                }
            }

            val records: List<AdminTaskRecord> = try {
                firestore.collectionGroup("tasks").get().await()
                    .documents
                    .mapNotNull { doc ->
                        val ownerUid = doc.reference.parent.parent?.id.orEmpty()
                        doc.toAdminTaskRecord(ownerUid, usersByUid[ownerUid], categoryMap)
                    }
            } catch (_: Exception) {
                val fallbackList = mutableListOf<AdminTaskRecord>()
                for ((uid, ownerSummary) in usersByUid) {
                    val docs = runCatching {
                        firestore.collection("users")
                            .document(uid)
                            .collection("tasks")
                            .get()
                            .await()
                            .documents
                    }.getOrDefault(emptyList())
                    docs.mapNotNullTo(fallbackList) { doc ->
                        doc.toAdminTaskRecord(uid, ownerSummary, categoryMap)
                    }
                }
                fallbackList
            }

            val sorted = records.sortedWith(
                compareBy<AdminTaskRecord> { it.isCompleted }
                    .thenByDescending { it.priority.ordinal }
                    .thenByDescending { it.createdAtMillis }
            )
            Result.Success(sorted)
        }

    override suspend fun loadUserDetail(user: AdminUserSummary): Result<AdminUserDetail> =
        withContext(ioDispatcher) {
            try {
                val tasksSnap = firestore.collection("users")
                    .document(user.uid)
                    .collection("tasks")
                    .get()
                    .await()
                val catsSnap = firestore.collection("users")
                    .document(user.uid)
                    .collection("categories")
                    .get()
                    .await()

                val categoryMap = catsSnap.documents.mapNotNull { doc ->
                    val cat = doc.toObject(CategoryDto::class.java)?.copy(id = doc.id)
                    if (cat != null && cat.name.isNotBlank()) "${user.uid}:${cat.id}" to cat.name else null
                }.toMap()

                val records = tasksSnap.documents
                    .mapNotNull { doc -> doc.toAdminTaskRecord(user.uid, user, categoryMap) }
                    .sortedWith(
                        compareBy<AdminTaskRecord> { it.isCompleted }
                            .thenByDescending { it.createdAtMillis }
                    )

                val openCount = records.count { !it.isCompleted }
                val completedCount = records.count { it.isCompleted }
                val maxStreak = records.maxOfOrNull { it.streak } ?: 0

                Result.Success(
                    AdminUserDetail(
                        user = user,
                        openTaskCount = openCount,
                        completedTaskCount = completedCount,
                        categoryCount = catsSnap.size(),
                        activeStreakMax = maxStreak,
                        recentTasks = records.take(8)
                    )
                )
            } catch (e: Exception) {
                Result.Error(e, e.localizedMessage ?: "Couldn't inspect user workspace")
            }
        }

    override suspend fun updateUserDisplayName(
        target: AdminUserSummary,
        newDisplayName: String
    ): Result<Unit> = withContext(ioDispatcher) {
        val trimmed = newDisplayName.trim()
        if (trimmed.isBlank()) {
            return@withContext Result.Error(
                IllegalArgumentException("Empty name"),
                "Display name cannot be empty."
            )
        }
        try {
            firestore.collection("users")
                .document(target.uid)
                .set(mapOf("displayName" to trimmed), SetOptions.merge())
                .await()
            recordAuditEvent(
                action = "EDIT_USER_PROFILE",
                targetId = target.uid,
                summary = "Updated display name for ${target.email.ifBlank { target.uid }} to '$trimmed'"
            )
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.localizedMessage ?: "Failed to update display name")
        }
    }

    override suspend fun sendUserPasswordReset(email: String): Result<Unit> =
        withContext(ioDispatcher) {
            val clean = email.trim()
            if (clean.isBlank()) {
                return@withContext Result.Error(
                    IllegalArgumentException("Missing email"),
                    "User has no email address on file."
                )
            }
            try {
                auth.sendPasswordResetEmail(clean).await()
                recordAuditEvent(
                    action = "SEND_PASSWORD_RESET",
                    targetId = clean,
                    summary = "Sent Firebase password reset email to $clean"
                )
                Result.Success(Unit)
            } catch (e: Exception) {
                Result.Error(e, e.localizedMessage ?: "Failed to send password reset email")
            }
        }

    override suspend fun setUserAdminRole(
        target: AdminUserSummary,
        makeAdmin: Boolean
    ): Result<Unit> = withContext(ioDispatcher) {
        val actorUid = authRepository.getCurrentUserId().orEmpty()
        val actorEmail = authRepository.getCurrentUserEmail().orEmpty()
        if (!AdminAccessPolicy.canModifyUserRole(actorUid, target.uid, target.email)) {
            return@withContext Result.Error(
                IllegalArgumentException("Protected account"),
                "Primary owner and active session roles cannot be modified."
            )
        }

        try {
            val newRole = if (makeAdmin) UserRole.ADMIN.name else UserRole.USER.name
            firestore.collection("users")
                .document(target.uid)
                .set(mapOf("role" to newRole), SetOptions.merge())
                .await()

            firestore.collection("admins")
                .document(target.uid)
                .set(
                    mapOf(
                        "uid" to target.uid,
                        "email" to AdminAccessPolicy.normalizeEmail(target.email),
                        "enabled" to makeAdmin,
                        "grantedBy" to actorEmail,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
                .await()

            recordAuditEvent(
                action = if (makeAdmin) "GRANT_ADMIN" else "REVOKE_ADMIN",
                targetId = target.uid,
                summary = "${if (makeAdmin) "Granted" else "Revoked"} admin role for ${target.email.ifBlank { target.uid }}"
            )
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.localizedMessage ?: "Failed to update role")
        }
    }

    override suspend fun setUserSuspended(
        target: AdminUserSummary,
        suspended: Boolean
    ): Result<Unit> = withContext(ioDispatcher) {
        val actorUid = authRepository.getCurrentUserId().orEmpty()
        if (!AdminAccessPolicy.canSuspendUser(actorUid, target.uid, target.email)) {
            return@withContext Result.Error(
                IllegalArgumentException("Protected account"),
                "Primary owner and active admin session cannot be suspended."
            )
        }

        try {
            firestore.collection("users")
                .document(target.uid)
                .set(mapOf("disabled" to suspended), SetOptions.merge())
                .await()

            recordAuditEvent(
                action = if (suspended) "SUSPEND_USER" else "REACTIVATE_USER",
                targetId = target.uid,
                summary = "${if (suspended) "Suspended" else "Reactivated"} ${target.email.ifBlank { target.uid }}"
            )
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.localizedMessage ?: "Failed to update account status")
        }
    }

    override suspend fun deleteUserWorkspace(target: AdminUserSummary): Result<Unit> =
        withContext(ioDispatcher) {
            val actorUid = authRepository.getCurrentUserId().orEmpty()
            if (!AdminAccessPolicy.canDeleteUser(actorUid, target.uid, target.email)) {
                return@withContext Result.Error(
                    IllegalArgumentException("Protected account"),
                    "Primary owner and active admin session cannot be deleted."
                )
            }

            try {
                // Delete user's subcollection docs (tasks, categories, activity) then user doc.
                for (sub in listOf("tasks", "categories", "activity")) {
                    val docs = runCatching {
                        firestore.collection("users")
                            .document(target.uid)
                            .collection(sub)
                            .get()
                            .await()
                            .documents
                    }.getOrDefault(emptyList())
                    for (chunk in docs.chunked(400)) {
                        val batch = firestore.batch()
                        chunk.forEach { batch.delete(it.reference) }
                        batch.commit().await()
                    }
                }
                runCatching {
                    firestore.collection("admins").document(target.uid).delete().await()
                }
                firestore.collection("users").document(target.uid).delete().await()

                recordAuditEvent(
                    action = "DELETE_USER_WORKSPACE",
                    targetId = target.uid,
                    summary = "Deleted workspace & tasks for ${target.email.ifBlank { target.uid }}"
                )
                Result.Success(Unit)
            } catch (e: Exception) {
                Result.Error(e, e.localizedMessage ?: "Failed to delete user workspace")
            }
        }

    override suspend fun togglePlatformTaskComplete(task: AdminTaskRecord): Result<Unit> =
        withContext(ioDispatcher) {
            val nextCompleted = !task.isCompleted
            try {
                val updates = mutableMapOf<String, Any?>(
                    "completed" to nextCompleted,
                    "completedAt" to if (nextCompleted) Timestamp.now() else null,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                firestore.collection("users")
                    .document(task.ownerUid)
                    .collection("tasks")
                    .document(task.taskId)
                    .update(updates)
                    .await()

                recordAuditEvent(
                    action = if (nextCompleted) "ADMIN_COMPLETE_TASK" else "ADMIN_REOPEN_TASK",
                    targetId = "${task.ownerUid}/${task.taskId}",
                    summary = "${if (nextCompleted) "Completed" else "Reopened"} '${task.title.take(40)}' (${task.ownerEmail.ifBlank { task.ownerUid }})"
                )
                Result.Success(Unit)
            } catch (e: Exception) {
                Result.Error(e, e.localizedMessage ?: "Failed to update task status")
            }
        }

    override suspend fun updatePlatformTask(
        task: AdminTaskRecord,
        newTitle: String,
        newDescription: String,
        newPriority: Priority
    ): Result<Unit> = withContext(ioDispatcher) {
        val cleanTitle = newTitle.trim()
        if (cleanTitle.isBlank()) {
            return@withContext Result.Error(
                IllegalArgumentException("Blank title"),
                "Task title cannot be empty."
            )
        }
        try {
            val keywords = cleanTitle.lowercase(Locale.ROOT)
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .distinct()
            firestore.collection("users")
                .document(task.ownerUid)
                .collection("tasks")
                .document(task.taskId)
                .set(
                    mapOf(
                        "title" to cleanTitle,
                        "description" to newDescription.trim(),
                        "priority" to newPriority.name,
                        "searchKeywords" to keywords,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
                .await()

            recordAuditEvent(
                action = "ADMIN_EDIT_TASK",
                targetId = "${task.ownerUid}/${task.taskId}",
                summary = "Edited task '${cleanTitle.take(40)}' [${newPriority.name}] (${task.ownerEmail.ifBlank { task.ownerUid }})"
            )
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.localizedMessage ?: "Failed to save task changes")
        }
    }

    override suspend fun deletePlatformTask(task: AdminTaskRecord): Result<Unit> =
        withContext(ioDispatcher) {
            try {
                firestore.collection("users")
                    .document(task.ownerUid)
                    .collection("tasks")
                    .document(task.taskId)
                    .delete()
                    .await()

                recordAuditEvent(
                    action = "ADMIN_DELETE_TASK",
                    targetId = "${task.ownerUid}/${task.taskId}",
                    summary = "Deleted task '${task.title.take(40)}' (${task.ownerEmail.ifBlank { task.ownerUid }})"
                )
                Result.Success(Unit)
            } catch (e: Exception) {
                Result.Error(e, e.localizedMessage ?: "Failed to delete task")
            }
        }

    override suspend fun updateSystemConfig(config: AdminSystemConfig): Result<Unit> =
        withContext(ioDispatcher) {
            val actorEmail = authRepository.getCurrentUserEmail().orEmpty()
            val quota = config.dailyAiQuotaPerUser.coerceIn(5, 1_000)
            try {
                firestore.collection("system_config")
                    .document("global")
                    .set(
                        mapOf(
                            "cloudAiGlobalEnabled" to config.cloudAiGlobalEnabled,
                            "prioritizeEnabled" to config.prioritizeEnabled,
                            "suggestionsEnabled" to config.suggestionsEnabled,
                            "dailyAiQuotaPerUser" to quota,
                            "maintenanceMode" to config.maintenanceMode,
                            "announcementBanner" to config.announcementBanner.trim(),
                            "updatedByEmail" to actorEmail,
                            "updatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                    .await()
                aiQuotaGuard.runtimeMaxDailyCalls = quota

                recordAuditEvent(
                    action = "UPDATE_SYSTEM_CONFIG",
                    targetId = "system_config/global",
                    summary = "CloudAI=${config.cloudAiGlobalEnabled}, Prioritize=${config.prioritizeEnabled}, Suggest=${config.suggestionsEnabled}, Quota=$quota/d, Maint=${config.maintenanceMode}"
                )
                Result.Success(Unit)
            } catch (e: Exception) {
                Result.Error(e, e.localizedMessage ?: "Failed to save system configuration")
            }
        }

    override suspend fun generateExport(type: AdminExportType): Result<AdminExportPayload> =
        withContext(ioDispatcher) {
            try {
                val dateSlug = LocalDate.now().toString()
                val payload = when (type) {
                    AdminExportType.USERS_CSV -> {
                        val users = runCatching {
                            firestore.collection("users").get().await()
                                .documents
                                .mapNotNull { it.toAdminUserSummary() }
                        }.getOrElse { listOfNotNull(currentAdminFallbackSummary()) }
                        AdminExportPayload(
                            fileName = "daymark-users-$dateSlug.csv",
                            mimeType = type.mimeType,
                            content = AdminExportFormatter.formatUsersCsv(users),
                            rowCount = users.size
                        )
                    }
                    AdminExportType.TASKS_CSV -> {
                        val tasks = (loadAllPlatformTasks() as? Result.Success)?.data.orEmpty()
                        AdminExportPayload(
                            fileName = "daymark-tasks-$dateSlug.csv",
                            mimeType = type.mimeType,
                            content = AdminExportFormatter.formatTasksCsv(tasks),
                            rowCount = tasks.size
                        )
                    }
                    AdminExportType.FULL_SNAPSHOT_JSON -> {
                        val metrics = (loadPlatformMetrics() as? Result.Success)?.data ?: AdminPlatformMetrics()
                        val users = runCatching {
                            firestore.collection("users").get().await()
                                .documents
                                .mapNotNull { it.toAdminUserSummary() }
                        }.getOrElse { listOfNotNull(currentAdminFallbackSummary()) }
                        val todayKey = LocalDate.now().toString()
                        val todayAi = runCatching {
                            firestore.collection("ai_telemetry_daily").document(todayKey).get().await()
                                .toAiDailyStats(todayKey)
                        }.getOrDefault(AdminAiDailyStats(dateKey = todayKey))
                        val cfgSnap = runCatching {
                            firestore.collection("system_config").document("global").get().await()
                        }.getOrNull()
                        val cfg = AdminSystemConfig(
                            cloudAiGlobalEnabled = cfgSnap?.getBoolean("cloudAiGlobalEnabled") ?: true,
                            prioritizeEnabled = cfgSnap?.getBoolean("prioritizeEnabled") ?: true,
                            suggestionsEnabled = cfgSnap?.getBoolean("suggestionsEnabled") ?: true,
                            dailyAiQuotaPerUser = (cfgSnap?.getLong("dailyAiQuotaPerUser") ?: 50L).toInt(),
                            maintenanceMode = cfgSnap?.getBoolean("maintenanceMode") ?: false,
                            announcementBanner = cfgSnap?.getString("announcementBanner").orEmpty()
                        )
                        val logs = runCatching {
                            firestore.collection("admin_audit_logs")
                                .orderBy("createdAt", Query.Direction.DESCENDING)
                                .limit(25)
                                .get()
                                .await()
                                .documents
                                .map { doc ->
                                    AdminAuditEntry(
                                        id = doc.id,
                                        actorUid = doc.getString("actorUid").orEmpty(),
                                        actorEmail = doc.getString("actorEmail").orEmpty(),
                                        action = doc.getString("action").orEmpty(),
                                        targetId = doc.getString("targetId").orEmpty(),
                                        summary = doc.getString("summary").orEmpty(),
                                        createdAtMillis = doc.getTimestamp("createdAt")?.toDate()?.time
                                            ?: System.currentTimeMillis()
                                    )
                                }
                        }.getOrDefault(emptyList())

                        AdminExportPayload(
                            fileName = "daymark-admin-snapshot-$dateSlug.json",
                            mimeType = type.mimeType,
                            content = AdminExportFormatter.formatFullSnapshotJson(
                                exportedAtMillis = System.currentTimeMillis(),
                                metrics = metrics,
                                todayAi = todayAi,
                                config = cfg,
                                users = users,
                                auditLogs = logs
                            ),
                            rowCount = users.size
                        )
                    }
                }
                recordAuditEvent(
                    action = "EXPORT_ADMIN_DATA",
                    targetId = payload.fileName,
                    summary = "Exported ${type.title} (${payload.rowCount} records)"
                )
                Result.Success(payload)
            } catch (e: Exception) {
                Result.Error(e, e.localizedMessage ?: "Failed to generate export")
            }
        }

    override suspend fun linkGoogleAdminToken(idToken: String): Result<Unit> =
        withContext(ioDispatcher) {
            when (val res = authRepository.signInWithGoogle(idToken)) {
                is Result.Success -> {
                    val linkedEmail = auth.currentUser?.email.orEmpty()
                    if (!AdminAccessPolicy.isPrimaryAdminEmail(linkedEmail) && !isCurrentUserAdmin()) {
                        return@withContext Result.Error(
                            SecurityException("Non-admin Google account"),
                            "Selected Google account ($linkedEmail) does not have Admin privileges."
                        )
                    }
                    recordAuditEvent(
                        action = "LINK_ADMIN_GOOGLE_TOKEN",
                        targetId = res.data,
                        summary = "Connected native Firebase Google token for $linkedEmail"
                    )
                    Result.Success(Unit)
                }
                is Result.Error -> Result.Error(res.exception, res.message ?: "Google token link failed")
                else -> Result.Error(IllegalStateException("Unknown state"), "Google token link failed")
            }
        }

    override suspend fun changeAdminPassword(
        currentPassword: String,
        newPassword: String
    ): Result<Unit> = withContext(ioDispatcher) {
        if (newPassword.length < 6) {
            return@withContext Result.Error(
                IllegalArgumentException("Weak password"),
                "New password must be at least 6 characters."
            )
        }
        val user = auth.currentUser
        if (user == null || user.isAnonymous) {
            return@withContext Result.Error(
                IllegalStateException("Google token required"),
                "Tap 'Connect Google Firebase Token' first to bind your Firebase Auth password."
            )
        }
        val email = user.email.orEmpty()

        try {
            if (currentPassword.isNotBlank() && email.isNotBlank()) {
                val credential = EmailAuthProvider.getCredential(email, currentPassword)
                runCatching { user.reauthenticate(credential).await() }
            }
            // If user only had Google provider, link EmailAuthProvider first; otherwise updatePassword.
            val hasPasswordProvider = user.providerData.any { it.providerId == EmailAuthProvider.PROVIDER_ID }
            if (!hasPasswordProvider && email.isNotBlank()) {
                user.linkWithCredential(EmailAuthProvider.getCredential(email, newPassword)).await()
            } else {
                user.updatePassword(newPassword).await()
            }
            recordAuditEvent(
                action = "ROTATE_ADMIN_PASSWORD",
                targetId = user.uid,
                summary = "Updated Firebase Auth password for $email"
            )
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(
                e,
                e.localizedMessage ?: "Couldn't update password — re-authenticate with Google or sign in again first."
            )
        }
    }

    override suspend fun recordAuditEvent(action: String, targetId: String, summary: String) {
        val actorUid = authRepository.getCurrentUserId() ?: return
        val actorEmail = authRepository.getCurrentUserEmail().orEmpty()
        runCatching {
            firestore.collection("admin_audit_logs").add(
                mapOf(
                    "actorUid" to actorUid,
                    "actorEmail" to actorEmail,
                    "action" to action,
                    "targetId" to targetId,
                    "summary" to summary,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
    }

    private fun currentAdminFallbackSummary(): AdminUserSummary? {
        val uid = authRepository.getCurrentUserId() ?: return null
        val email = authRepository.getCurrentUserEmail().orEmpty()
        val fbUser = auth.currentUser
        return AdminUserSummary(
            uid = uid,
            displayName = fbUser?.displayName?.takeIf { it.isNotBlank() } ?: "Daymark Admin",
            email = email.ifBlank { AdminAccessPolicy.PRIMARY_ADMIN_EMAIL },
            photoUrl = fbUser?.photoUrl?.toString(),
            role = UserRole.ADMIN
        )
    }

    private fun DocumentSnapshot?.toAiDailyStats(defaultKey: String): AdminAiDailyStats {
        if (this == null || !exists()) {
            return AdminAiDailyStats(dateKey = defaultKey)
        }
        return AdminAiDailyStats(
            dateKey = getString("dateKey") ?: defaultKey,
            totalCalls = getLong("totalCalls") ?: 0L,
            cloudSuccessCount = getLong("cloudSuccessCount") ?: 0L,
            localFallbackCount = getLong("localFallbackCount") ?: 0L,
            errorCount = getLong("errorCount") ?: 0L,
            totalLatencyMs = getLong("totalLatencyMs") ?: 0L,
            acceptedOutcomes = getLong("acceptedOutcomes") ?: 0L,
            dismissedOutcomes = getLong("dismissedOutcomes") ?: 0L
        )
    }

    private fun DocumentSnapshot.toAdminTaskRecord(
        ownerUid: String,
        owner: AdminUserSummary?,
        categoryMap: Map<String, String>
    ): AdminTaskRecord? {
        val dto = toObject(TaskDto::class.java)?.copy(id = id) ?: return null
        val domain = dto.toDomain()
        if (domain.isDeleted) return null
        val catId = domain.categoryId
        val catName = if (!catId.isNullOrBlank()) {
            categoryMap["$ownerUid:$catId"].orEmpty()
        } else {
            ""
        }
        return AdminTaskRecord(
            taskId = domain.id,
            ownerUid = ownerUid,
            ownerName = owner?.displayName ?: ownerUid.take(8),
            ownerEmail = owner?.email.orEmpty(),
            title = domain.title,
            description = domain.description,
            priority = domain.priority,
            categoryId = catId,
            categoryName = catName,
            isCompleted = domain.isCompleted,
            dueAtMillis = domain.dueAt,
            completedAtMillis = domain.completedAt,
            createdAtMillis = domain.createdAt,
            isRecurring = domain.repeat.isRecurring,
            streak = domain.streak,
            subTasksDone = domain.subTasks.count { it.isDone },
            subTasksTotal = domain.subTasks.size
        )
    }

    private fun DocumentSnapshot.toAdminUserSummary(): AdminUserSummary? {
        val email = getString("email").orEmpty()
        val rawRole = getString("role")
        val effectiveRole = if (AdminAccessPolicy.isPrimaryAdminEmail(email)) {
            UserRole.ADMIN
        } else {
            UserRole.fromString(rawRole)
        }
        return AdminUserSummary(
            uid = id,
            displayName = getString("displayName")?.takeIf { it.isNotBlank() }
                ?: email.substringBefore('@').ifBlank { "User" },
            email = email,
            photoUrl = getString("photoUrl"),
            role = effectiveRole,
            isDisabled = getBoolean("disabled") == true && !AdminAccessPolicy.isPrimaryAdminEmail(email),
            themePref = getString("themePref") ?: "SYSTEM",
            createdAtMillis = getTimestamp("createdAt")?.toDate()?.time,
            lastLoginAtMillis = getTimestamp("lastLoginAt")?.toDate()?.time
        )
    }
}
