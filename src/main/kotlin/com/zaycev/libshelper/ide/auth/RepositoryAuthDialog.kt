package com.zaycev.libshelper.ide.auth

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBDimension
import com.intellij.util.ui.JBUI
import com.zaycev.libshelper.core.auth.RepositoryAuth
import com.zaycev.libshelper.core.auth.RepositoryAuthScheme
import com.zaycev.libshelper.ide.i18n.msg
import com.zaycev.libshelper.ide.ui.authSchemeLabel
import java.awt.Component
import java.awt.event.ItemEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JList

private const val DIALOG_WIDTH_PX = 420
private const val DIALOG_HEIGHT_PX = 320
private const val FORM_WIDTH_PX = 400
private const val FORM_HEIGHT_PX = 240

internal class RepositoryAuthDialog(
    project: Project?,
    initialHost: String,
    private val reason: String,
    private val initial: RepositoryAuth? = null,
) : DialogWrapper(project) {
    private val hostField = JBTextField(initialHost)
    private val schemeBox = ComboBox(
        DefaultComboBoxModel(
            arrayOf(
                RepositoryAuthScheme.Basic,
                RepositoryAuthScheme.Bearer,
                RepositoryAuthScheme.Header,
            ),
        ),
    )
    private val userField = JBTextField()
    private val secretField = JBPasswordField()
    private val headerField = ComboBox(
        DefaultComboBoxModel(
            arrayOf("Authorization", "Private-Token", "Deploy-Token", "Job-Token", "X-JFrog-Art-Api"),
        ),
    ).apply { isEditable = true }

    init {
        title = msg("settings.auth.dialogTitle")
        schemeBox.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = (value as? RepositoryAuthScheme)?.let { authSchemeLabel(it) }.orEmpty()
                return label
            }
        }
        schemeBox.selectedItem = initial?.scheme ?: RepositoryAuthScheme.Basic
        userField.text = initial?.username.orEmpty()
        headerField.selectedItem = initial?.headerName?.ifBlank { "Authorization" } ?: "Authorization"
        schemeBox.addItemListener { event ->
            if (event.stateChange == ItemEvent.SELECTED) toggleFields()
        }
        toggleFields()
        init()
        setSize(JBUI.scale(DIALOG_WIDTH_PX), JBUI.scale(DIALOG_HEIGHT_PX))
    }

    fun host(): String = hostField.text.trim()

    fun result(): RepositoryAuth? {
        val scheme = schemeBox.selectedItem as? RepositoryAuthScheme ?: return null
        return RepositoryAuth(
            scheme = scheme,
            username = userField.text.trim(),
            secret = String(secretField.password),
            headerName = (headerField.selectedItem as? String).orEmpty().trim(),
        ).takeUnless { it.isBlank }
    }

    override fun getDimensionServiceKey(): String = "LibsHelper.RepositoryAuth.v2"

    override fun createCenterPanel(): JComponent {
        val hint = JBLabel("<html><body style='width: 280px'>$reason<br>${msg("settings.auth.dialogHint")}</body></html>").apply {
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
        }
        return FormBuilder.createFormBuilder()
            .addComponent(hint)
            .addLabeledComponent(msg("settings.auth.host"), hostField, 8, false)
            .addLabeledComponent(msg("settings.auth.scheme"), schemeBox, 8, false)
            .addLabeledComponent(msg("settings.auth.user"), userField, 8, false)
            .addLabeledComponent(msg("settings.auth.secret"), secretField, 8, false)
            .addLabeledComponent(msg("settings.auth.header"), headerField, 8, false)
            .panel
            .apply {
                border = JBUI.Borders.empty(8)
                preferredSize = JBDimension(FORM_WIDTH_PX, FORM_HEIGHT_PX)
            }
    }

    override fun getPreferredFocusedComponent(): JComponent = hostField

    private fun toggleFields() {
        val scheme = schemeBox.selectedItem as? RepositoryAuthScheme ?: RepositoryAuthScheme.Basic
        userField.isEnabled = scheme == RepositoryAuthScheme.Basic
        headerField.isEnabled = scheme == RepositoryAuthScheme.Header
    }
}
