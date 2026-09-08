package com.zaycev.libshelper.ide.auth

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.messages.MessageBusConnection
import com.zaycev.libshelper.ide.i18n.AppLanguage
import com.zaycev.libshelper.ide.i18n.LibsHelperSettings
import com.zaycev.libshelper.ide.i18n.LocaleChangeListener
import com.zaycev.libshelper.ide.i18n.msg
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.ItemEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel

class LibsHelperConfigurable(
    private val project: Project,
) : Configurable {
    private val root = JPanel(BorderLayout())
    private var credentials: RepoAuthSettingsPanel? = null
    private var busConnection: MessageBusConnection? = null

    override fun getDisplayName(): String = msg("app.title")

    override fun createComponent(): JComponent {
        rebuild()
        busConnection = ApplicationManager.getApplication().messageBus.connect()
        busConnection?.subscribe(LocaleChangeListener.TOPIC, LocaleChangeListener { rebuild() })
        return root
    }

    override fun isModified(): Boolean = false

    override fun apply() {
        credentials?.reload()
    }

    override fun reset() {
        credentials?.reload()
    }

    override fun disposeUIResources() {
        busConnection?.disconnect()
        busConnection = null
        credentials = null
        root.removeAll()
    }

    private fun rebuild() {
        val created = RepoAuthSettingsPanel(project)
        credentials = created
        root.removeAll()
        root.add(buildPanel(created), BorderLayout.CENTER)
        root.revalidate()
        root.repaint()
    }

    private fun buildPanel(credentialsPanel: RepoAuthSettingsPanel): JComponent = panel {
        row(msg("settings.language")) {
            val box = ComboBox(AppLanguage.entries.toTypedArray())
            box.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): Component {
                    val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    text = (value as? AppLanguage)?.nativeName.orEmpty()
                    return label
                }
            }
            box.selectedItem = LibsHelperSettings.getInstance().language
            box.addItemListener { event ->
                if (event.stateChange != ItemEvent.SELECTED) return@addItemListener
                val language = box.selectedItem as? AppLanguage ?: return@addItemListener
                LibsHelperSettings.getInstance().setLanguage(language)
            }
            cell(box)
        }
        row {
            comment(msg("settings.language.hint"))
        }
        group(msg("settings.credentials")) {
            row {
                cell(credentialsPanel).align(Align.FILL)
            }.resizableRow()
        }
    }
}
