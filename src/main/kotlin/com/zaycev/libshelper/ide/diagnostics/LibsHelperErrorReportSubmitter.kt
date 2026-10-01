package com.zaycev.libshelper.ide.diagnostics

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.Consumer
import com.zaycev.libshelper.core.log.publicHeadline
import com.zaycev.libshelper.ide.i18n.msg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.datatransfer.StringSelection

class LibsHelperErrorReportSubmitter : ErrorReportSubmitter() {
    override fun getReportActionText(): String = msg("action.report.text")

    override fun submit(
        events: Array<out IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component,
        consumer: Consumer<in SubmittedReportInfo>,
    ): Boolean {
        val error = events.joinToString("\n\n") { event ->
            val thrown = event.throwableText
            redactForDialog(event.message + "\n" + thrown)
        }
        val summary = additionalInfo?.ifBlank { null } ?: events.firstOrNull()?.message ?: "LibsHelper error"
        val report = LibsHelperDiagnostics.report(summary, error)
        val text = LibsHelperDiagnostics.render(report)
        val edited = Messages.showMultilineInputDialog(
            null,
            msg("error.report.title"),
            msg("action.report.text"),
            text,
            null,
            null,
        )
        if (edited == null) {
            consumer.consume(SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.FAILED))
            return false
        }
        val safe = redactForDialog(edited)
        val saved = LibsHelperDiagnostics.write(safe)
        CopyPasteManager.getInstance().setContents(StringSelection(safe))
        BrowserUtil.browse(LibsHelperDiagnostics.issueUrl("LibsHelper: ${report.publicHeadline()}", safe))
        consumer.consume(SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.NEW_ISSUE))
        LibsHelperDiagnostics.record("ui", "error-report", saved.fileName.toString())
        return true
    }
}

class ReportProblemAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        val report = LibsHelperDiagnostics.report("", null)
        val text = LibsHelperDiagnostics.render(report)
        val saved = LibsHelperDiagnostics.write(text)
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        BrowserUtil.browse(LibsHelperDiagnostics.issueUrl(report))
        LibsHelperDiagnostics.record("ui", "report-problem", "${saved.fileName} ${event.place}")
    }
}

private fun redactForDialog(text: String): String = com.zaycev.libshelper.core.log.DefaultTextRedactor.redact(text)

internal suspend fun reportOnEdt(project: Project?, block: () -> Unit) {
    withContext(Dispatchers.EDT) {
        if (project?.isDisposed == true) return@withContext
        block()
    }
}
