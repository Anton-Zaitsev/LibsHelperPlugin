package com.zaycev.libshelper.core.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class ConflictSignal(
    val type: ConflictType,
    val severity: Severity,
    val args: ImmutableList<String> = persistentListOf(),
    val related: ImmutableList<Coordinates> = persistentListOf(),
)
