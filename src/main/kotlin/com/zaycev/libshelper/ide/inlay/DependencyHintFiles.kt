package com.zaycev.libshelper.ide.inlay

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiFile
import com.zaycev.libshelper.core.inventory.pathInsideRoot
import com.zaycev.libshelper.ide.LibsHelperService
import com.zaycev.libshelper.ide.project.primaryGradleRoot
import java.nio.file.Path

internal fun isDependencyHintFile(name: String): Boolean =
    name.endsWith(".versions.toml") ||
        name == "libs.versions.toml" ||
        name == "build.gradle.kts" ||
        name == "build.gradle" ||
        name == "settings.gradle.kts" ||
        name == "settings.gradle"

internal fun isDependencyHintFile(file: VirtualFile): Boolean = isDependencyHintFile(file.name)

internal fun relativePathOf(project: Project, file: PsiFile): String? {
    val virtual = file.virtualFile ?: return null
    val root = primaryGradleRoot(project)?.toAbsolutePath()?.normalize()
        ?: project.basePath?.let { Path.of(it).toAbsolutePath().normalize() }
        ?: return null
    val inside = pathInsideRoot(root, root.relativize(Path.of(virtual.path)).toString()) ?: return null
    return root.relativize(inside).toString().replace('\\', '/')
}

internal fun openLibrary(project: Project, key: String, query: String = key) {
    project.service<LibsHelperService>().selectLibrary(key, query)
    ToolWindowManager.getInstance(project).getToolWindow("LibsHelper")?.activate(null)
}
