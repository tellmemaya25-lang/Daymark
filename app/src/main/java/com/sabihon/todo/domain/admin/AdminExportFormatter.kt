package com.sabihon.todo.domain.admin

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pure, framework-free RFC-4180 CSV & JSON formatter for Admin Console exports.
 */
object AdminExportFormatter {

    private val isoFormatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    fun formatUsersCsv(users: List<AdminUserSummary>): String {
        val sb = StringBuilder()
        sb.append("uid,displayName,email,role,status,themePref,createdAtIso,lastLoginAtIso\n")
        for (u in users) {
            sb.append(escapeCsv(u.uid)).append(',')
                .append(escapeCsv(u.displayName)).append(',')
                .append(escapeCsv(u.email)).append(',')
                .append(escapeCsv(u.role.name)).append(',')
                .append(escapeCsv(if (u.isDisabled) "SUSPENDED" else "ACTIVE")).append(',')
                .append(escapeCsv(u.themePref)).append(',')
                .append(escapeCsv(formatIso(u.createdAtMillis))).append(',')
                .append(escapeCsv(formatIso(u.lastLoginAtMillis)))
                .append('\n')
        }
        return sb.toString()
    }

    fun formatTasksCsv(tasks: List<AdminTaskRecord>): String {
        val sb = StringBuilder()
        sb.append("taskId,ownerUid,ownerEmail,title,priority,category,completed,recurring,streak,subTasks,dueAtIso,createdAtIso\n")
        for (t in tasks) {
            val subTaskRatio = if (t.subTasksTotal > 0) "${t.subTasksDone}/${t.subTasksTotal}" else ""
            sb.append(escapeCsv(t.taskId)).append(',')
                .append(escapeCsv(t.ownerUid)).append(',')
                .append(escapeCsv(t.ownerEmail)).append(',')
                .append(escapeCsv(t.title)).append(',')
                .append(escapeCsv(t.priority.name)).append(',')
                .append(escapeCsv(t.categoryName)).append(',')
                .append(t.isCompleted).append(',')
                .append(t.isRecurring).append(',')
                .append(t.streak).append(',')
                .append(escapeCsv(subTaskRatio)).append(',')
                .append(escapeCsv(formatIso(t.dueAtMillis))).append(',')
                .append(escapeCsv(formatIso(t.createdAtMillis.takeIf { it > 0L })))
                .append('\n')
        }
        return sb.toString()
    }

