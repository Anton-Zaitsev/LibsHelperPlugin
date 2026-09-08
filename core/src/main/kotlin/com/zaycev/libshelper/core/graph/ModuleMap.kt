package com.zaycev.libshelper.core.graph

import com.zaycev.libshelper.core.model.ModuleGraph
import com.zaycev.libshelper.core.model.ModuleKind
import com.zaycev.libshelper.core.model.ModuleLinkKind
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet

data class PlacedNode(
    val id: String,
    val label: String,
    val kind: ModuleKind,
    val x: Float,
    val y: Float,
    val depth: Int,
    val parentId: String?,
    val pathLabel: String,
)

data class PlacedLink(
    val fromIndex: Int,
    val toIndex: Int,
    val kind: ModuleLinkKind,
)

data class ModuleMap(
    val nodes: ImmutableList<PlacedNode>,
    val links: ImmutableList<PlacedLink>,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
    val indexById: ImmutableMap<String, Int>,
) {
    companion object {
        val Empty = ModuleMap(
            nodes = persistentListOf(),
            links = persistentListOf(),
            minX = 0f,
            minY = 0f,
            maxX = 1f,
            maxY = 1f,
            indexById = persistentMapOf(),
        )
    }
}

private const val X_GAP = GraphMetrics.X_GAP
private const val Y_GAP = GraphMetrics.Y_GAP

fun layoutModuleMap(graph: ModuleGraph): ModuleMap {
    if (graph.nodes.isEmpty()) return ModuleMap.Empty
    val ids = HashSet<String>(graph.nodes.size)
    graph.nodes.forEach { ids += it.id }
    val children = HashMap<String, MutableList<String>>()
    graph.nodes.forEach { node ->
        val parent = node.parentId ?: return@forEach
        if (parent in ids) {
            children.getOrPut(parent) { ArrayList() }.add(node.id)
        }
    }
    children.values.forEach { list ->
        list.sortWith(compareBy({ '#' in it }, { it }))
    }
    val placed = HashMap<String, Pair<Float, Float>>(graph.nodes.size)
    val depths = HashMap<String, Int>(graph.nodes.size)
    var cursor = 0f
    val roots = graph.nodes.filter { node ->
        node.parentId == null || node.parentId !in ids
    }.sortedBy { it.id }
    for (root in roots) {
        cursor += layoutTree(root.id, cursor, 0, children, placed, depths) + X_GAP
    }
    graph.nodes.forEach { node ->
        if (node.id !in placed) {
            placed[node.id] = cursor to 0f
            depths[node.id] = 0
            cursor += X_GAP
        }
    }
    val nodes = graph.nodes.map { node ->
        val point = placed.getValue(node.id)
        PlacedNode(
            id = node.id,
            label = node.label,
            kind = node.kind,
            x = point.first,
            y = point.second,
            depth = depths[node.id] ?: 0,
            parentId = node.parentId,
            pathLabel = modulePathLabel(node.id, node.parentId),
        )
    }
    val indexById = HashMap<String, Int>(nodes.size)
    nodes.forEachIndexed { index, node -> indexById[node.id] = index }
    val links = graph.links.mapNotNull { link ->
        val from = indexById[link.fromId] ?: return@mapNotNull null
        val to = indexById[link.toId] ?: return@mapNotNull null
        PlacedLink(from, to, link.kind)
    }
    return ModuleMap(
        nodes = nodes.toPersistentList(),
        links = links.toPersistentList(),
        minX = nodes.minOf { it.x } - X_GAP,
        minY = nodes.minOf { it.y } - Y_GAP,
        maxX = nodes.maxOf { it.x } + X_GAP,
        maxY = nodes.maxOf { it.y } + Y_GAP,
        indexById = indexById.toPersistentMap(),
    )
}

fun searchModuleIndices(map: ModuleMap, query: String): ImmutableList<Int> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty() || map.nodes.isEmpty()) return persistentListOf()
    val ranked = ArrayList<Pair<Int, Int>>(16)
    map.nodes.forEachIndexed { index, node ->
        val id = node.id.lowercase()
        val label = node.label.lowercase()
        val path = node.pathLabel.lowercase()
        val rank = when {
            id == needle || label == needle || path == needle -> 0
            id.endsWith(":$needle") || label.startsWith(needle) || path.startsWith(needle) -> 1
            id.contains(needle) || label.contains(needle) || path.contains(needle) -> 2
            else -> return@forEachIndexed
        }
        ranked += index to rank
    }
    ranked.sortWith(compareBy({ it.second }, { map.nodes[it.first].id }))
    return ranked.map { it.first }.toPersistentList()
}

fun expandVisibleKmpChildren(map: ModuleMap, visible: MutableSet<Int>) {
    if (visible.isEmpty() || map.links.isEmpty()) return
    val extra = ArrayList<Int>(8)
    map.links.forEach { link ->
        if (link.kind != ModuleLinkKind.Target && link.kind != ModuleLinkKind.Hierarchy) return@forEach
        if (link.fromIndex !in visible) return@forEach
        if (map.nodes[link.fromIndex].kind != ModuleKind.Kmp) return@forEach
        extra += link.toIndex
    }
    visible.addAll(extra)
}

fun relatedNodeIds(map: ModuleMap, id: String?): ImmutableSet<String> {
    if (id == null) return persistentSetOf()
    val start = map.indexById[id] ?: return persistentSetOf()
    val out = HashSet<String>()
    var current: PlacedNode? = map.nodes[start]
    while (current != null) {
        out += current.id
        current = current.parentId?.let { parent -> map.indexById[parent]?.let { map.nodes[it] } }
    }
    map.links.forEach { link ->
        val from = map.nodes[link.fromIndex]
        val to = map.nodes[link.toIndex]
        if (from.id == id || to.id == id) {
            out += from.id
            out += to.id
        }
    }
    return out.toPersistentSet()
}

private fun layoutTree(
    id: String,
    left: Float,
    depth: Int,
    children: Map<String, List<String>>,
    placed: MutableMap<String, Pair<Float, Float>>,
    depths: MutableMap<String, Int>,
): Float {
    depths[id] = depth
    val kids = children[id].orEmpty()
    if (kids.isEmpty()) {
        val x = left + X_GAP / 2f
        placed[id] = x to depth * Y_GAP
        return X_GAP
    }
    var childLeft = left
    val widths = FloatArray(kids.size)
    kids.forEachIndexed { index, child ->
        val width = layoutTree(child, childLeft, depth + 1, children, placed, depths)
        widths[index] = width
        childLeft += width
    }
    val total = widths.sum().coerceAtLeast(X_GAP)
    val x = left + total / 2f
    placed[id] = x to depth * Y_GAP
    return total
}
