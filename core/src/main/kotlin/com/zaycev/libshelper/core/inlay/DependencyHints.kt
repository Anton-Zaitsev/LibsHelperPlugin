package com.zaycev.libshelper.core.inlay

import com.zaycev.libshelper.core.inventory.CatalogVersionSite
import com.zaycev.libshelper.core.inventory.catalogVersionSites
import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.LibraryAdvice
import com.zaycev.libshelper.core.model.ProjectReport
import com.zaycev.libshelper.core.model.VersionChannel
import com.zaycev.libshelper.core.model.displayTarget
import com.zaycev.libshelper.core.model.resolvedUsageLocations
import com.zaycev.libshelper.core.settings.BuildSettingAdvice
import com.zaycev.libshelper.core.settings.BuildSettingRole

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
    val recommendedChannel: VersionChannel? = null,
)

fun dependencyHints(report: ProjectReport): List<DependencyHint> {
    val collected = mutableListOf<DependencyHint>()
    val byKey = report.libraries
        .filter { showHintFor(it) }
        .associateBy { it.advice.dependency.coordinates.key }
    if (byKey.isNotEmpty()) {
        for (declared in report.inventory.dependencies) {
            val advice = byKey[declared.coordinates.key] ?: continue
            collected += locationsOf(declared).map { location ->
                hintAt(location, declared, advice)
            }
        }
    }
    collected += buildSettingHints(report)
    return mergeHints(collected)
}

private fun buildSettingHints(report: ProjectReport): List<DependencyHint> {
    val sites = catalogVersionSites(report.inventory.versionSources)
    return report.buildSettings.mapNotNull { advice -> hintForSetting(advice, sites) }
}

private fun hintForSetting(
    advice: BuildSettingAdvice,
    sites: Map<String, CatalogVersionSite>,
): DependencyHint? {
    if (advice.role !in trackedSettingRoles) return null
    val tracked = advice.trackedVersion ?: return null
    if (advice.current.isBlank()) return null
    val location = sites[advice.key] ?: return null
    val outdated = advice.suggestions.isNotEmpty()
    return DependencyHint(
        relativePath = location.path,
        line = location.line,
        needle = advice.key,
        coordinatesKey = advice.key,
        kind = if (outdated) DependencyHintKind.Outdated else DependencyHintKind.Current,
        currentVersion = advice.current,
        recommendedVersion = if (outdated) tracked else null,
        catalogAlias = advice.key,
        recommendedChannel = if (outdated) VersionChannel.Stable else null,
    )
}

private val trackedSettingRoles = setOf(
    BuildSettingRole.CompileSdk,
    BuildSettingRole.TargetSdk,
    BuildSettingRole.Ndk,
)

internal fun showHintFor(item: LibraryAdvice): Boolean {
    val advice = item.advice
    if (advice.dependency.isLocalArtifact) return false
    if (advice.current == null) return false
    return item.lookupError == null || advice.isOutdated
}

internal fun kindOf(item: LibraryAdvice): DependencyHintKind {
    val advice = item.advice
    val target = advice.displayTarget()
    return when {
        advice.isOutdated && target != null -> DependencyHintKind.Outdated
        advice.currentChannel == VersionChannel.Alpha -> DependencyHintKind.Alpha
        advice.currentChannel == VersionChannel.Beta -> DependencyHintKind.Beta
        advice.currentChannel == VersionChannel.ReleaseCandidate -> DependencyHintKind.Rc
        advice.currentChannel == VersionChannel.Snapshot -> DependencyHintKind.Snapshot
        advice.currentChannel == VersionChannel.Dev -> DependencyHintKind.Snapshot
        else -> DependencyHintKind.Current
    }
}

fun mergeHints(hints: List<DependencyHint>): List<DependencyHint> =
    hints.groupBy { it.relativePath to it.line }
        .values
        .map { group -> collapseGroup(group) }
        .sortedWith(compareBy({ it.relativePath }, { it.line }))

fun hintsOnCurrentText(hints: List<DependencyHint>, lines: List<String>): List<DependencyHint> =
    mergeHints(hints.mapNotNull { hint -> bindHintToCurrentText(hint, lines) })

fun bindHintToCurrentText(hint: DependencyHint, fileText: String): DependencyHint? =
    bindHintToCurrentText(hint, fileText.lines())

fun bindHintToCurrentText(hint: DependencyHint, lines: List<String>): DependencyHint? {
    val line = resolveHintLine(lines, hint) ?: return null
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
    } else if (
        catalogPath != null &&
        dependency.catalogLine != null &&
        !dependency.catalogAlias.isNullOrBlank() &&
        dependency.versionRef.isNullOrBlank()
    ) {
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
    recommendedVersion = item.advice.displayTarget()?.version,
    catalogAlias = dependency.catalogAlias,
    recommendedChannel = item.advice.displayTarget()?.channel,
)

private fun collapseGroup(group: List<DependencyHint>): DependencyHint {
    val chosen = group.firstOrNull { it.kind == DependencyHintKind.Outdated } ?: group.first()
    if (chosen.kind != DependencyHintKind.Outdated) return chosen
    val recommended = group.mapNotNull { it.recommendedVersion }.distinct()
    val single = recommended.singleOrNull()
    return if (single == null && recommended.size > 1) {
        chosen.copy(kind = DependencyHintKind.Current, recommendedVersion = null, recommendedChannel = null)
    } else {
        chosen.copy(recommendedVersion = single)
    }
}

fun hintBelongsToLine(lineText: String, needle: String): Boolean {
    if (needle.isEmpty()) return true
    val line = lineText.substringBefore('#').trim()
    if (needle.startsWith("libs.") || ':' in needle) return line.contains(needle)
    if (!line.startsWith(needle)) return false
    val next = line.getOrNull(needle.length) ?: return true
    return next == '=' || next.isWhitespace()
}
