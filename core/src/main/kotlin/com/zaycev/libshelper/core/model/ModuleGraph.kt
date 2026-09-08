package com.zaycev.libshelper.core.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

enum class ModuleKind {
    Root,
    Gradle,
    Android,
    Jvm,
    Kmp,
    Ios,
    Js,
    Wasm,
    Native,
    Desktop,
    Common,
}

enum class ModuleLinkKind {
    Hierarchy,
    ProjectDep,
    Target,
}

data class ModuleNode(
    val id: String,
    val label: String,
    val kind: ModuleKind,
    val parentId: String?,
)

data class ModuleLink(
    val fromId: String,
    val toId: String,
    val kind: ModuleLinkKind,
)

data class ModuleGraph(
    val nodes: ImmutableList<ModuleNode>,
    val links: ImmutableList<ModuleLink>,
) {
    companion object {
        val Empty = ModuleGraph(persistentListOf(), persistentListOf())
    }
}
