package com.zaycev.libshelper.core.inventory

import com.zaycev.libshelper.core.model.ModuleGraph
import com.zaycev.libshelper.core.model.ModuleKind
import com.zaycev.libshelper.core.model.ModuleLink
import com.zaycev.libshelper.core.model.ModuleLinkKind
import com.zaycev.libshelper.core.model.ModuleNode
import kotlinx.collections.immutable.toPersistentList

fun buildModuleGraph(
    moduleIds: Collection<String>,
    factsByModule: Map<String, ModuleFacts>,
): ModuleGraph {
    val ids = LinkedHashSet<String>()
    moduleIds.forEach { ids += normalizeModuleId(it) }
    ids += ":"
    val expanded = LinkedHashSet<String>()
    for (id in ids) {
        var current = id
        while (true) {
            expanded += current
            current = parentModuleId(current) ?: break
        }
    }
    val nodes = mutableListOf<ModuleNode>()
    val links = mutableListOf<ModuleLink>()
    val seen = HashSet<String>()
    for (id in expanded.sorted()) {
        if (!seen.add(id)) continue
        val facts = factsByModule[id]
        val kind = when {
            id == ":" -> ModuleKind.Root
            facts != null -> facts.kind
            else -> ModuleKind.Gradle
        }
        val parent = parentModuleId(id)
        nodes += ModuleNode(id = id, label = shortModuleLabel(id), kind = kind, parentId = parent)
        if (parent != null) {
            links += ModuleLink(parent, id, ModuleLinkKind.Hierarchy)
        }
        facts?.projectDeps.orEmpty().forEach { dep ->
            val target = normalizeModuleId(dep)
            if (target != id && target in expanded) {
                links += ModuleLink(id, target, ModuleLinkKind.ProjectDep)
            }
        }
        if (kind == ModuleKind.Kmp) {
            facts?.targets.orEmpty()
                .filter { it != ModuleKind.Kmp }
                .forEach { targetKind ->
                    val targetId = "$id#${targetKind.name.lowercase()}"
                    if (seen.add(targetId)) {
                        nodes += ModuleNode(
                            id = targetId,
                            label = targetKind.name.lowercase(),
                            kind = targetKind,
                            parentId = id,
                        )
                        links += ModuleLink(id, targetId, ModuleLinkKind.Target)
                    }
                }
        }
    }
    return ModuleGraph(
        nodes = nodes.distinctBy { it.id }.toPersistentList(),
        links = links.distinctBy { "${it.fromId}>${it.toId}>${it.kind}" }.toPersistentList(),
    )
}
