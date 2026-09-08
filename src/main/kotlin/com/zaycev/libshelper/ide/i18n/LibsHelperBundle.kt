package com.zaycev.libshelper.ide.i18n

import com.intellij.openapi.application.ApplicationManager
import java.text.MessageFormat
import java.util.Locale
import java.util.PropertyResourceBundle
import java.util.ResourceBundle

object LibsHelperBundle {
    const val BUNDLE: String = "messages.LibsHelperBundle"

    @Volatile
    private var cached: ResourceBundle? = null

    @Volatile
    private var cachedLocale: Locale? = null

    fun message(key: String, vararg params: Any): String {
        val pattern = runCatching { bundle().getString(key) }.getOrDefault(key)
        return if (params.isEmpty()) pattern else MessageFormat.format(pattern, *params)
    }

    fun clearCache() {
        cached = null
        cachedLocale = null
        ResourceBundle.clearCache()
    }

    fun currentLocale(): Locale = currentLanguage().locale

    fun currentLanguage(): AppLanguage {
        val application = ApplicationManager.getApplication() ?: return AppLanguage.Default
        return runCatching { application.getService(LibsHelperSettings::class.java)?.language }
            .getOrNull()
            ?: AppLanguage.Default
    }

    private fun bundle(): ResourceBundle {
        val locale = currentLocale()
        val hit = cached
        if (hit != null && cachedLocale == locale) return hit
        val loaded = ResourceBundle.getBundle(BUNDLE, locale, Utf8Control)
        cached = loaded
        cachedLocale = locale
        return loaded
    }

    private object Utf8Control : ResourceBundle.Control() {
        override fun getFallbackLocale(baseName: String, locale: Locale): Locale? = null

        override fun newBundle(
            baseName: String,
            locale: Locale,
            format: String,
            loader: ClassLoader,
            reload: Boolean,
        ): ResourceBundle? {
            val bundleName = toBundleName(baseName, locale)
            val resourceName = toResourceName(bundleName, "properties")
            val stream = if (reload) {
                loader.getResource(resourceName)?.openConnection()?.apply { useCaches = false }?.getInputStream()
            } else {
                loader.getResourceAsStream(resourceName)
            } ?: return null
            return stream.reader(Charsets.UTF_8).use { PropertyResourceBundle(it) }
        }
    }
}

fun msg(key: String, vararg params: Any): String = LibsHelperBundle.message(key, *params)
