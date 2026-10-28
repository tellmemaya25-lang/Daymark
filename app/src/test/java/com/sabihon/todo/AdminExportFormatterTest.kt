package com.sabihon.todo

import com.sabihon.todo.domain.admin.AdminAiDailyStats
import com.sabihon.todo.domain.admin.AdminAuditEntry
import com.sabihon.todo.domain.admin.AdminExportFormatter
import com.sabihon.todo.domain.admin.AdminPlatformMetrics
import com.sabihon.todo.domain.admin.AdminSystemConfig
import com.sabihon.todo.domain.admin.AdminTaskRecord
import com.sabihon.todo.domain.admin.AdminUserSummary
import com.sabihon.todo.domain.admin.UserRole
import com.sabihon.todo.domain.model.Priority
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminExportFormatterTest {

    @Test
    fun `formatUsersCsv escapes commas and quotes per RFC-4180`() {
        val users = listOf(
            AdminUserSummary(
                uid = "uid-1",
                displayName = "Maya, \"Admin\"",
                email = "tellmemaya25@gmail.com",
                role = UserRole.ADMIN,
                isDisabled = false,
                themePref = "DARK",
                createdAtMillis = 1_700_000_000_000L
            )
        )
        val csv = AdminExportFormatter.formatUsersCsv(users)
        assertTrue(csv.startsWith("uid,displayName,email,role,status,themePref"))
        assertTrue(csv.contains("\"Maya, \"\"Admin\"\"\""))
        assertTrue(csv.contains("tellmemaya25@gmail.com,ADMIN,ACTIVE,DARK"))
    }

    @Test
    fun `formatTasksCsv includes checklist ratio and priority`() {
        val tasks = listOf(
            AdminTaskRecord(
                taskId = "task-99",
                ownerUid = "uid-1",
                ownerName = "Maya",
                ownerEmail = "tellmemaya25@gmail.com",
                title = "Review Q4 roadmap, items",
                priority = Priority.URGENT,
                categoryName = "Work",
                isCompleted = false,
                isRecurring = true,
                streak = 6,
                subTasksDone = 2,
                subTasksTotal = 4
            )
        )
        val csv = AdminExportFormatter.formatTasksCsv(tasks)
        assertTrue(csv.contains("\"Review Q4 roadmap, items\""))
        assertTrue(csv.contains("URGENT,Work,false,true,6,2/4"))
    }

    @Test
    fun `formatFullSnapshotJson serializes metrics config users and audit logs`() {
        val json = AdminExportFormatter.formatFullSnapshotJson(
            exportedAtMillis = 1_700_000_000_000L,
            metrics = AdminPlatformMetrics(totalUsers = 3, totalTasks = 12, completedTasks = 9),
            todayAi = AdminAiDailyStats(dateKey = "2026-10-28", totalCalls = 10L, cloudSuccessCount = 8L),
            config = AdminSystemConfig(dailyAiQuotaPerUser = 100, announcementBanner = "Hello \"Daymark\""),
            users = listOf(
                AdminUserSummary(uid = "u1", displayName = "Maya", email = "tellmemaya25@gmail.com", role = UserRole.ADMIN)
            ),
            auditLogs = listOf(
                AdminAuditEntry(
                    id = "log1",
                    actorEmail = "tellmemaya25@gmail.com",
                    action = "UPDATE_SYSTEM_CONFIG",
                    targetId = "system_config/global",
                    summary = "Updated quota",
                    createdAtMillis = 1_700_000_000_000L
                )
            )
        )
        assertTrue(json.contains("\"totalUsers\": 3"))
        assertTrue(json.contains("\"completionRatePercent\": 75"))
        assertTrue(json.contains("\"dailyAiQuotaPerUser\": 100"))
        assertTrue(json.contains("\"announcementBanner\": \"Hello \\\"Daymark\\\"\""))
        assertTrue(json.contains("\"action\": \"UPDATE_SYSTEM_CONFIG\""))
    }

    @Test
    fun `escapeCsv neutralizes leading spreadsheet formula triggers`() {
        val tasks = listOf(
            AdminTaskRecord(
                taskId = "task-sec",
                ownerUid = "uid-1",
                ownerName = "User",
                ownerEmail = "user@example.com",
                title = "=HYPERLINK(\"https://malicious.example\",\"Open\")",
                priority = Priority.HIGH,
                categoryName = "@SUM(1+1)",
                isCompleted = false,
                isRecurring = false,
                streak = 0,
                subTasksDone = 0,
                subTasksTotal = 0
            )
        )
        val csv = AdminExportFormatter.formatTasksCsv(tasks)
        assertTrue(csv.contains("\"'=HYPERLINK("))
        assertTrue(csv.contains("'@SUM(1+1)"))
    }
}
