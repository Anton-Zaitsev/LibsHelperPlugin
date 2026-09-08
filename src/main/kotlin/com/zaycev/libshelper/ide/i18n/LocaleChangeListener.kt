package com.zaycev.libshelper.ide.i18n

import com.intellij.util.messages.Topic

fun interface LocaleChangeListener {
    fun localeChanged()

    companion object {
        @Topic.AppLevel
        val TOPIC: Topic<LocaleChangeListener> =
            Topic.create("libsHelper.locale", LocaleChangeListener::class.java)
    }
}
