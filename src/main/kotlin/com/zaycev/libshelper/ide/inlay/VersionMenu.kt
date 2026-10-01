package com.zaycev.libshelper.ide.inlay

import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.zaycev.libshelper.core.inlay.contextMenuVersions
import com.zaycev.libshelper.core.settings.settingLibraries
import com.zaycev.libshelper.core.model.LibraryAdvice
import com.zaycev.libshelper.core.model.OfferScore
import com.zaycev.libshelper.core.model.VersionCandidate
import com.zaycev.libshelper.core.model.VersionChannel
import com.zaycev.libshelper.core.model.displayTarget
import com.zaycev.libshelper.ide.LibsHelperService
import com.zaycev.libshelper.ide.diagnostics.LibsHelperDiagnostics
import com.zaycev.libshelper.ide.i18n.msg
import com.zaycev.libshelper.ide.ui.channelLabel
import java.awt.event.MouseEvent

class LibsHelperInlayMenuGroup : DefaultActionGroup() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun getChildren(event: AnActionEvent?): Array<AnAction> {
        val data = event?.dataContext ?: return emptyArray()
        val provider = InlayHintsProvider.PROVIDER_ID.getData(data)
        if (provider != null && provider != DECLARATIVE_PROVIDER_ID) return emptyArray()
        val payloads = InlayHintsProvider.INLAY_PAYLOADS.getData(data) ?: return emptyArray()
        val key = payloads.values.filterIsInstance<StringInlayActionPayload>().firstOrNull()?.text ?: return emptyArray()
        val project = event.project ?: return emptyArray()
        return versionActions(project, key)
    }
}

internal class InlayVersionMouseListener : EditorMouseListener {
    override fun mouseClicked(event: EditorMouseEvent) {
        if (event.mouseEvent.button != MouseEvent.BUTTON3) return
        val editor = event.editor
        val project = editor.project ?: return
        val key = hintKeyAt(project, editor, event.mouseEvent) ?: return
        LibsHelperDiagnostics.record("ui", "inlay-menu", key)
        if (showVersionMenu(project, editor, key)) event.consume()
    }
}

private fun versionActions(project: com.intellij.openapi.project.Project, key: String): Array<AnAction> {
    val item = findAdvice(project, key) ?: return emptyArray()
    val grouped = contextMenuVersions(item.advice).groupBy { it.channel }
    val actions = mutableListOf<AnAction>()
    for (channel in listOf(
        VersionChannel.Stable,
        VersionChannel.ReleaseCandidate,
        VersionChannel.Beta,
    )) {
        val versions = grouped[channel].orEmpty()
        if (versions.isEmpty()) continue
        actions += object : AnAction(channelLabel(channel)) {
            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = false
            }

            override fun actionPerformed(event: AnActionEvent) = Unit
        }
        versions.forEach { candidate ->
            actions += object : AnAction(labelOf(item, candidate)) {
                override fun actionPerformed(event: AnActionEvent) {
                    applyCandidate(project, item, candidate.version.raw)
                }
            }
        }
    }
    return actions.toTypedArray()
}

private fun showVersionMenu(project: com.intellij.openapi.project.Project, editor: Editor, key: String): Boolean {
    val item = findAdvice(project, key) ?: return false
    val versions = contextMenuVersions(item.advice)
    if (versions.isEmpty()) return false
    val title = item.advice.dependency.catalogAlias ?: item.advice.dependency.coordinates.artifact
    val step = object : BaseListPopupStep<VersionCandidate>(title, versions) {
        override fun getTextFor(value: VersionCandidate): String = labelOf(item, value)

        override fun getSeparatorAbove(value: VersionCandidate): ListSeparator? {
            val index = versions.indexOf(value)
            val heading = channelLabel(value.channel)
            if (index <= 0) return ListSeparator(heading)
            val previous = versions[index - 1]
            return if (previous.channel != value.channel) ListSeparator(heading) else null
        }

        override fun onChosen(selectedValue: VersionCandidate, finalChoice: Boolean): PopupStep<*>? {
            applyCandidate(project, item, selectedValue.version.raw)
            return FINAL_CHOICE
        }
    }
    JBPopupFactory.getInstance().createListPopup(step).showInBestPositionFor(editor)
    return true
}

private fun labelOf(item: LibraryAdvice, candidate: VersionCandidate): String {
    val current = item.advice.current?.raw
    val recommended = item.advice.displayTarget()?.version
    val marks = buildList {
        if (candidate.version.raw == current) add(msg("inlay.menu.current"))
        if (candidate.version.raw == recommended) add(candidate.scoreMark())
    }
    val suffix = if (marks.isEmpty()) "" else " · " + marks.joinToString(" · ")
    return candidate.version.raw + suffix
}

private fun VersionCandidate.scoreMark(): String = when (channel) {
    VersionChannel.Stable -> OfferScore.Recommended.name
    else -> channel.name
}

private fun findAdvice(project: com.intellij.openapi.project.Project, key: String): LibraryAdvice? {
    val report = project.service<LibsHelperService>().lastReport ?: return null
    return (report.libraries + settingLibraries(report)).firstOrNull {
        it.advice.dependency.coordinates.key == key || it.advice.dependency.catalogAlias == key
    }
}

private fun applyCandidate(project: com.intellij.openapi.project.Project, item: LibraryAdvice, version: String) {
    LibsHelperDiagnostics.record("ui", "apply-version", "${item.advice.dependency.coordinates.key} $version")
    project.service<LibsHelperService>().enqueueApply(item.advice.dependency, version)
}

private fun hintKeyAt(project: com.intellij.openapi.project.Project, editor: Editor, event: MouseEvent): String? {
    val offset = editor.logicalPositionToOffset(editor.xyToLogicalPosition(event.point))
    val line = editor.document.getLineNumber(offset) + 1
    val file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.document) ?: return null
    val root = com.zaycev.libshelper.ide.project.primaryGradleRoot(project)?.toString()?.replace('\\', '/') ?: return null
    val path = file.path.replace('\\', '/')
    val prefix = root.trimEnd('/') + "/"
    if (!path.startsWith(prefix)) return null
    val relative = path.removePrefix(prefix)
    val report = project.service<LibsHelperService>().lastReport ?: return null
    return com.zaycev.libshelper.core.inlay.dependencyHints(report)
        .firstOrNull { it.relativePath == relative && it.line == line }
        ?.coordinatesKey
}
