package com.zaycev.libshelper.core.log

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class DefaultDiagnosticFormatter(
    private val repository: String = "Anton-Zaitsev/LibsHelperPlugin",
    private val redactor: TextRedactor = DefaultTextRedactor,
    zone: ZoneId = ZoneId.systemDefault(),
) : DiagnosticFormatter {
    private val zoneLabel = zone.id
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(zone)
    private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone)

    override fun format(report: DiagnosticReport): String = redact(
        buildString {
            appendLine("# LibsHelper ${report.pluginVersion}")
            appendLine()
            appendMachine(report)
            appendSummary(report)
            appendErrorHeadline(report.error)
            appendActions(report)
            appendStack(report.error)
        },
    )

    fun wrapWithLog(body: String, log: String?): String = redact(
        buildString {
            append(body.trimEnd())
            appendLine()
            if (!log.isNullOrBlank()) {
                appendLine()
                appendPluginLog(log)
            }
        },
    )

    override fun issueUrl(title: String, body: String, repo: String): String {
        val limited = limit(body)
        val encodedTitle = java.net.URLEncoder.encode(title, Charsets.UTF_8)
        val encodedBody = java.net.URLEncoder.encode(limited, Charsets.UTF_8)
        return "https://github.com/$repo/issues/new?title=$encodedTitle&body=$encodedBody&labels=bug"
    }

    fun issueUrl(title: String, body: String): String = issueUrl(title, body, repository)

    private fun redact(text: String): String = redactor.redact(text, System.getProperty("user.home"))

    private fun limit(body: String): String {
        val redacted = redactor.redact(body, System.getProperty("user.home"))
        if (redacted.length <= MAX_URL_BODY) return redacted
        return redacted.take(MAX_URL_BODY) + "\n…(truncated, full report was saved locally)"
    }

    private fun StringBuilder.appendMachine(report: DiagnosticReport) {
        appendLine("## Machine")
        appendLine()
        appendLine("| | |")
        appendLine("| --- | --- |")
        appendLine("| IDE | ${cell(report.ideVersion ?: "unknown")} |")
        val host = report.host
        if (host == null) {
            appendLine("| OS | ${cell(report.os.ifBlank { "unknown" })} |")
            appendLine()
            return
        }
        appendLine("| OS | ${cell("${host.osName} ${host.osVersion} (${host.arch})")} |")
        row("Model", host.cpu.model)
        row("CPU", host.cpu.name)
        row("Cores", host.cpu.describeCores())
        row("Core layout", host.cpu.describeClusters())
        row("Memory", host.memory.describeInstalled())
        row("JVM", listOf(host.jvmName, host.jvmVersion).filter { it.isNotBlank() }.joinToString(" "))
        row("Heap", host.memory.describeHeap())
        appendLine()
    }

    private fun StringBuilder.row(label: String, value: String?) {
        if (value.isNullOrBlank()) return
        appendLine("| ${cell(label)} | ${cell(value)} |")
    }

    private fun StringBuilder.appendSummary(report: DiagnosticReport) {
        val summary = report.visibleSummary() ?: return
        appendLine("## Summary")
        appendLine()
        appendLine(summary)
        appendLine()
    }

    private fun StringBuilder.appendErrorHeadline(error: String?) {
        val text = error?.trim().orEmpty()
        if (text.isEmpty()) return
        val head = text.lineSequence().first()
        appendLine("## Error")
        appendLine()
        appendLine("> [!CAUTION]")
        appendLine("> ${colored(cell(head), RED)}")
        appendLine()
    }

    private fun StringBuilder.appendActions(report: DiagnosticReport) {
        appendLine("## Actions")
        appendLine()
        val recent = report.actions.takeLast(RECENT_ACTIONS)
        if (recent.isEmpty()) {
            appendLine("No actions.")
            appendLine()
            return
        }
        appendLine("Times use `$zoneLabel`.")
        appendLine()
        val grouped = group(recent)
        val signal = grouped.filter { it.event.gesture() == null }
        val gestures = grouped.filter { it.event.gesture() != null }
        if (signal.isNotEmpty()) {
            appendLine("| Time | Category | Action | Detail |")
            appendLine("| --- | --- | --- | --- |")
            signal.forEach { group ->
                appendLine("| ${group.whenLabel()} | ${cell(group.event.category)} | `${cell(group.event.action)}` | ${detailCell(group)} |")
            }
            appendLine()
        }
        if (gestures.isNotEmpty()) appendGestures(gestures)
    }

    private fun StringBuilder.appendGestures(groups: List<ActionGroup>) {
        appendLine("<details>")
        appendLine("<summary>${colored(gestureSummary(groups), BLUE)}</summary>")
        appendLine()
        appendLine("> [!NOTE]")
        appendLine("> ${colored("Blue", BLUE)} marks are clicks and inlay menus. They do not change dependencies.")
        appendLine()
        appendLine("| Time | | Action | Detail |")
        appendLine("| --- | --- | --- | --- |")
        groups.forEach { group ->
            val mark = if (group.event.gesture() == Gesture.Menu) MENU_MARK else CLICK_MARK
            appendLine("| ${group.whenLabel()} | $mark | `${cell(group.event.action)}` | ${detailCell(group)} |")
        }
        appendLine()
        appendLine("</details>")
        appendLine()
    }

    private fun StringBuilder.appendStack(error: String?) {
        val text = error?.trim().orEmpty()
        if (text.lines().size <= 1) return
        appendLine("<details>")
        appendLine("<summary>${colored("Stack trace", RED)}</summary>")
        appendLine()
        appendLine(fence(text))
        appendLine()
        appendLine("</details>")
        appendLine()
    }

    private fun StringBuilder.appendPluginLog(log: String) {
        val lines = log.lines().size
        appendLine("## Log")
        appendLine()
        appendLine("<details>")
        appendLine("<summary>Plugin log ($lines lines)</summary>")
        appendLine()
        appendLine(fence(log))
        appendLine()
        appendLine("</details>")
        appendLine()
    }

    private fun group(actions: List<ActionEvent>): List<ActionGroup> {
        val groups = mutableListOf<ActionGroup>()
        actions.forEach { event ->
            val previous = groups.lastOrNull()
            if (previous != null && previous.event.sameAs(event)) {
                groups[groups.lastIndex] = previous.copy(count = previous.count + 1, lastAtEpochMs = event.atEpochMs)
            } else {
                groups += ActionGroup(event, count = 1, lastAtEpochMs = event.atEpochMs)
            }
        }
        return groups
    }

    private fun ActionGroup.whenLabel(): String {
        val start = timeFormatter.format(Instant.ofEpochMilli(event.atEpochMs))
        if (count == 1) return start
        if (lastAtEpochMs == event.atEpochMs) return "$start ×$count"
        val end = clockFormatter.format(Instant.ofEpochMilli(lastAtEpochMs))
        return "$start–$end ×$count"
    }

    private fun detailCell(group: ActionGroup): String {
        val detail = group.event.detail?.takeIf { it.isNotBlank() } ?: return "—"
        return "`${cell(detail)}`"
    }

    private fun gestureSummary(groups: List<ActionGroup>): String {
        val total = groups.sumOf { it.count }
        val title = when {
            groups.all { it.event.gesture() == Gesture.Click } -> "Clicks"
            groups.all { it.event.gesture() == Gesture.Menu } -> "Menus"
            else -> "Clicks and menus"
        }
        val kinds = groups.groupBy { it.event.action }
            .entries
            .sortedByDescending { (_, items) -> items.sumOf { it.count } }
            .take(SUMMARY_KINDS)
            .joinToString(", ") { (action, items) -> "${cell(action)} ×${items.sumOf { it.count }}" }
        return "🔵 $title ($total) — $kinds"
    }

    private fun fence(text: String): String = "```text\n${text.replace("```", "'''")}\n```"

    private fun cell(value: String): String {
        val flat = WHITESPACE.replace(value, " ").trim()
        val escaped = flat
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("|", "\\|")
            .replace("`", "'")
        if (escaped.length <= MAX_CELL) return escaped
        return escaped.take(MAX_CELL) + "…"
    }

    private data class ActionGroup(
        val event: ActionEvent,
        val count: Int,
        val lastAtEpochMs: Long,
    )

    private enum class Gesture { Click, Menu }

    private fun ActionEvent.gesture(): Gesture? {
        val name = action.lowercase()
        return when {
            name == "click" || name.endsWith("-click") -> Gesture.Click
            name.endsWith("-menu") -> Gesture.Menu
            else -> null
        }
    }

    private fun ActionEvent.sameAs(other: ActionEvent): Boolean =
        category == other.category && action == other.action && detail == other.detail

    private companion object {
        const val RECENT_ACTIONS = 40
        const val MAX_URL_BODY = 1500
        const val MAX_CELL = 200
        const val SUMMARY_KINDS = 3
        const val BLUE = "#0969da"
        const val RED = "#d1242f"
        const val CLICK_MARK = "🔵 <span style=\"color:" + BLUE + "\">click</span>"
        const val MENU_MARK = "🔵 <span style=\"color:" + BLUE + "\">menu</span>"
        val WHITESPACE = Regex("""\s+""")

        fun colored(text: String, color: String): String = "<span style=\"color:$color\">$text</span>"
    }
}

