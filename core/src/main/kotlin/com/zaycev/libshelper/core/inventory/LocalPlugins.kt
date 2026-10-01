package com.zaycev.libshelper.core.inventory

import com.zaycev.libshelper.core.model.DeclaredDependency
import java.nio.file.Path

fun withoutLocalPlugins(root: Path, dependencies: List<DeclaredDependency>): List<DeclaredDependency> =
    withoutKnownPlugins(localPluginIds(root), dependencies)

internal fun withoutKnownPlugins(ids: Set<String>, dependencies: List<DeclaredDependency>): List<DeclaredDependency> {
    if (ids.isEmpty()) return dependencies
    return dependencies.filterNot { dependency -> isProjectPlugin(dependency, ids) }
}

internal fun localPluginIds(root: Path): Set<String> = projectTree(root).pluginIds

private fun isProjectPlugin(dependency: DeclaredDependency, ids: Set<String>): Boolean {
    if (!dependency.isPlugin) return false
    val group = dependency.coordinates.group
    val artifact = dependency.coordinates.artifact
    return ids.any { id -> group == id || artifact == "$id.gradle.plugin" || artifact == id }
}
