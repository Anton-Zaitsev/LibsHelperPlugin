package com.zaycev.libshelper.ide.inlay

import com.intellij.codeInsight.hints.ChangeListener
import com.intellij.codeInsight.hints.FactoryInlayHintsCollector
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayGroup
import com.intellij.codeInsight.hints.InlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.lang.Language
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.zaycev.libshelper.core.inlay.DependencyHint
import com.zaycev.libshelper.core.inlay.DependencyHintKind
import com.zaycev.libshelper.core.inlay.bindHintToCurrentText
import com.zaycev.libshelper.core.inlay.dependencyHints
import com.zaycev.libshelper.ide.LibsHelperService
import com.zaycev.libshelper.ide.i18n.msg
import javax.swing.JPanel

class LibsHelperDependencyInlayHintsProvider : InlayHintsProvider<DependencyHintSettings>, DumbAware {
    override val key: SettingsKey<DependencyHintSettings> = KEY
    override val name: String
        get() = msg("inlay.name")
    override val group: InlayGroup = InlayGroup.OTHER_GROUP
    override val previewText: String = PREVIEW_TEXT
    override val description: String
        get() = msg("inlay.description")

    override fun createSettings(): DependencyHintSettings = DependencyHintSettings()

    override fun getSettingsLanguage(language: Language): Language = hintsSettingsLanguage()

    override fun createConfigurable(settings: DependencyHintSettings): ImmediateConfigurable {
        return object : ImmediateConfigurable {
            override val cases: List<ImmediateConfigurable.Case> = listOf(
                ImmediateConfigurable.Case(msg("inlay.case.outdated"), "outdated", settings::showOutdated),
                ImmediateConfigurable.Case(msg("inlay.case.current"), "current", settings::showCurrent),
                ImmediateConfigurable.Case(msg("inlay.case.prerelease"), "prerelease", settings::showPrerelease),
            )

            override fun createComponent(listener: ChangeListener) = JPanel()
        }
    }

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: DependencyHintSettings,
        sink: InlayHintsSink,
    ): InlayHintsCollector? {
        if (!isDependencyHintFile(file.name)) return null
        val hints = hintsFor(file, settings)
        if (hints.isEmpty()) return null
        val project = file.project
        return DependencyHintCollector(editor, hints) { hint ->
            openLibrary(project, hint.coordinatesKey)
        }
    }

    override fun getProperty(key: String): String? = runCatching { msg(key) }.getOrNull()

    private fun hintsFor(file: PsiFile, settings: DependencyHintSettings): List<DependencyHint> {
        if (!file.isPhysical) return previewHints().filter { matchesSettings(it, settings) }
        val project = file.project
        if (project.isDefault) return emptyList()
        val report = project.service<LibsHelperService>().lastReport ?: return emptyList()
        val relative = relativePathOf(project, file) ?: return emptyList()
        return dependencyHints(report)
            .filter { it.relativePath == relative }
            .filter { matchesSettings(it, settings) }
    }
}

private class DependencyHintCollector(
    editor: Editor,
    private val hints: List<DependencyHint>,
    private val onClick: (DependencyHint) -> Unit,
) : FactoryInlayHintsCollector(editor) {
    private var collected = false

    override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
        if (collected) return false
        if (element != element.containingFile) return true
        collected = true
        val document = editor.document
        val fileText = document.text
        for (hint in hints) {
            val bound = bindHintToCurrentText(hint, fileText) ?: continue
            val lineIndex = bound.line - 1
            if (lineIndex < 0 || lineIndex >= document.lineCount) continue
            val end = document.getLineEndOffset(lineIndex)
            sink.addInlineElement(
                end,
                true,
                dependencyHintPresentation(editor, factory, bound, onClick),
                false,
            )
        }
        return false
    }
}

internal fun hintsSettingsLanguage(): Language =
    Language.findLanguageByID("kotlin")
        ?: Language.findLanguageByID("TOML")
        ?: Language.findLanguageByID("Groovy")
        ?: Language.ANY

internal fun relativePathOf(project: Project, file: PsiFile): String? {
    val root = project.basePath ?: return null
    val virtual = file.virtualFile ?: file.originalFile.virtualFile ?: return null
    val path = virtual.path.replace('\\', '/')
    val prefix = root.replace('\\', '/').trimEnd('/') + '/'
    if (!path.startsWith(prefix)) return null
    return path.removePrefix(prefix)
}

internal fun openLibrary(project: Project, coordinatesKey: String) {
    if (project.isDisposed || project.isDefault) return
    project.service<LibsHelperService>().selectLibrary(coordinatesKey)
    ToolWindowManager.getInstance(project).getToolWindow("LibsHelper")?.activate(null)
}

private fun previewHints(): List<DependencyHint> = listOf(
    DependencyHint(
        relativePath = "build.gradle.kts",
        line = 2,
        needle = "com.squareup.okhttp3:okhttp",
        coordinatesKey = "com.squareup.okhttp3:okhttp",
        kind = DependencyHintKind.Outdated,
        currentVersion = "4.12.0",
        recommendedVersion = "5.0.0",
        catalogAlias = null,
    ),
)

private val KEY = SettingsKey<DependencyHintSettings>("libshelper.dependency.status")

private val PREVIEW_TEXT = """
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
""".trimIndent()
