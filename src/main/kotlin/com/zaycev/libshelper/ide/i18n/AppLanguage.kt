package com.zaycev.libshelper.ide.i18n

import java.util.Locale

enum class AppLanguage(val locale: Locale, val nativeName: String) {
    Russian(Locale.forLanguageTag("ru"), "Русский"),
    English(Locale.ENGLISH, "English"),
    ;

    companion object {
        val Default: AppLanguage = English

        fun fromStored(raw: String?): AppLanguage =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: Default
    }
}
