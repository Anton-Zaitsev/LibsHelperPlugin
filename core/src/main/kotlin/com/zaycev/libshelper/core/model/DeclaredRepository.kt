package com.zaycev.libshelper.core.model

import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf

data class DeclaredRepository(
    val name: String,
    val url: String,
    val type: RepositoryType,
    val kind: RepositoryKind,
    val scope: RepositoryScope,
    val connectStatus: ConnectStatus = ConnectStatus.Unknown,
    val message: String? = null,
    val includeGroups: ImmutableSet<String> = persistentSetOf(),
    val exclusive: Boolean = false,
) {
    fun servesGroup(group: String): Boolean =
        includeGroups.isEmpty() || group in includeGroups
}
