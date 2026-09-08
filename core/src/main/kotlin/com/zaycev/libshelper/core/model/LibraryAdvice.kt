package com.zaycev.libshelper.core.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class LibraryAdvice(
    val advice: UpdateAdvice,
    val repositories: ImmutableList<DeclaredRepository> = persistentListOf(),
    val links: LibraryLinks = LibraryLinks.none,
    val lookupDurationMs: Long? = null,
    val fromCache: Boolean = false,
    val lookupError: String? = null,
)
