package com.zaycev.libshelper.core.metadata

import com.zaycev.libshelper.core.model.DeclaredRepository

data class MetadataLookup(
    val resolved: ResolvedMetadata?,
    val officialError: String?,
    val proxyError: String?,
    val repositoryStatuses: List<DeclaredRepository>,
    val lastUrl: String? = null,
)
