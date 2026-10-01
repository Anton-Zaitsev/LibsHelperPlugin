package com.zaycev.libshelper.ide.auth

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import com.zaycev.libshelper.ide.i18n.AppLanguage
import com.zaycev.libshelper.ide.i18n.LibsHelperSettings
import com.zaycev.libshelper.ide.i18n.LocaleChangeListener
import com.zaycev.libshelper.ide.i18n.msg
import com.zaycev.libshelper.ide.mcp.McpServerService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.ItemEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingConstants

class LibsHelperConfigurable(
    private val project: Project,
) : Configurable {
    private val root = JPanel(BorderLayout())
    private var credentials: RepoAuthSettingsPanel? = null
    private var busConnection: MessageBusConnection? = null
    private var mcpEnabledBox: JBCheckBox? = null
    private var mcpWritesBox: JBCheckBox? = null
    private var mcpVerboseBox: JBCheckBox? = null
    private var mcpPortField: JBTextField? = null
    private var appliedEnabled = false
    private var appliedWrites = false
    private var appliedVerbose = false
    private var appliedPort = ""

    override fun getDisplayName(): String = msg("app.title")

    override fun createComponent(): JComponent {
        rebuild()
        busConnection = ApplicationManager.getApplication().messageBus.connect()
        busConnection?.subscribe(LocaleChangeListener.TOPIC, LocaleChangeListener { rebuild() })
        return root
    }

    override fun isModified(): Boolean {
        val enabled = mcpEnabledBox ?: return false
        return enabled.isSelected != appliedEnabled ||
            mcpWritesBox?.isSelected != appliedWrites ||
            mcpVerboseBox?.isSelected != appliedVerbose ||
            mcpPortField?.text != appliedPort
    }

    override fun apply() {
        val enabled = mcpEnabledBox ?: return
        val port = mcpPortField?.text?.toIntOrNull() ?: McpServerService.DEFAULT_PORT
        val writes = mcpWritesBox?.isSelected == true
        val verbose = mcpVerboseBox?.isSelected == true
        LibsHelperSettings.getInstance().setVerboseLog(verbose)
        McpServerService.getInstance().update(enabled.isSelected, port, writes)
        appliedEnabled = enabled.isSelected
        appliedWrites = writes
        appliedVerbose = verbose
        appliedPort = port.toString()
        mcpPortField?.text = appliedPort
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
        group(msg("settings.mcp")) {
            val mcp = McpServerService.getInstance()
            val enabled = JBCheckBox(msg("settings.mcp.enabled"), mcp.enabled)
            val writes = JBCheckBox(msg("settings.mcp.writes"), mcp.allowFileChanges)
            val verbose = JBCheckBox(msg("settings.verbose"), LibsHelperSettings.getInstance().verboseLog)
            val port = JBTextField(mcp.port.toString())
            mcpEnabledBox = enabled
            mcpWritesBox = writes
            mcpVerboseBox = verbose
            mcpPortField = port
            appliedEnabled = mcp.enabled
            appliedWrites = mcp.allowFileChanges
            appliedVerbose = verbose.isSelected
            appliedPort = port.text
            row { cell(enabled) }
            row(msg("settings.mcp.port")) { cell(port) }
            row { cell(writes) }
            row { cell(verbose) }
            val feedback = JBLabel().apply { isVisible = false }
            row {
                button(msg("settings.mcp.reissue")) { event ->
                    mcp.reissueToken()
                    mcp.update(enabled.isSelected, port.text.toIntOrNull() ?: mcp.port, writes.isSelected)
                    showActionFeedback(feedback, event, msg("settings.mcp.reissued"))
                }
                button(msg("settings.mcp.cursor")) { event ->
                    copy(mcp.cursorConfig())
                    showActionFeedback(feedback, event, msg("settings.mcp.copied.cursor"))
                }
                button(msg("settings.mcp.claude")) { event ->
                    copy(mcp.claudeCodeConfig())
                    showActionFeedback(feedback, event, msg("settings.mcp.copied.claude"))
                }
                button(msg("settings.mcp.desktop")) { event ->
                    copy(mcp.claudeDesktopConfig())
                    showActionFeedback(feedback, event, msg("settings.mcp.copied.desktop"))
                }
            }
            row { cell(feedback) }
        }
        group(msg("settings.credentials")) {
            row {
                cell(credentialsPanel).align(Align.FILL)
            }.resizableRow()
        }
    }

    private fun showActionFeedback(feedback: JBLabel, event: ActionEvent, message: String) {
        feedback.icon = AllIcons.General.InspectionsOK
        feedback.text = message
        feedback.isVisible = true
        root.revalidate()
        root.repaint()
        val anchor = event.source as? JComponent ?: return
        val content = JBLabel(message, AllIcons.General.InspectionsOK, SwingConstants.LEADING).apply {
            border = JBUI.Borders.empty(4, 8)
        }
        JBPopupFactory.getInstance()
            .createBalloonBuilder(content)
            .setFillColor(MessageType.INFO.popupBackground)
            .setFadeoutTime(FEEDBACK_FADE_MS)
            .setHideOnClickOutside(true)
            .setHideOnKeyOutside(true)
            .setHideOnAction(true)
            .setRequestFocus(false)
            .createBalloon()
            .show(RelativePoint.getSouthOf(anchor), Balloon.Position.below)
    }
}

private fun copy(text: String) {
    CopyPasteManager.getInstance().setContents(StringSelection(text))
}

private const val FEEDBACK_FADE_MS = 2_500L
