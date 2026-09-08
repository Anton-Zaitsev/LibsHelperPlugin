package com.zaycev.libshelper.core.model

import com.zaycev.libshelper.core.analytics.ProjectAnalytics
import com.zaycev.libshelper.core.graph.ModuleMap
import kotlinx.collections.immutable.ImmutableList

data class ProjectReport(
    val inventory: ProjectInventory,
    val libraries: ImmutableList<LibraryAdvice>,
    val metadataFromProxyOnly: Boolean,
    val scanPlan: ScanPlan,
    val fingerprint: String? = null,
    val servedFromCache: Boolean = false,
    val analytics: ProjectAnalytics = ProjectAnalytics.Empty,
    val moduleMap: ModuleMap = ModuleMap.Empty,
)
