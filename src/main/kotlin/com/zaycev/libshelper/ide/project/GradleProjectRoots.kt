package com.zaycev.libshelper.ide.project

import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.project.Project
import java.nio.file.Path

private val GRADLE_SYSTEM_ID = ProjectSystemId("GRADLE")

fun gradleProjectRoots(project: Project): List<Path> {
    val linked = runCatching { linkedGradleRoots(project) }.getOrDefault(emptyList())
    val existing = linked.filter { it.toFile().isDirectory }
    if (existing.isNotEmpty()) return existing.distinct()
    return listOfNotNull(project.basePath?.let { Path.of(it) })
}

private fun linkedGradleRoots(project: Project): List<Path> {
    val settings = ExternalSystemApiUtil.getSettings(project, GRADLE_SYSTEM_ID)
    return settings.linkedProjectsSettings.mapNotNull { linked ->
        val path = linked.externalProjectPath
        path.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    }
}

fun primaryGradleRoot(project: Project): Path? {
    val roots = gradleProjectRoots(project)
    val base = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() }
    return roots.firstOrNull { it.toAbsolutePath().normalize() == base } ?: roots.firstOrNull()
}
