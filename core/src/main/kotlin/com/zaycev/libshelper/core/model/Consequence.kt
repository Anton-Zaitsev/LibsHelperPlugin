package com.zaycev.libshelper.core.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

enum class ConsequenceId {
    LocalArtifact,
    Stable,
    Major,
    SameLinePatch,
    DowngradeToStable,
    Rc,
    Beta,
    Alpha,
    DataFromProxy,
}

data class Consequence(
    val id: ConsequenceId,
    val args: ImmutableList<String> = persistentListOf(),
)
