package com.zaycev.libshelper.core.analytics

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class AnalyticsDependency(
    val key: String,
    val group: String,
    val artifact: String,
    val module: String,
    val configuration: String,
    val catalogAlias: String?,
    val versionRef: String?,
    val requestedVersion: String?,
    val isPlugin: Boolean,
    val isLocal: Boolean,
    val isBom: Boolean,
    val fromCatalog: Boolean,
    val current: String?,
    val recommended: String?,
    val outdated: Boolean,
    val channel: String?,
    val github: String?,
    val mavenCentral: String?,
)

data class AnalyticsInput(
    val dependencies: List<AnalyticsDependency>,
    val moduleCount: Int,
)

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
    val channel: String?,
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

fun interface AnalyticsComputer {
    fun compute(input: AnalyticsInput): ProjectAnalytics
}

interface SunburstLayout {
    fun layout(libraries: ImmutableList<LibraryStat>, otherLabel: String): SunburstChartData
}
