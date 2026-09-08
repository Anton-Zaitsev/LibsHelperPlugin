package com.zaycev.libshelper.core.analytics

import com.zaycev.libshelper.core.model.DependencySource
import com.zaycev.libshelper.core.model.ProjectReport
import com.zaycev.libshelper.core.model.VersionChannel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

data class LibraryStat(
    val key: String,
    val group: String,
    val artifact: String,
    val modules: ImmutableList<String>,
    val configurations: ImmutableList<String>,
    val catalogAlias: String?,
    val versionRef: String?,
    val current: String?,
    val recommended: String?,
    val isOutdated: Boolean,
    val channel: VersionChannel?,
    val isPlugin: Boolean,
    val isLocal: Boolean,
    val isBom: Boolean,
    val isFirstParty: Boolean,
    val github: String?,
    val mavenCentral: String?,
    val weight: Int,
)

data class SharedVersionGroup(
    val ref: String,
    val keys: ImmutableList<String>,
)

data class ProjectAnalytics(
    val libraries: ImmutableList<LibraryStat>,
    val thirdParty: ImmutableList<LibraryStat>,
    val topByWeight: ImmutableList<LibraryStat>,
    val sharedGroups: ImmutableList<SharedVersionGroup>,
    val outdatedCount: Int,
    val pluginCount: Int,
    val moduleCount: Int,
    val catalogCount: Int,
    val skippedFirstParty: Int,
    val totalWeight: Int,
) {
    companion object {
        val Empty = ProjectAnalytics(
            libraries = persistentListOf(),
            thirdParty = persistentListOf(),
            topByWeight = persistentListOf(),
            sharedGroups = persistentListOf(),
            outdatedCount = 0,
            pluginCount = 0,
            moduleCount = 0,
            catalogCount = 0,
            skippedFirstParty = 0,
            totalWeight = 0,
        )
    }
}

fun projectAnalytics(report: ProjectReport, topLimit: Int = 8): ProjectAnalytics {
    val prefixes = firstPartyPrefixes(report.inventory.dependencies)
    val adviceByKey = report.libraries.associateBy { it.advice.dependency.coordinates.key }
    val grouped = report.inventory.dependencies.groupBy { it.coordinates.key }
    val allStats = grouped.map { (key, group) ->
        val sample = group.first()
        val advice = adviceByKey[key]?.advice
        val modules = group.map { it.module }.distinct().sorted()
        val firstParty = isFirstParty(sample.coordinates.group, group.any { it.isLocalArtifact }, prefixes)
        LibraryStat(
            key = key,
            group = sample.coordinates.group,
            artifact = sample.coordinates.artifact,
            modules = modules.toPersistentList(),
            configurations = group.map { it.configuration }.distinct().sorted().toPersistentList(),
            catalogAlias = group.mapNotNull { it.catalogAlias }.firstOrNull(),
            versionRef = group.mapNotNull { it.versionRef }.firstOrNull(),
            current = advice?.current?.raw ?: sample.requestedVersion,
            recommended = advice?.preferredStable?.version?.raw,
            isOutdated = advice?.isOutdated == true,
            channel = advice?.currentChannel,
            isPlugin = group.any { it.isPlugin },
            isLocal = group.any { it.isLocalArtifact },
            isBom = group.any { it.isBom },
            isFirstParty = firstParty,
            github = adviceByKey[key]?.links?.github,
            mavenCentral = adviceByKey[key]?.links?.mavenCentral,
            weight = modules.size.coerceAtLeast(1),
        )
    }.sortedWith(compareByDescending<LibraryStat> { it.weight }.thenBy { it.key })
    val libraries = allStats.toPersistentList()
    val thirdParty = allStats.filterNot { it.isFirstParty }.toPersistentList()
    val shared = thirdParty
        .filter { !it.versionRef.isNullOrBlank() }
        .groupBy { it.versionRef.orEmpty() }
        .filter { it.value.size > 1 }
        .map { (ref, items) -> SharedVersionGroup(ref, items.map { it.key }.toPersistentList()) }
        .sortedByDescending { it.keys.size }
        .toPersistentList()

    return ProjectAnalytics(
        libraries = libraries,
        thirdParty = thirdParty,
        topByWeight = thirdParty.take(topLimit).toPersistentList(),
        sharedGroups = shared,
        outdatedCount = thirdParty.count { it.isOutdated },
        pluginCount = thirdParty.count { it.isPlugin },
        moduleCount = report.inventory.modules.size.coerceAtLeast(report.scanPlan.moduleCount),
        catalogCount = thirdParty.count { it.catalogAlias != null || groupSource(report, it.key) },
        skippedFirstParty = allStats.count { it.isFirstParty },
        totalWeight = thirdParty.sumOf { it.weight },
    )
}

private fun groupSource(report: ProjectReport, key: String): Boolean =
    report.inventory.dependencies.any { it.coordinates.key == key && it.source == DependencySource.Toml }
