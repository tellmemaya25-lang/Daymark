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
        assertTrue(
            AdminAccessPolicy.hasAdminPrivilege(
                email = "member@example.com",
                storedRole = UserRole.USER,
                hasDelegatedAdminDoc = false,
                hasCustomAdminClaim = true
            )
        )
        assertTrue(
            AdminAccessPolicy.hasAdminPrivilege(
                email = "tellmemaya25@gmail.com",
                storedRole = UserRole.USER,
                hasDelegatedAdminDoc = false,
                hasCustomAdminClaim = false
            )
        )
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
    fun `anti-lockout blocks modifying suspending or deleting primary owner or self`() {
        // Cannot demote, suspend, or delete primary owner
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
        assertFalse(
            AdminAccessPolicy.canDeleteUser(
                actorUid = "delegated-admin-uid",
                targetUid = "owner-uid",
                targetEmail = "tellmemaya25@gmail.com"
            )
        )

        // Cannot demote, suspend, or delete self
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
        assertFalse(
            AdminAccessPolicy.canDeleteUser(
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
        assertTrue(
            AdminAccessPolicy.canDeleteUser(
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

        val digestA = AdminAccessPolicy.computeDigestHex("salt-1", "a@b.com", "synthetic-secret")
        val digestB = AdminAccessPolicy.computeDigestHex("salt-1", "a@b.com", "synthetic-secret")
        val digestC = AdminAccessPolicy.computeDigestHex("salt-2", "a@b.com", "synthetic-secret")
        assertEquals(64, digestA.length)
        assertEquals(digestA, digestB)
        assertFalse(digestA == digestC)
    }

    @Test
    fun `platform metrics and AI daily stats compute safe percentages and averages`() {
        val emptyMetrics = AdminPlatformMetrics(totalUsers = 0, totalTasks = 0, completedTasks = 0)
        assertEquals(0, emptyMetrics.completionRatePercent)
        assertEquals("0.0", emptyMetrics.avgTasksPerUser)

        val populatedMetrics = AdminPlatformMetrics(totalUsers = 8, totalTasks = 40, completedTasks = 30)
        assertEquals(75, populatedMetrics.completionRatePercent)
        assertEquals("5.0", populatedMetrics.avgTasksPerUser)

        val aiStats = AdminAiDailyStats(
            dateKey = "2026-10-28",
            totalCalls = 20L,
            cloudSuccessCount = 15L,
            totalLatencyMs = 8_400L,
            acceptedOutcomes = 9L,
            dismissedOutcomes = 3L
        )
        assertEquals(75, aiStats.cloudHitRatePercent)
        assertEquals(420L, aiStats.averageLatencyMs)
        assertEquals(75, aiStats.acceptanceRatePercent)
    }
}
