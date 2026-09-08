package com.zaycev.libshelper.core.inlay

import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.LibraryAdvice
import com.zaycev.libshelper.core.model.ProjectReport
import com.zaycev.libshelper.core.model.VersionChannel
import com.zaycev.libshelper.core.model.resolvedUsageLocations

enum class DependencyHintKind {
    Outdated,
    Current,
    Alpha,
    Beta,
    Rc,
    Snapshot,
}

data class DependencyHint(
    val relativePath: String,
    val line: Int,
    val needle: String,
    val coordinatesKey: String,
    val kind: DependencyHintKind,
    val currentVersion: String?,
    val recommendedVersion: String?,
    val catalogAlias: String?,
)

fun dependencyHints(report: ProjectReport): List<DependencyHint> {
    val byKey = report.libraries
        .filter { showHintFor(it) }
        .associateBy { it.advice.dependency.coordinates.key }
    if (byKey.isEmpty()) return emptyList()
    val collected = mutableListOf<DependencyHint>()
    for (declared in report.inventory.dependencies) {
        val advice = byKey[declared.coordinates.key] ?: continue
        collected += locationsOf(declared).map { location ->
            hintAt(location, declared, advice)
        }
    }
    return mergeHints(collected)
}

internal fun showHintFor(item: LibraryAdvice): Boolean {
    val advice = item.advice
    if (advice.dependency.isLocalArtifact) return false
    if (advice.current == null) return false
    return item.lookupError == null || advice.isOutdated
}

internal fun kindOf(item: LibraryAdvice): DependencyHintKind {
    val advice = item.advice
    return when {
        advice.isOutdated -> DependencyHintKind.Outdated
        advice.currentChannel == VersionChannel.Alpha -> DependencyHintKind.Alpha
        advice.currentChannel == VersionChannel.Beta -> DependencyHintKind.Beta
        advice.currentChannel == VersionChannel.ReleaseCandidate -> DependencyHintKind.Rc
        advice.currentChannel == VersionChannel.Snapshot -> DependencyHintKind.Snapshot
        else -> DependencyHintKind.Current
    }
}

internal fun mergeHints(hints: List<DependencyHint>): List<DependencyHint> =
    hints.groupBy { it.relativePath to it.line }
        .values
        .map { group -> collapseGroup(group) }
        .sortedWith(compareBy({ it.relativePath }, { it.line }))

fun bindHintToCurrentText(hint: DependencyHint, fileText: String): DependencyHint? {
    val line = resolveHintLine(fileText.lines(), hint) ?: return null
    return if (line == hint.line) hint else hint.copy(line = line)
}

internal fun resolveHintLine(lines: List<String>, hint: DependencyHint): Int? {
    val needle = hint.needle
    if (needle.isEmpty()) {
        return hint.line.takeIf { it in 1..lines.size }
    }
    val stored = hint.line - 1
    val storedMatches = stored in lines.indices && hintBelongsToLine(lines[stored], needle)
    if (isBareCatalogKey(needle)) {
        val inVersions = firstMatchingLine(lines, needle, versionsSection = true)
        if (inVersions != null) return inVersions
    }
    if (storedMatches) return hint.line
    val matches = lines.indices.filter { hintBelongsToLine(lines[it], needle) }
    return matches.singleOrNull()?.plus(1)
}

private fun isBareCatalogKey(needle: String): Boolean =
    needle.isNotEmpty() && !needle.startsWith("libs.") && ':' !in needle

private fun firstMatchingLine(
    lines: List<String>,
    needle: String,
    versionsSection: Boolean,
): Int? {
    var inVersions = false
    for (index in lines.indices) {
        val trimmed = lines[index].substringBefore('#').trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            inVersions = trimmed == "[versions]"
            continue
        }
        if (versionsSection && !inVersions) continue
        if (hintBelongsToLine(lines[index], needle)) return index + 1
    }
    return null
}

private data class HintLocation(
    val relativePath: String,
    val line: Int,
    val needle: String,
)

private fun locationsOf(dependency: DeclaredDependency): List<HintLocation> {
    val locations = mutableListOf<HintLocation>()
    val catalogPath = dependency.catalogPath
    val versionLine = dependency.catalogVersionLine
    if (catalogPath != null && versionLine != null && !dependency.versionRef.isNullOrBlank()) {
        locations += HintLocation(catalogPath, versionLine, dependency.versionRef)
    } else if (catalogPath != null && dependency.catalogLine != null && !dependency.catalogAlias.isNullOrBlank()) {
        locations += HintLocation(catalogPath, dependency.catalogLine, dependency.catalogAlias)
    }
    for (usage in dependency.resolvedUsageLocations()) {
        locations += HintLocation(usage.path, usage.line, usageNeedle(dependency))
    }
    return locations
}

private fun usageNeedle(dependency: DeclaredDependency): String {
    val alias = dependency.catalogAlias
    if (!alias.isNullOrBlank()) {
        val dotted = alias.replace('-', '.')
        return if (dependency.isPlugin) "libs.plugins.$dotted" else "libs.$dotted"
    }
    return "${dependency.coordinates.group}:${dependency.coordinates.artifact}"
}

private fun hintAt(
    location: HintLocation,
    dependency: DeclaredDependency,
    item: LibraryAdvice,
): DependencyHint = DependencyHint(
    relativePath = location.relativePath,
    line = location.line,
    needle = location.needle,
    coordinatesKey = dependency.coordinates.key,
    kind = kindOf(item),
    currentVersion = item.advice.current?.raw,
    recommendedVersion = item.advice.preferredStable?.version?.raw,
    catalogAlias = dependency.catalogAlias,
)

private fun collapseGroup(group: List<DependencyHint>): DependencyHint {
    val chosen = group.firstOrNull { it.kind == DependencyHintKind.Outdated } ?: group.first()
    if (chosen.kind != DependencyHintKind.Outdated) return chosen
    val recommended = group.mapNotNull { it.recommendedVersion }.distinct()
    return chosen.copy(recommendedVersion = recommended.singleOrNull())
}

fun hintBelongsToLine(lineText: String, needle: String): Boolean {
    if (needle.isEmpty()) return true
    val line = lineText.substringBefore('#').trim()
    if (needle.startsWith("libs.") || ':' in needle) return line.contains(needle)
    if (!line.startsWith(needle)) return false
    val next = line.getOrNull(needle.length) ?: return true
    return next == '=' || next.isWhitespace()
}
