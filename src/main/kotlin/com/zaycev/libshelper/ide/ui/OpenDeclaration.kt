package com.zaycev.libshelper.ide.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.zaycev.libshelper.core.inventory.moduleScriptCandidates
import com.zaycev.libshelper.core.model.DeclaredDependency
import java.nio.file.Path

internal fun openModuleGradle(project: Project, moduleId: String) {
    val root = project.basePath ?: return
    val relative = moduleScriptCandidates(moduleId).firstOrNull { candidate ->
        Path.of(root, candidate).toFile().isFile
    } ?: return
    openDeclaration(project, relative, line = null, token = null)
}

internal fun openDependencyCatalog(project: Project, dependency: DeclaredDependency) {
    openDeclaration(
        project = project,
        relativePath = dependency.catalogPath ?: "gradle/libs.versions.toml",
        line = dependency.catalogLine,
        token = dependency.catalogAlias,
    )
}

internal fun openDependencyUsage(project: Project, dependency: DeclaredDependency) {
    val path = dependency.usagePath ?: dependency.catalogPath ?: return
    openDeclaration(project, path, dependency.usageLine ?: dependency.catalogLine, dependency.catalogAlias)
}

private fun openDeclaration(project: Project, relativePath: String, line: Int?, token: String?) {
    val root = project.basePath ?: return
    val ioFile = Path.of(root, relativePath).toFile()
    val virtual = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile) ?: return
    val descriptor = if (line != null && line > 0) {
        OpenFileDescriptor(project, virtual, line - 1, 0)
    } else {
        val text = String(virtual.contentsToByteArray())
        val needle = token?.takeIf { it.isNotBlank() }
        val offset = needle?.let { text.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        OpenFileDescriptor(project, virtual, offset)
    }
    FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
}
