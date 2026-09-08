package com.zaycev.libshelper.core.model

import kotlinx.collections.immutable.ImmutableList

data class ScanPlan(
    val catalogPresent: Boolean,
    val catalogFileName: String = "gradle/libs.versions.toml",
    val moduleCount: Int,
    val dependencyCount: Int,
    val unresolvedAliasCount: Int,
    val httpProxy: HttpProxySettings?,
    val repositories: ImmutableList<RepositorySearchEntry>,
)
