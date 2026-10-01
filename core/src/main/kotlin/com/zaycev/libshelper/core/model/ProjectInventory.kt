package com.zaycev.libshelper.core.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class ProjectInventory(
    val modules: ImmutableList<String>,
    val dependencies: ImmutableList<DeclaredDependency>,
    val repositories: ImmutableList<DeclaredRepository>,
    val httpProxy: HttpProxySettings?,
    val kotlinVersion: String? = null,
    val minSdk: Int? = null,
    val compileSdk: Int? = null,
    val catalogPresent: Boolean = true,
    val catalogError: String? = null,
    val unresolvedCatalogAliases: ImmutableList<UnresolvedCatalogAlias> = persistentListOf(),
    val moduleGraph: ModuleGraph = ModuleGraph.Empty,
    val catalogVersions: Map<String, String> = emptyMap(),
    val versionSources: Map<String, String> = emptyMap(),
    val fingerprint: String = "",
)
