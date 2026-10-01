package com.zaycev.libshelper.ide.diagnostics

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.PathManager
import com.zaycev.libshelper.core.PLUGIN_VERSION
import com.zaycev.libshelper.core.log.ActionTrail
import com.zaycev.libshelper.core.log.DefaultDiagnosticFormatter
import com.zaycev.libshelper.core.log.DefaultTextRedactor
import com.zaycev.libshelper.core.log.DiagnosticReport
import com.zaycev.libshelper.core.log.JdkHostProbe
import com.zaycev.libshelper.core.log.publicHeadline
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

object LibsHelperDiagnostics {
    val trail = ActionTrail()
    private val lines = ArrayDeque<String>(LOG_LIMIT)

    fun record(category: String, action: String, detail: String? = null) {
        trail.record(category, action, detail?.let { DefaultTextRedactor.redact(it) })
    }

    @Synchronized
    fun log(category: String, message: String) {
        if (lines.size >= LOG_LIMIT) lines.removeFirst()
        val stamp = LocalTime.now().format(LOG_TIME)
        lines.addLast(DefaultTextRedactor.redact("$stamp [$category] $message"))
    }

    @Synchronized
    fun recentLog(): String = lines.joinToString("\n")

    fun report(summary: String, error: String?): DiagnosticReport = DiagnosticReport(
        pluginVersion = PLUGIN_VERSION,
        ideVersion = runCatching { ApplicationInfo.getInstance().fullVersion }.getOrNull(),
        os = System.getProperty("os.name").orEmpty(),
        summary = summary,
        actions = trail.snapshot(),
        error = error?.let { DefaultTextRedactor.redact(it) },
        host = JdkHostProbe().snapshot(),
    )

    fun render(report: DiagnosticReport): String = DefaultDiagnosticFormatter().format(report)

    fun write(body: String): Path {
        val directory = Path.of(PathManager.getLogPath(), "libshelper-reports")
        Files.createDirectories(directory)
        val stamp = LocalDateTime.now().format(FILE_TIME)
        val file = directory.resolve("libshelper-$stamp.md")
        val text = DefaultDiagnosticFormatter().wrapWithLog(body, recentLog())
        Files.writeString(file, text)
        return file
    }

    fun issueUrl(report: DiagnosticReport): String {
        val formatter = DefaultDiagnosticFormatter()
        return formatter.issueUrl("LibsHelper: ${report.publicHeadline()}", formatter.format(report))
    }

    fun issueUrl(title: String, body: String): String = DefaultDiagnosticFormatter().issueUrl(title, body)
}

private const val LOG_LIMIT = 400
private val LOG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
private val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
