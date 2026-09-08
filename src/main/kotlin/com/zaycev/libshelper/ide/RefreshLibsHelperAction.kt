package com.zaycev.libshelper.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.ToolWindowManager
import com.zaycev.libshelper.ide.i18n.msg

class RefreshLibsHelperAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        project.service<LibsHelperService>().refresh(forceRefresh = false)
        ToolWindowManager.getInstance(project).getToolWindow("LibsHelper")?.show()
    }

    override fun update(event: AnActionEvent) {
        event.presentation.text = msg("action.refresh.text")
        event.presentation.description = msg("action.refresh.description")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
