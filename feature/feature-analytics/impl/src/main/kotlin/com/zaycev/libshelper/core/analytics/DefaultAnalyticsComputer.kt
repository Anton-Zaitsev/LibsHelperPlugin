package com.zaycev.libshelper.core.analytics

import kotlinx.collections.immutable.toPersistentList

class DefaultAnalyticsComputer(
    private val topLimit: Int = 8,
) : AnalyticsComputer {
    override fun compute(input: AnalyticsInput): ProjectAnalytics = projectAnalytics(input, topLimit)
}

internal fun projectAnalytics(input: AnalyticsInput, topLimit: Int = 8): ProjectAnalytics {
    val prefixes = firstPartyPrefixes(input.dependencies)
    val grouped = input.dependencies.groupBy { it.key }
    val allStats = grouped.map { (key, group) ->
        val sample = group.first()
        val modules = group.map { it.module }.distinct().sorted()
        val firstParty = isFirstParty(sample.group, group.any { it.isLocal }, prefixes)
        LibraryStat(
            key = key,
            group = sample.group,
            artifact = sample.artifact,
            modules = modules.toPersistentList(),
            configurations = group.asSequence().map { it.configuration }.distinct().sorted().toPersistentList(),
            catalogAlias = group.firstNotNullOfOrNull { it.catalogAlias },
            versionRef = group.firstNotNullOfOrNull { it.versionRef },
            current = sample.current ?: sample.requestedVersion,
            recommended = group.firstNotNullOfOrNull { it.recommended },
            isOutdated = group.any { it.outdated },
            channel = group.firstNotNullOfOrNull { it.channel },
            isPlugin = group.any { it.isPlugin },
            isLocal = group.any { it.isLocal },
            isBom = group.any { it.isBom },
            isFirstParty = firstParty,
            github = group.firstNotNullOfOrNull { it.github },
            mavenCentral = group.firstNotNullOfOrNull { it.mavenCentral },
            weight = modules.size.coerceAtLeast(1),
        )
    }.sortedWith(compareByDescending<LibraryStat> { it.weight }.thenBy { it.key })
    val libraries = allStats.toPersistentList()
    val thirdParty = allStats.filterNot { it.isFirstParty }.toPersistentList()
    val shared = thirdParty
        .asSequence()
        .filter { !it.versionRef.isNullOrBlank() }
        .groupBy { it.versionRef.orEmpty() }
        .filter { it.value.size > 1 }
        .map { (ref, items) -> SharedVersionGroup(ref, items.map { it.key }.toPersistentList()) }
        .sortedByDescending { it.keys.size }
        .toList()
        .toPersistentList()
    return ProjectAnalytics(
        libraries = libraries,
        thirdParty = thirdParty,
        topByWeight = thirdParty.take(topLimit).toPersistentList(),
        sharedGroups = shared,
        outdatedCount = thirdParty.count { it.isOutdated },
        pluginCount = thirdParty.count { it.isPlugin },
        moduleCount = input.moduleCount,
        catalogCount = run {
            val catalogKeys = input.dependencies.asSequence().filter { it.fromCatalog }.map { it.key }.toSet()
            thirdParty.count { stat -> stat.catalogAlias != null || stat.key in catalogKeys }
        },
        skippedFirstParty = allStats.count { it.isFirstParty },
        totalWeight = thirdParty.sumOf { it.weight },
    )
}
