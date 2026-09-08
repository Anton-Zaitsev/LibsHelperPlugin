package com.zaycev.libshelper.core.model

data class DeclaredDependency(
    val coordinates: Coordinates,
    val requestedVersion: String?,
    val catalogAlias: String? = null,
    val versionRef: String? = null,
    val configuration: String,
    val module: String,
    val source: DependencySource,
    val isBom: Boolean = false,
    val isPlugin: Boolean = false,
    val isLocalArtifact: Boolean = false,
    val localFileName: String? = null,
    val localKind: LocalArtifactKind? = null,
    val catalogPath: String? = null,
    val catalogLine: Int? = null,
    val catalogVersionLine: Int? = null,
    val usagePath: String? = null,
    val usageLine: Int? = null,
    val usageLocations: List<SourceLocation> = emptyList(),
)

data class SourceLocation(
    val path: String,
    val line: Int,
)

fun DeclaredDependency.resolvedUsageLocations(): List<SourceLocation> {
    if (usageLocations.isNotEmpty()) return usageLocations
    val path = usagePath ?: return emptyList()
    val line = usageLine ?: return emptyList()
    return listOf(SourceLocation(path, line))
}

