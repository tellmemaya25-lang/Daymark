package com.sabihon.todo

import com.sabihon.todo.domain.admin.AdminAccessPolicy
import com.sabihon.todo.domain.admin.AdminAiDailyStats
import com.sabihon.todo.domain.admin.AdminPlatformMetrics
import com.sabihon.todo.domain.admin.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminAccessPolicyTest {

    @Test
    fun `primary admin email normalizes case and whitespace`() {
        assertTrue(AdminAccessPolicy.isPrimaryAdminEmail("tellmemaya25@gmail.com"))
        assertTrue(AdminAccessPolicy.isPrimaryAdminEmail("  TellMeMaya25@GMAIL.com "))
        assertFalse(AdminAccessPolicy.isPrimaryAdminEmail("other@gmail.com"))
        assertFalse(AdminAccessPolicy.isPrimaryAdminEmail(null))
    }

    @Test
    fun `hasAdminPrivilege evaluates 3-tier RBAC hierarchy`() {
        // Tier 1: Custom claim
        assertTrue(
            AdminAccessPolicy.hasAdminPrivilege(
                email = "member@example.com",
                storedRole = UserRole.USER,
                hasDelegatedAdminDoc = false,
                hasCustomAdminClaim = true
            )
        )
        // Tier 2: Primary owner email
        assertTrue(
            AdminAccessPolicy.hasAdminPrivilege(
                email = "tellmemaya25@gmail.com",
                storedRole = UserRole.USER,
                hasDelegatedAdminDoc = false,
                hasCustomAdminClaim = false
            )
        )
        // Tier 3: Delegated /admins/{uid} doc or /users/{uid}.role == ADMIN
        assertTrue(
            AdminAccessPolicy.hasAdminPrivilege(
                email = "mod@example.com",
                storedRole = UserRole.USER,
                hasDelegatedAdminDoc = true
            )
        )
        assertTrue(
            AdminAccessPolicy.hasAdminPrivilege(
                email = "mod@example.com",
                storedRole = UserRole.ADMIN,
                hasDelegatedAdminDoc = false
            )
        )
        // Standard user
        assertFalse(
            AdminAccessPolicy.hasAdminPrivilege(
                email = "user@example.com",
                storedRole = UserRole.USER,
                hasDelegatedAdminDoc = false,
                hasCustomAdminClaim = false
            )
        )
    }

    @Test
    fun `anti-lockout blocks modifying primary owner or active session`() {
        // Cannot demote or suspend primary owner
        assertFalse(
            AdminAccessPolicy.canModifyUserRole(
                actorUid = "delegated-admin-uid",
                targetUid = "owner-uid",
                targetEmail = "tellmemaya25@gmail.com"
            )
        )
        assertFalse(
            AdminAccessPolicy.canSuspendUser(
                actorUid = "delegated-admin-uid",
                targetUid = "owner-uid",
                targetEmail = "tellmemaya25@gmail.com"
            )
        )
        // Cannot demote or suspend self
        assertFalse(
            AdminAccessPolicy.canModifyUserRole(
                actorUid = "same-uid",
                targetUid = "same-uid",
                targetEmail = "mod@example.com"
            )
        )
        assertFalse(
            AdminAccessPolicy.canSuspendUser(
                actorUid = "same-uid",
                targetUid = "same-uid",
                targetEmail = "mod@example.com"
            )
        )
        // Can manage regular non-owner user
        assertTrue(
            AdminAccessPolicy.canModifyUserRole(
                actorUid = "owner-uid",
                targetUid = "member-uid",
                targetEmail = "member@example.com"
            )
        )
        assertTrue(
            AdminAccessPolicy.canSuspendUser(
                actorUid = "owner-uid",
                targetUid = "member-uid",
                targetEmail = "member@example.com"
            )
        )
    }

    @Test
    fun `bootstrap verification rejects invalid emails and wrong secrets`() {
        assertFalse(AdminAccessPolicy.matchesBootstrapCredential("user@example.com", "wrong-secret"))
        assertFalse(AdminAccessPolicy.matchesBootstrapCredential("tellmemaya25@gmail.com", ""))
        assertFalse(AdminAccessPolicy.matchesBootstrapCredential("tellmemaya25@gmail.com", "incorrect-pass"))

        // Deterministic salted SHA-256 helper check using synthetic test vector
        val digestA = AdminAccessPolicy.computeDigestHex("salt-1", "a@b.com", "synthetic-secret")
        val digestB = AdminAccessPolicy.computeDigestHex("salt-1", "a@b.com", "synthetic-secret")
        val digestC = AdminAccessPolicy.computeDigestHex("salt-2", "a@b.com", "synthetic-secret")
        assertEquals(64, digestA.length)
        assertEquals(digestA, digestB)
        assertFalse(digestA == digestC)
    }

    @Test
    fun `platform metrics and AI daily stats compute safe percentages`() {
        val emptyMetrics = AdminPlatformMetrics(totalTasks = 0, completedTasks = 0)
        assertEquals(0, emptyMetrics.completionRatePercent)

        val populatedMetrics = AdminPlatformMetrics(totalTasks = 40, completedTasks = 30)
        assertEquals(75, populatedMetrics.completionRatePercent)

        val aiStats = AdminAiDailyStats(
            dateKey = "2026-10-28",
            totalCalls = 20L,
            cloudSuccessCount = 15L,
            totalLatencyMs = 8_400L
        )
        assertEquals(75, aiStats.cloudHitRatePercent)
        assertEquals(420L, aiStats.averageLatencyMs)
    }
}
