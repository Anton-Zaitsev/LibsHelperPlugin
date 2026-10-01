package com.zaycev.libshelper.ide.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.zaycev.libshelper.core.inventory.moduleScriptCandidates
import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.ide.LibsHelperService
import com.zaycev.libshelper.ide.project.primaryGradleRoot

internal fun openModuleGradle(project: Project, moduleId: String) {
    val root = primaryGradleRoot(project) ?: return
    val relative = moduleScriptCandidates(moduleId).firstOrNull { candidate ->
        root.resolve(candidate).toFile().isFile
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
    project.service<LibsHelperService>().openRelative(relativePath, line, token)
}
