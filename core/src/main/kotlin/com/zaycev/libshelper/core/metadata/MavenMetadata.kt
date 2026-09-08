package com.zaycev.libshelper.core.metadata

data class MavenMetadata(
    val groupId: String?,
    val artifactId: String?,
    val versions: List<String>,
    val latestTag: String?,
    val releaseTag: String?,
)
