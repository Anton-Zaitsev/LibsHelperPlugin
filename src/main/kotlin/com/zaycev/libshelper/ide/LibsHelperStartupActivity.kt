package com.zaycev.libshelper.ide

import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.zaycev.libshelper.ide.diagnostics.LibsHelperDiagnostics
import com.zaycev.libshelper.ide.inlay.InlayVersionMouseListener
import java.util.concurrent.atomic.AtomicBoolean

class LibsHelperStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (listenersInstalled.compareAndSet(false, true)) {
            val application = ApplicationManager.getApplication()
            EditorFactory.getInstance().eventMulticaster.addEditorMouseListener(InlayVersionMouseListener(), application)
            application.messageBus.connect(application).subscribe(
                AnActionListener.TOPIC,
                object : AnActionListener {
                    override fun afterActionPerformed(
                        action: com.intellij.openapi.actionSystem.AnAction,
                        event: com.intellij.openapi.actionSystem.AnActionEvent,
                        result: com.intellij.openapi.actionSystem.AnActionResult,
                    ) {
                        val name = action.javaClass.name
                        if (name.startsWith("com.zaycev.libshelper")) {
                            LibsHelperDiagnostics.record("ui", name.substringAfterLast('.'), event.place)
                        }
                    }
                },
            )
        }
        project.service<LibsHelperService>().refresh()
    }
}

private val listenersInstalled = AtomicBoolean(false)
