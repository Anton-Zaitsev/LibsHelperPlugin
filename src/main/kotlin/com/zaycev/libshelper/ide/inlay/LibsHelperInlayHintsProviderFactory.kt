package com.zaycev.libshelper.ide.inlay

import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsProviderFactory
import com.intellij.codeInsight.hints.ProviderInfo
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.DumbAware

class LibsHelperInlayHintsProviderFactory : InlayHintsProviderFactory, DumbAware {
    private val provider = LibsHelperDependencyInlayHintsProvider()

    override fun getProvidersInfo(): List<ProviderInfo<out Any>> =
        hintLanguages().map { ProviderInfo(it, provider) }

    override fun getProvidersInfoForLanguage(language: Language): List<InlayHintsProvider<out Any>> {
        if (language in hintLanguages() || language.isKindOf(PlainTextLanguage.INSTANCE)) {
            return listOf(provider)
        }
        val id = language.id
        if (id.equals("TOML", ignoreCase = true) ||
            id.equals("kotlin", ignoreCase = true) ||
            id.equals("Groovy", ignoreCase = true)
        ) {
            return listOf(provider)
        }
        return emptyList()
    }

    override fun getLanguages(): Iterable<Language> = hintLanguages()

    override fun isDumbAware(): Boolean = true
}

private fun hintLanguages(): List<Language> = buildList {
    Language.findLanguageByID("TOML")?.let(::add)
    Language.findLanguageByID("kotlin")?.let(::add)
    Language.findLanguageByID("Groovy")?.let(::add)
    add(PlainTextLanguage.INSTANCE)
}
