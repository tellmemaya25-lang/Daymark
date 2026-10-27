package com.sabihon.todo.data.repository

import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.sabihon.todo.core.util.Result
import com.sabihon.todo.data.remote.dto.TaskDto
import com.sabihon.todo.data.remote.dto.toDomain
import com.sabihon.todo.di.IoDispatcher
import com.sabihon.todo.domain.admin.AdminAccessPolicy
import com.sabihon.todo.domain.admin.AdminAiDailyStats
import com.sabihon.todo.domain.admin.AdminAuditEntry
import com.sabihon.todo.domain.admin.AdminPlatformMetrics
import com.sabihon.todo.domain.admin.AdminRepository
import com.sabihon.todo.domain.admin.AdminSystemConfig
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
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : AdminRepository {

    override suspend fun isCurrentUserAdmin(): Boolean =
        authRepository.isCurrentUserAdmin()

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
                        compareByDescending<AdminUserSummary> { it.role == UserRole.ADMIN }
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
                trySend(
                    AdminSystemConfig(
                        cloudAiGlobalEnabled = snapshot.getBoolean("cloudAiGlobalEnabled") ?: true,
                        prioritizeEnabled = snapshot.getBoolean("prioritizeEnabled") ?: true,
                        suggestionsEnabled = snapshot.getBoolean("suggestionsEnabled") ?: true,
                        dailyAiQuotaPerUser = (snapshot.getLong("dailyAiQuotaPerUser") ?: 50L).toInt(),
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
                if (snapshot == null || !snapshot.exists()) {
                    trySend(AdminAiDailyStats(dateKey = todayKey))
                    return@addSnapshotListener
                }
                trySend(
                    AdminAiDailyStats(
                        dateKey = snapshot.getString("dateKey") ?: todayKey,
                        totalCalls = snapshot.getLong("totalCalls") ?: 0L,
                        cloudSuccessCount = snapshot.getLong("cloudSuccessCount") ?: 0L,
                        localFallbackCount = snapshot.getLong("localFallbackCount") ?: 0L,
                        errorCount = snapshot.getLong("errorCount") ?: 0L,
                        totalLatencyMs = snapshot.getLong("totalLatencyMs") ?: 0L,
                        acceptedOutcomes = snapshot.getLong("acceptedOutcomes") ?: 0L,
                        dismissedOutcomes = snapshot.getLong("dismissedOutcomes") ?: 0L
                    )
                )
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

            val users: List<AdminUserSummary> = try {
                firestore.collection("users").get().await()
                    .documents
                    .mapNotNull { it.toAdminUserSummary() }
            } catch (e: Exception) {
                permissionLimited = true
                diagnosticHint =
                    "Cross-user Firestore rules not yet deployed. Run `firebase deploy --only firestore:rules` for full platform-wide analytics."
                listOfNotNull(currentAdminFallbackSummary())
            }

            val allTasks: List<Task> = try {
                firestore.collectionGroup("tasks").get().await()
                    .documents
                    .mapNotNull { doc ->
                        doc.toObject(TaskDto::class.java)?.copy(id = doc.id)?.toDomain()
                    }
                    .filter { !it.isDeleted }
            } catch (_: Exception) {
                // Fallback: aggregate per-user subcollections across accessible user docs.
                val collected = mutableListOf<Task>()
                for (u in users) {
                    val docs = runCatching {
                        firestore.collection("users")
                            .document(u.uid)
                            .collection("tasks")
                            .get()
                            .await()
                            .documents
                    }.getOrDefault(emptyList())
                    docs.mapNotNullTo(collected) { doc ->
                        doc.toObject(TaskDto::class.java)?.copy(id = doc.id)?.toDomain()
                    }
                }
                collected.filter { !it.isDeleted }
            }

            val totalCategories: Int = try {
                firestore.collectionGroup("categories").get().await().size()
            } catch (_: Exception) {
                var sum = 0
                for (u in users) {
                    sum += runCatching {
                        firestore.collection("users")
                            .document(u.uid)
                            .collection("categories")
                            .get()
                            .await()
                            .size()
                    }.getOrDefault(0)
                }
                sum
            }

            val now = System.currentTimeMillis()
            val sevenDaysAgoMillis = now - 7L * 24L * 3_600_000L
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val startOfTodayMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()

            val openList = allTasks.filter { !it.isCompleted }
            val completedList = allTasks.filter { it.isCompleted }
            val overdueCount = openList.count { it.dueAt != null && it.dueAt < startOfTodayMillis }
            val activeHabits = openList.count { it.repeat.isRecurring }

            val priorityMap = Priority.entries.associateWith { p ->
                openList.count { it.priority == p }
            }

            val dayFormatter = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
            val velocity = (6 downTo 0).map { daysBack ->
                val day = today.minusDays(daysBack.toLong())
                val createdOnDay = allTasks.count { task ->
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

            Result.Success(
                AdminPlatformMetrics(
                    totalUsers = users.size,
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
                    priorityBreakdown = priorityMap,
                    sevenDayVelocity = velocity,
                    permissionLimited = permissionLimited,
                    diagnosticHint = diagnosticHint
                )
            )
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

                val tasks = tasksSnap.documents
                    .mapNotNull { doc ->
                        doc.toObject(TaskDto::class.java)?.copy(id = doc.id)?.toDomain()
                    }
                    .filter { !it.isDeleted }

                val openCount = tasks.count { !it.isCompleted }
                val completedCount = tasks.count { it.isCompleted }
                val maxStreak = tasks.maxOfOrNull { it.streak } ?: 0

                Result.Success(
                    AdminUserDetail(
                        user = user,
                        openTaskCount = openCount,
                        completedTaskCount = completedCount,
                        categoryCount = catsSnap.size(),
                        activeStreakMax = maxStreak
                    )
                )
            } catch (e: Exception) {
                Result.Error(e, e.localizedMessage ?: "Couldn't inspect user workspace")
            }
        }

    override suspend fun setUserAdminRole(
        target: AdminUserSummary,
        makeAdmin: Boolean
    ): Result<Unit> = withContext(ioDispatcher) {
        val actor = auth.currentUser
            ?: return@withContext Result.Error(IllegalStateException("Signed out"), "Not signed in")
        if (!AdminAccessPolicy.canModifyUserRole(actor.uid, target.uid, target.email)) {
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
                        "grantedBy" to actor.email.orEmpty(),
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
        val actor = auth.currentUser
            ?: return@withContext Result.Error(IllegalStateException("Signed out"), "Not signed in")
        if (!AdminAccessPolicy.canSuspendUser(actor.uid, target.uid, target.email)) {
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

    override suspend fun updateSystemConfig(config: AdminSystemConfig): Result<Unit> =
        withContext(ioDispatcher) {
            val actor = auth.currentUser
                ?: return@withContext Result.Error(IllegalStateException("Signed out"), "Not signed in")
            try {
                firestore.collection("system_config")
                    .document("global")
                    .set(
                        mapOf(
                            "cloudAiGlobalEnabled" to config.cloudAiGlobalEnabled,
                            "prioritizeEnabled" to config.prioritizeEnabled,
                            "suggestionsEnabled" to config.suggestionsEnabled,
                            "dailyAiQuotaPerUser" to config.dailyAiQuotaPerUser.coerceIn(5, 1_000),
                            "maintenanceMode" to config.maintenanceMode,
                            "announcementBanner" to config.announcementBanner.trim(),
                            "updatedByEmail" to actor.email.orEmpty(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                    .await()

                recordAuditEvent(
                    action = "UPDATE_SYSTEM_CONFIG",
                    targetId = "system_config/global",
                    summary = "CloudAI=${config.cloudAiGlobalEnabled}, Quota=${config.dailyAiQuotaPerUser}/day, Maintenance=${config.maintenanceMode}"
                )
                Result.Success(Unit)
            } catch (e: Exception) {
                Result.Error(e, e.localizedMessage ?: "Failed to save system configuration")
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
            ?: return@withContext Result.Error(IllegalStateException("Signed out"), "Not signed in")
        val email = user.email.orEmpty()

        try {
            if (currentPassword.isNotBlank() && email.isNotBlank()) {
                val credential = EmailAuthProvider.getCredential(email, currentPassword)
                runCatching { user.reauthenticate(credential).await() }
            }
            user.updatePassword(newPassword).await()
            recordAuditEvent(
                action = "ROTATE_ADMIN_PASSWORD",
                targetId = user.uid,
                summary = "Updated Firebase Auth password for $email"
            )
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(
                e,
                e.localizedMessage ?: "Couldn't update password — sign out and sign back in first."
            )
        }
    }

    override suspend fun recordAuditEvent(action: String, targetId: String, summary: String) {
        val actor = auth.currentUser ?: return
        runCatching {
            firestore.collection("admin_audit_logs").add(
                mapOf(
                    "actorUid" to actor.uid,
                    "actorEmail" to actor.email.orEmpty(),
                    "action" to action,
                    "targetId" to targetId,
                    "summary" to summary,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
    }

    private fun currentAdminFallbackSummary(): AdminUserSummary? {
        val user = auth.currentUser ?: return null
        val email = user.email.orEmpty()
        return AdminUserSummary(
            uid = user.uid,
            displayName = user.displayName?.takeIf { it.isNotBlank() } ?: "Daymark Admin",
            email = email,
            photoUrl = user.photoUrl?.toString(),
            role = if (AdminAccessPolicy.isPrimaryAdminEmail(email)) UserRole.ADMIN else UserRole.USER
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
