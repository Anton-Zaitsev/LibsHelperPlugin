package com.zaycev.libshelper.core.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class RepositorySearchEntry(
    val repository: DeclaredRepository,
    val role: SearchRole,
    val alternatives: ImmutableList<OfficialAlternative> = persistentListOf(),
)
