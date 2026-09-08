package com.zaycev.libshelper.core.cache

import com.zaycev.libshelper.core.model.MetadataOriginKind
import kotlinx.serialization.Serializable

@Serializable
data class CachedMetadata(
    val versions: List<String>,
    val latestTag: String? = null,
    val releaseTag: String? = null,
    val originKind: MetadataOriginKind,
    val originUrl: String,
    val storedAtEpochMs: Long,
    val usedProxyFallback: Boolean,
)
