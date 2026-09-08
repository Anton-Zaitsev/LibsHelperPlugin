package com.zaycev.libshelper.ide.i18n

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.RoamingType

@State(
    name = "LibsHelperSettings",
    storages = [Storage(value = "libsHelper.xml", roamingType = RoamingType.DEFAULT)],
)
class LibsHelperSettings : PersistentStateComponent<LibsHelperSettings.State> {
    class State {
        var language: String = AppLanguage.Default.name
    }

    private var stored = State()

    val language: AppLanguage
        get() = AppLanguage.fromStored(stored.language)

    fun setLanguage(value: AppLanguage) {
        if (stored.language == value.name) return
        stored.language = value.name
        LibsHelperBundle.clearCache()
        ApplicationManager.getApplication().messageBus
            .syncPublisher(LocaleChangeListener.TOPIC)
            .localeChanged()
    }

    override fun getState(): State = stored

    override fun loadState(state: State) {
        stored = state
        LibsHelperBundle.clearCache()
    }

    companion object {
        fun getInstance(): LibsHelperSettings =
            ApplicationManager.getApplication().getService(LibsHelperSettings::class.java)
    }
}
