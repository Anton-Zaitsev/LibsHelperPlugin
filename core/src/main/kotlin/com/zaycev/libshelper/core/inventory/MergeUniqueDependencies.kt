package com.zaycev.libshelper.core.inventory

import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.DependencySource
import com.zaycev.libshelper.core.model.resolvedUsageLocations

fun mergeUniqueDependencies(dependencies: List<DeclaredDependency>): List<DeclaredDependency> {
    return dependencies
        .filter { it.isLocalArtifact || it.requestedVersion != null || it.catalogAlias != null }
        .groupBy { it.coordinates.key + "\u0000" + it.localFileName.orEmpty() }
        .values
        .map { mergeGroup(it) }
}

private fun mergeGroup(group: List<DeclaredDependency>): DeclaredDependency {
    val withModule = group.firstOrNull { it.module != ":" }
    val catalog = group.firstOrNull { it.source == DependencySource.Toml }
    val withUsage = group.firstOrNull { it.usagePath != null }
    val base = withUsage ?: withModule ?: catalog ?: group.first()
    val usages = group.flatMap { it.resolvedUsageLocations() }.distinct()
    return base.copy(
        catalogAlias = base.catalogAlias ?: catalog?.catalogAlias,
        versionRef = base.versionRef ?: catalog?.versionRef,
        catalogPath = group.mapNotNull { it.catalogPath }.firstOrNull(),
        catalogLine = group.mapNotNull { it.catalogLine }.firstOrNull(),
        catalogVersionLine = group.mapNotNull { it.catalogVersionLine }.firstOrNull(),
        usagePath = usages.firstOrNull()?.path ?: group.mapNotNull { it.usagePath }.firstOrNull(),
        usageLine = usages.firstOrNull()?.line ?: group.mapNotNull { it.usageLine }.firstOrNull(),
        usageLocations = usages,
        module = withModule?.module ?: base.module,
        isPlugin = group.any { it.isPlugin },
        isBom = group.any { it.isBom },
        referenced = group.any { it.source != DependencySource.Toml || it.resolvedUsageLocations().isNotEmpty() },
    )
}
