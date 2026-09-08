package com.zaycev.libshelper.core.model

import com.zaycev.libshelper.core.versioning.MavenVersion

data class VersionCandidate(
    val version: MavenVersion,
    val channel: VersionChannel,
    val origin: MetadataOrigin,
)

