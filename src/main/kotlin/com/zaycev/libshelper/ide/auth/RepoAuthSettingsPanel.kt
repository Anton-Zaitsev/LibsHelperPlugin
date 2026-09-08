package com.zaycev.libshelper.ide.auth

import com.intellij.openapi.project.Project
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.zaycev.libshelper.core.auth.RepositoryAuthProfile
import com.zaycev.libshelper.ide.ui.authSchemeLabel
import com.zaycev.libshelper.ide.i18n.msg
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

internal class RepoAuthSettingsPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val authenticator = PasswordSafeAuthenticator(project)
    private val model = DefaultListModel<RepositoryAuthProfile>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val profile = value as? RepositoryAuthProfile
                val text = profile?.let {
                    val secret = if (it.hasSecret) msg("settings.auth.secretSaved") else msg("settings.auth.noSecret")
                    "${it.host}  ·  ${authSchemeLabel(it.scheme)}  ·  ${it.username.ifBlank { "—" }}  ·  $secret"
                }
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
            }
        }
    }

    init {
        border = JBUI.Borders.empty(8)
        add(JBLabel("<html>${msg("settings.auth.hint")}</html>"), BorderLayout.NORTH)
        val decorator = ToolbarDecorator.createDecorator(list)
            .setAddAction { addOrEdit(null) }
            .setEditAction { addOrEdit(list.selectedValue) }
            .setRemoveAction {
                val selected = list.selectedValue ?: return@setRemoveAction
                authenticator.remove(selected.host)
                reload()
            }
            .disableUpDownActions()
        add(decorator.createPanel(), BorderLayout.CENTER)
        reload()
    }

    fun reload() {
        model.clear()
        authenticator.profiles().forEach { model.addElement(it) }
    }

    private fun addOrEdit(existing: RepositoryAuthProfile?) {
        val previous = existing?.let { authenticator.stored(it.host) }
        val dialog = RepositoryAuthDialog(
            project = project,
            initialHost = existing?.host.orEmpty(),
            reason = if (existing == null) msg("settings.auth.add") else msg("settings.auth.edit"),
            initial = previous,
        )
        if (!dialog.showAndGet()) return
        val auth = dialog.result() ?: return
        val host = dialog.host().ifBlank { existing?.host }.orEmpty()
        if (host.isBlank()) return
        authenticator.save(host, auth)
        reload()
    }
}
