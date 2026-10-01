package com.zaycev.libshelper.ide.inlay

import com.intellij.codeInsight.hints.ChangeListener
import com.intellij.codeInsight.hints.FactoryInlayHintsCollector
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsProviderFactory
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.zaycev.libshelper.ide.diagnostics.LibsHelperDiagnostics
import com.zaycev.libshelper.ide.i18n.msg
import javax.swing.JComponent
import javax.swing.JPanel

class LibsHelperInlayProviderFactory : InlayHintsProviderFactory {
    private val provider = LibsHelperInlayProvider()

    override fun getLanguages(): Iterable<Language> = setOfNotNull(
        Language.findLanguageByID("kotlin"),
        Language.findLanguageByID("Groovy"),
        Language.findLanguageByID("TOML"),
    )

    override fun getProvidersInfoForLanguage(language: Language): List<InlayHintsProvider<out Any>> {
        if (!supportsHintLanguage(language)) return emptyList()
        return listOf(provider)
    }
}

private class LibsHelperInlayProvider : InlayHintsProvider<NoSettings> {
    override val isVisibleInSettings: Boolean
        get() = false

    override val key: SettingsKey<NoSettings> = SettingsKey("libshelper.dependency.badge")
    override val name: String
        get() = msg("inlay.name")
    override val previewText: String = """version = "1.0.0""""

    override fun createSettings(): NoSettings = NoSettings()

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable =
        object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JComponent = JPanel()
        }

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink,
    ): InlayHintsCollector = BadgeCollector(editor)
}

private class BadgeCollector(
    editor: Editor,
) : FactoryInlayHintsCollector(editor) {
    private var placed = false

    override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
        if (placed || element != element.containingFile) return true
        placed = true
        if (!hintsProviderEnabled()) return false
        val file = element.containingFile
        val document = editor.document
        for (hint in boundHints(file, editor)) {
            if (!hintCaseEnabled(hint.kind)) continue
            val line = hint.line - 1
            if (line < 0 || line >= document.lineCount) continue
            val presentation = dependencyHintPresentation(editor, factory, hint) { selected ->
                LibsHelperDiagnostics.record("ui", "inlay-click", selected.coordinatesKey)
                openLibrary(
                    file.project,
                    selected.coordinatesKey,
                    selected.catalogAlias ?: selected.coordinatesKey,
                )
            }
            sink.addInlineElement(document.getLineEndOffset(line), true, presentation, false)
        }
        return false
    }
}
