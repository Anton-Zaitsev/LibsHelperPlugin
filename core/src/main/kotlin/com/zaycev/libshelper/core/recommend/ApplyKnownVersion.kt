package com.zaycev.libshelper.core.recommend

import com.zaycev.libshelper.core.analytics.AnalyticsComputer
import com.zaycev.libshelper.core.analytics.analyticsInput
import com.zaycev.libshelper.core.inventory.isSafeVersionToken
import com.zaycev.libshelper.core.inventory.readDeclaredVersion
import com.zaycev.libshelper.core.inventory.versionFileOf
import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.LibraryAdvice
import com.zaycev.libshelper.core.model.MetadataOrigin
import com.zaycev.libshelper.core.model.MetadataOriginKind
import com.zaycev.libshelper.core.model.ProjectReport
import com.zaycev.libshelper.core.model.UpdateAdvice
import com.zaycev.libshelper.core.versioning.classifyVersion
import kotlinx.collections.immutable.toPersistentList

fun applyKnownVersion(
    report: ProjectReport,
    dependency: DeclaredDependency,
    newVersion: String,
    analytics: AnalyticsComputer,
): ProjectReport {
    val version = newVersion.trim()
    if (version.isEmpty()) return report
    val keys = affectedKeys(report, dependency)
    if (keys.isEmpty()) return report
    val inventory = report.inventory.copy(
        dependencies = report.inventory.dependencies.map { declared ->
            if (declared.coordinates.key in keys) declared.copy(requestedVersion = version) else declared
        }.toPersistentList(),
    )
    val libraries = report.libraries.map { item ->
        val current = item.advice.dependency
        if (current.coordinates.key !in keys) return@map item
        val updated = current.copy(requestedVersion = version)
        item.copy(
            advice = buildAdvice(
                dependency = updated,
                versions = knownVersions(item.advice) + version,
                origin = originOf(item.advice),
                inventory = inventory,
                proxyMissingOfficial = report.metadataFromProxyOnly,
            ),
        )
    }.sortedWith(
        compareByDescending<LibraryAdvice> { it.advice.isOutdated }
            .thenBy { it.advice.dependency.coordinates.key },
    ).toPersistentList()
    val draft = report.copy(inventory = inventory, libraries = libraries)
    return draft.copy(analytics = analytics.compute(draft.analyticsInput()))
}

fun syncRequestedVersions(
    report: ProjectReport,
    relativePath: String,
    text: String,
    analytics: AnalyticsComputer,
): ProjectReport {
    val normalized = relativePath.replace('\\', '/')
    val updates = report.inventory.dependencies
        .filter { versionFileOf(it) == normalized }
        .distinctBy { declared ->
            val ref = declared.versionRef
            if (ref.isNullOrBlank()) declared.coordinates.key else "ref:$ref"
        }
        .mapNotNull { declared ->
            val found = readDeclaredVersion(text, declared)?.trim() ?: return@mapNotNull null
            if (!isTrackableVersion(found) || found == declared.requestedVersion) return@mapNotNull null
            declared to found
        }
    return updates.fold(report) { current, (declared, version) ->
        applyKnownVersion(current, declared, version, analytics)
    }
}

private fun isTrackableVersion(raw: String): Boolean =
    isSafeVersionToken(raw) && raw.any(Char::isDigit) && classifyVersion(raw) != null

internal fun affectedKeys(report: ProjectReport, dependency: DeclaredDependency): Set<String> {
    val ref = dependency.versionRef
    if (!ref.isNullOrBlank()) {
        return report.inventory.dependencies
            .filter { it.versionRef == ref }
            .map { it.coordinates.key }
            .toSet()
            .ifEmpty { setOf(dependency.coordinates.key) }
    }
    return setOf(dependency.coordinates.key)
}

private fun knownVersions(advice: UpdateAdvice): List<String> = listOfNotNull(
    advice.current?.raw,
    advice.preferredStable?.version?.raw,
    advice.latestRc?.version?.raw,
    advice.latestBeta?.version?.raw,
    advice.latestAlpha?.version?.raw,
)

private fun originOf(advice: UpdateAdvice): MetadataOrigin =
    advice.preferredStable?.origin
        ?: advice.latestRc?.origin
        ?: advice.latestBeta?.origin
        ?: advice.latestAlpha?.origin
        ?: MetadataOrigin(MetadataOriginKind.OfficialDirect, "")
