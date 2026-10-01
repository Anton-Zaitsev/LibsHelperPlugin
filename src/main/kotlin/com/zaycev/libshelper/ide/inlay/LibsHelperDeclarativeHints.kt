package com.zaycev.libshelper.ide.inlay

import com.intellij.codeInsight.hints.declarative.DeclarativeInlayHintsSettings
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayHintsProviderFactory
import com.intellij.codeInsight.hints.declarative.InlayOptionInfo
import com.intellij.codeInsight.hints.declarative.InlayProviderInfo
import com.intellij.lang.Language
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.zaycev.libshelper.core.inlay.DependencyHint
import com.zaycev.libshelper.core.inlay.DependencyHintKind
import com.zaycev.libshelper.core.inlay.dependencyHints
import com.zaycev.libshelper.core.inlay.hintsOnCurrentText
import com.zaycev.libshelper.ide.LibsHelperService
import com.zaycev.libshelper.ide.i18n.msg
import java.util.concurrent.atomic.AtomicReference

internal const val DECLARATIVE_PROVIDER_ID = "libshelper.dependency.status"

class LibsHelperDeclarativeInlayProviderFactory : InlayHintsProviderFactory {
    private val provider = LibsHelperDeclarativeHints()

    override fun getProvidersForLanguage(language: Language): List<InlayProviderInfo> {
        if (!supportsHintLanguage(language)) return emptyList()
        return listOf(info())
    }

    override fun getSupportedLanguages(): Set<Language> = setOfNotNull(
        Language.findLanguageByID("kotlin"),
        Language.findLanguageByID("Groovy"),
        Language.findLanguageByID("TOML"),
    )

    override fun getProviderInfo(language: Language, providerId: String): InlayProviderInfo? {
        if (providerId != DECLARATIVE_PROVIDER_ID || !supportsHintLanguage(language)) return null
        return info()
    }

    private fun info(): InlayProviderInfo = InlayProviderInfo(
        provider = provider,
        providerId = DECLARATIVE_PROVIDER_ID,
        options = setOf(
            InlayOptionInfo("outdated", true, msg("inlay.case.outdated")),
            InlayOptionInfo("current", true, msg("inlay.case.current")),
            InlayOptionInfo("prerelease", true, msg("inlay.case.prerelease")),
        ),
        isEnabledByDefault = true,
        providerName = msg("inlay.name"),
    )
}

internal fun supportsHintLanguage(language: Language): Boolean {
    val id = language.id
    return id == "kotlin" || id == "Groovy" || id == "TOML"
}

private class LibsHelperDeclarativeHints : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? = null
}

internal fun boundHints(file: PsiFile, editor: Editor): List<DependencyHint> {
    val hints = hintsFor(file)
    if (hints.isEmpty()) return emptyList()
    ProgressManager.checkCanceled()
    return hintsOnCurrentText(hints, editor.document.text.lines())
}

internal fun hintsProviderEnabled(): Boolean =
    DeclarativeInlayHintsSettings.getInstance().isProviderEnabled(DECLARATIVE_PROVIDER_ID) != false

internal fun hintCaseEnabled(kind: DependencyHintKind): Boolean =
    DeclarativeInlayHintsSettings.getInstance().isOptionEnabled(DECLARATIVE_PROVIDER_ID, optionOf(kind)) ?: true

private val hintIndex = AtomicReference<Pair<Int, Map<String, List<DependencyHint>>>?>(null)

private fun hintsFor(file: PsiFile): List<DependencyHint> {
    if (!isDependencyHintFile(file.name)) return emptyList()
    if (!file.isPhysical) return emptyList()
    val project = file.project
    if (project.isDefault) return emptyList()
    val report = project.service<LibsHelperService>().lastReport ?: return emptyList()
    val relative = relativePathOf(project, file) ?: return emptyList()
    return hintsByPath(report)[relative].orEmpty()
}

private fun hintsByPath(report: com.zaycev.libshelper.core.model.ProjectReport): Map<String, List<DependencyHint>> {
    val identity = System.identityHashCode(report)
    val cached = hintIndex.get()
    if (cached != null && cached.first == identity) return cached.second
    val built = dependencyHints(report).groupBy { it.relativePath }
    hintIndex.set(identity to built)
    return built
}

internal fun optionOf(kind: DependencyHintKind): String = when (kind) {
    DependencyHintKind.Outdated -> "outdated"
    DependencyHintKind.Current -> "current"
    DependencyHintKind.Alpha,
    DependencyHintKind.Beta,
    DependencyHintKind.Rc,
    DependencyHintKind.Snapshot,
    -> "prerelease"
}

