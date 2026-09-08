package com.zaycev.libshelper.ide.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.zaycev.libshelper.ide.i18n.LocaleChangeListener
import com.zaycev.libshelper.ide.i18n.msg
import com.zaycev.libshelper.ide.ui.compose.LibsHelperApp
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.foundation.enableNewSwingCompositing

class LibsHelperToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        install(project, toolWindow)
        val connection = ApplicationManager.getApplication().messageBus.connect(toolWindow.disposable)
        connection.subscribe(
            LocaleChangeListener.TOPIC,
            LocaleChangeListener {
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) install(project, toolWindow)
                }
            },
        )
    }

    private fun install(project: Project, toolWindow: ToolWindow) {
        val manager = toolWindow.contentManager
        manager.removeAllContents(true)
        enableNewSwingCompositing()
        val component = JewelComposePanel(focusOnClickInside = true) {
            LibsHelperApp(project)
        }
        val content = ContentFactory.getInstance().createContent(component, "", false)
        content.isCloseable = false
        manager.addContent(content)
        toolWindow.stripeTitle = msg("app.title")
    }
}
