package com.zaycev.libshelper.core.metadata

import com.zaycev.libshelper.core.model.MetadataOrigin

data class ResolvedMetadata(
    val metadata: MavenMetadata,
    val origin: MetadataOrigin,
    val usedProxyFallback: Boolean,
    val durationMs: Long = 0,
    val fromCache: Boolean = false,
)
