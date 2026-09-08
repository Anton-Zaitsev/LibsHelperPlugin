@file:Suppress("UnstableApiUsage")

package com.zaycev.libshelper.ide.inlay

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.InlayHintsPassFactoryInternal
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager

internal fun requestDependencyHintsUpdate(project: Project) {
    val app = ApplicationManager.getApplication()
    val task = Runnable {
        if (project.isDisposed) return@Runnable
        val analyzer = DaemonCodeAnalyzer.getInstance(project)
        val psiManager = PsiManager.getInstance(project)
        var restartedAny = false
        FileEditorManager.getInstance(project).allEditors.forEach { fileEditor ->
            val editor = (fileEditor as? TextEditor)?.editor ?: return@forEach
            val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return@forEach
            if (!isDependencyHintFile(file)) return@forEach
            runCatching { InlayHintsPassFactoryInternal.clearModificationStamp(editor) }
            val psi = psiManager.findFile(file)
            if (psi != null) {
                analyzer.restart(psi)
                restartedAny = true
            }
        }
        if (!restartedAny) {
            analyzer.restart()
        }
    }
    if (app.isDispatchThread) {
        task.run()
    } else {
        app.invokeLater(task, project.disposed)
    }
}
