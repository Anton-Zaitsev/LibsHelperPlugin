package com.zaycev.libshelper.core.log

interface LibsHelperLogger {
    fun debug(message: String)
    fun info(message: String)
    fun warn(message: String, error: Throwable? = null)
    fun error(message: String, error: Throwable? = null)
}

data class ActionEvent(
    val atEpochMs: Long,
    val category: String,
    val action: String,
    val detail: String?,
)

data class CoreCluster(
    val name: String,
    val cores: Int,
)

data class MachineCpu(
    val name: String?,
    val model: String?,
    val logicalCores: Int,
    val physicalCores: Int?,
    val jvmProcessors: Int,
    val clusters: List<CoreCluster> = emptyList(),
)

data class MachineMemory(
    val totalBytes: Long?,
    val freeBytes: Long?,
    val heapUsedBytes: Long,
    val heapMaxBytes: Long,
)

data class HostSnapshot(
    val osName: String,
    val osVersion: String,
    val arch: String,
    val cpu: MachineCpu,
    val memory: MachineMemory,
    val jvmName: String,
    val jvmVersion: String,
)

data class DiagnosticReport(
    val pluginVersion: String,
    val ideVersion: String?,
    val os: String,
    val summary: String,
    val actions: List<ActionEvent>,
    val error: String?,
    val host: HostSnapshot? = null,
)

private const val HEADLINE_CHARS = 120
private val PROJECT_NAME_ONLY = Regex("""Project \S+""")

/** Issue title. A bare `Project <name>` is dropped — it does not explain the failure. */
fun DiagnosticReport.publicHeadline(): String {
    val line = summary.lineSequence().firstOrNull().orEmpty().trim()
    if (line.isEmpty() || PROJECT_NAME_ONLY.matches(line)) return "Diagnostic report"
    return line.take(HEADLINE_CHARS)
}

/** Summary worth showing. A bare project name is omitted. */
fun DiagnosticReport.visibleSummary(): String? {
    val text = summary.trim()
    if (text.isEmpty() || PROJECT_NAME_ONLY.matches(text)) return null
    return text
}

interface ActionLog {
    fun record(category: String, action: String, detail: String? = null)
    fun snapshot(): List<ActionEvent>
}

interface TextRedactor {
    fun redact(text: String, home: String?): String
}

interface DiagnosticFormatter {
    fun format(report: DiagnosticReport): String
    fun issueUrl(title: String, body: String, repo: String): String
}
