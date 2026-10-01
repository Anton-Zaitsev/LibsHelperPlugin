package com.zaycev.libshelper.ide.inlay

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key

internal fun requestDependencyHintsUpdate(project: Project) {
    val app = ApplicationManager.getApplication()
    val task = Runnable {
        if (project.isDisposed) return@Runnable
        clearInlayStamps(project)
        DaemonCodeAnalyzer.getInstance(project).restart("libshelper")
    }
    if (app.isDispatchThread) {
        task.run()
    } else {
        app.invokeLater(task, ModalityState.nonModal())
    }
}

private fun clearInlayStamps(project: Project) {
    val keys = inlayStampKeys
    if (keys.isEmpty()) return
    for (editor in EditorFactory.getInstance().allEditors) {
        if (editor.project != project) continue
        for (key in keys) editor.putUserData(key, null)
    }
}

private val inlayStampKeys: List<Key<Any>> = listOf(
    "com.intellij.codeInsight.daemon.impl.InlayHintsPassFactoryInternalKt" to "access\$getPSI_MODIFICATION_STAMP\$p",
    "com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPassFactory" to "access\$getPSI_MODIFICATION_STAMP\$cp",
).mapNotNull { (typeName, methodName) -> inlayStampKey(typeName, methodName) }

private fun inlayStampKey(typeName: String, methodName: String): Key<Any>? = try {
    val accessor = Class.forName(typeName).getMethod(methodName)
    @Suppress("UNCHECKED_CAST")
    accessor.invoke(null) as Key<Any>
} catch (_: ReflectiveOperationException) {
    null
}