    fun formatFullSnapshotJson(
        exportedAtMillis: Long,
        metrics: AdminPlatformMetrics,
        todayAi: AdminAiDailyStats,
        config: AdminSystemConfig,
        users: List<AdminUserSummary>,
        auditLogs: List<AdminAuditEntry>
    ): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"exportedAt\": ").append(quoteJson(formatIso(exportedAtMillis))).append(",\n")
        sb.append("  \"metrics\": {\n")
        sb.append("    \"totalUsers\": ").append(metrics.totalUsers).append(",\n")
        sb.append("    \"activeUsersLast7Days\": ").append(metrics.activeUsersLast7Days).append(",\n")
        sb.append("    \"activeAdmins\": ").append(metrics.activeAdmins).append(",\n")
        sb.append("    \"suspendedUsers\": ").append(metrics.suspendedUsers).append(",\n")
        sb.append("    \"totalTasks\": ").append(metrics.totalTasks).append(",\n")
        sb.append("    \"openTasks\": ").append(metrics.openTasks).append(",\n")
        sb.append("    \"completedTasks\": ").append(metrics.completedTasks).append(",\n")
        sb.append("    \"completionRatePercent\": ").append(metrics.completionRatePercent).append(",\n")
        sb.append("    \"overdueOpenTasks\": ").append(metrics.overdueOpenTasks).append(",\n")
        sb.append("    \"activeHabitTasks\": ").append(metrics.activeHabitTasks).append(",\n")
        sb.append("    \"totalCategories\": ").append(metrics.totalCategories).append("\n")
        sb.append("  },\n")
        sb.append("  \"aiToday\": {\n")
        sb.append("    \"dateKey\": ").append(quoteJson(todayAi.dateKey)).append(",\n")
        sb.append("    \"totalCalls\": ").append(todayAi.totalCalls).append(",\n")
        sb.append("    \"cloudSuccessCount\": ").append(todayAi.cloudSuccessCount).append(",\n")
        sb.append("    \"localFallbackCount\": ").append(todayAi.localFallbackCount).append(",\n")
        sb.append("    \"errorCount\": ").append(todayAi.errorCount).append(",\n")
        sb.append("    \"averageLatencyMs\": ").append(todayAi.averageLatencyMs).append("\n")
        sb.append("  },\n")
        sb.append("  \"systemConfig\": {\n")
        sb.append("    \"cloudAiGlobalEnabled\": ").append(config.cloudAiGlobalEnabled).append(",\n")
        sb.append("    \"prioritizeEnabled\": ").append(config.prioritizeEnabled).append(",\n")
        sb.append("    \"suggestionsEnabled\": ").append(config.suggestionsEnabled).append(",\n")
        sb.append("    \"dailyAiQuotaPerUser\": ").append(config.dailyAiQuotaPerUser).append(",\n")
        sb.append("    \"maintenanceMode\": ").append(config.maintenanceMode).append(",\n")
        sb.append("    \"announcementBanner\": ").append(quoteJson(config.announcementBanner)).append("\n")
        sb.append("  },\n")
        sb.append("  \"usersCount\": ").append(users.size).append(",\n")
        sb.append("  \"users\": [\n")
        users.forEachIndexed { idx, u ->
            sb.append("    {")
                .append("\"uid\": ").append(quoteJson(u.uid)).append(", ")
                .append("\"displayName\": ").append(quoteJson(u.displayName)).append(", ")
                .append("\"email\": ").append(quoteJson(u.email)).append(", ")
                .append("\"role\": ").append(quoteJson(u.role.name)).append(", ")
                .append("\"disabled\": ").append(u.isDisabled)
                .append("}")
            if (idx < users.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ],\n")
        sb.append("  \"recentAuditLogs\": [\n")
        auditLogs.take(25).forEachIndexed { idx, entry ->
            sb.append("    {")
                .append("\"action\": ").append(quoteJson(entry.action)).append(", ")
                .append("\"actorEmail\": ").append(quoteJson(entry.actorEmail)).append(", ")
                .append("\"targetId\": ").append(quoteJson(entry.targetId)).append(", ")
                .append("\"summary\": ").append(quoteJson(entry.summary)).append(", ")
                .append("\"createdAt\": ").append(quoteJson(formatIso(entry.createdAtMillis)))
                .append("}")
            if (idx < minOf(auditLogs.size, 25) - 1) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private val CSV_FORMULA_TRIGGERS = charArrayOf('=', '+', '-', '@', '\t', '\r')
    private val CSV_FORMULA_LEADING_TRIGGERS = charArrayOf('=', '+', '-', '@')

    /**
     * OWASP CSV / Spreadsheet Formula Injection defense:
     * Any cell beginning with `=`, `+`, `-`, `@`, `\t`, or `\r` (or leading spaces
     * followed by `=`, `+`, `-`, `@`) is prefixed with a single quote (`'`) so Excel,
     * LibreOffice Calc, and Google Sheets treat it strictly as literal text.
     */
    internal fun neutralizeCsvFormula(raw: String): String {
        if (raw.isEmpty()) return raw
        val firstNonSpace = raw.firstOrNull { it != ' ' }
        val isFormulaTrigger = raw[0] in CSV_FORMULA_TRIGGERS ||
            (firstNonSpace != null && firstNonSpace in CSV_FORMULA_LEADING_TRIGGERS)
        return if (isFormulaTrigger) "'" + raw else raw
    }

    internal fun escapeCsv(raw: String): String {
        val safe = neutralizeCsvFormula(raw)
        val needsQuotes = safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) return safe
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }

    private fun quoteJson(raw: String): String {
        val sb = StringBuilder(raw.length + 2)
        sb.append('"')
        for (c in raw) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) {
                    sb.append(String.format("\\u%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun formatIso(epochMillis: Long?): String {
        if (epochMillis == null || epochMillis <= 0L) return ""
        return isoFormatter.format(Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC))
    }
}
