package com.zaycev.libshelper.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.ToolWindowManager
import com.zaycev.libshelper.ide.i18n.msg

class CheckInLibsHelperAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR)
        val selection = editor?.selectionModel?.selectedText?.trim()
        if (!selection.isNullOrBlank()) {
            project.service<LibsHelperService>().selectLibrary(selection)
        }
        project.service<LibsHelperService>().refresh()
        ToolWindowManager.getInstance(project).getToolWindow("LibsHelper")?.activate(null)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.text = msg("action.check.text")
        event.presentation.description = msg("action.check.description")
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val name = file?.name.orEmpty()
        event.presentation.isEnabledAndVisible =
            event.project != null && (
                name.endsWith(".toml") ||
                    name.endsWith(".kts") ||
                    name == "build.gradle" ||
                    name == "settings.gradle"
                )
    }
}