private fun MachineCpu.describeCores(): String {
    val physical = physicalCores?.let { "$logicalCores logical · $it physical" } ?: "$logicalCores logical"
    if (jvmProcessors == logicalCores) return physical
    return "$physical · JVM $jvmProcessors"
}

private fun MachineCpu.describeClusters(): String? {
    if (clusters.size <= 1) return null
    return clusters.joinToString(" · ") { "${it.cores} ${it.name}" }
}

private fun MachineMemory.describeInstalled(): String? {
    val installed = totalBytes?.let(::formatBytes) ?: return null
    val free = freeBytes?.let(::formatBytes) ?: return "$installed installed"
    return "$installed installed · $free free"
}

private fun MachineMemory.describeHeap(): String {
    val used = formatBytes(heapUsedBytes)
    val max = if (heapMaxBytes > 0L) formatBytes(heapMaxBytes) else "no limit"
    return "$used used / $max max"
}

private fun formatBytes(bytes: Long): String {
    val gib = bytes.toDouble() / BYTES_IN_GIB
    if (gib >= 1.0) return String.format(Locale.US, "%.1f GB", gib)
    val mib = bytes.toDouble() / BYTES_IN_MIB
    if (mib >= 1.0) return String.format(Locale.US, "%.0f MB", mib)
    val kib = bytes.toDouble() / BYTES_IN_KIB
    return String.format(Locale.US, "%.0f KB", kib)
}

private const val BYTES_IN_KIB = 1024.0
private const val BYTES_IN_MIB = BYTES_IN_KIB * BYTES_IN_KIB
private const val BYTES_IN_GIB = BYTES_IN_MIB * BYTES_IN_KIB
