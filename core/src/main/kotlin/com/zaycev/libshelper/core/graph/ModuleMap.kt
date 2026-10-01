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
private const val HALF = 0.5f

fun layoutModuleMap(graph: ModuleGraph, cardWidths: FloatArray? = null): ModuleMap {
    if (graph.nodes.isEmpty()) return ModuleMap.Empty
    val count = graph.nodes.size
    val indexById = HashMap<String, Int>(count)
    graph.nodes.forEachIndexed { index, node -> indexById[node.id] = index }
    val parentOf = IntArray(count) { index ->
        val parent = graph.nodes[index].parentId?.let { indexById[it] } ?: return@IntArray ORPHAN
        if (parent == index) ORPHAN else parent
    }
    val order = siblingOrder(count) { index ->
        val id = graph.nodes[index].id
        if ('#' in id) id else " $id"
    }
    val widths = resolvedWidths(count, cardWidths)
    val place = tidyPlace(count, parentOf, widths, order)
    val nodes = graph.nodes.mapIndexed { index, node ->
        PlacedNode(
            id = node.id,
            label = node.label,
            kind = node.kind,
            x = place.x[index],
            y = place.y[index],
            depth = place.depth[index],
            parentId = node.parentId,
            pathLabel = modulePathLabel(node.id, node.parentId),
        )
    }
    val links = graph.links.mapNotNull { link ->
        val from = indexById[link.fromId] ?: return@mapNotNull null
        val to = indexById[link.toId] ?: return@mapNotNull null
        PlacedLink(from, to, link.kind)
    }
    return bounded(nodes, links, indexById, widths)
}

fun cardWidthForLabel(labelWidth: Float, chrome: Float): Float {
    if (!labelWidth.isFinite() || labelWidth <= 0f) return GraphMetrics.CARD_W
    val width = chrome + labelWidth
    if (!width.isFinite()) return GraphMetrics.CARD_W
    return width.coerceAtLeast(GraphMetrics.CARD_W)
}

fun spreadCards(map: ModuleMap, cardWidths: FloatArray): ModuleMap {
    if (map.nodes.isEmpty()) return map
    val count = map.nodes.size
    val parentOf = IntArray(count) { index ->
        val parent = map.nodes[index].parentId?.let { map.indexById[it] } ?: return@IntArray ORPHAN
        if (parent == index) ORPHAN else parent
    }
    val order = siblingOrder(count) { map.nodes[it].x }
    val widths = resolvedWidths(count, cardWidths)
    val place = tidyPlace(count, parentOf, widths, order)
    val nodes = map.nodes.mapIndexed { index, node ->
        node.copy(x = place.x[index], y = place.y[index], depth = place.depth[index])
    }
    return bounded(nodes, map.links, map.indexById, widths)
}

private fun siblingOrder(count: Int, key: (Int) -> Comparable<*>): IntArray {
    val order = IntArray(count)
    val ranked = (0 until count).sortedWith { left, right ->
        val compared = compareValues(key(left), key(right))
        if (compared != 0) compared else left - right
    }
    ranked.forEachIndexed { position, index -> order[index] = position }
    return order
}

private fun resolvedWidths(count: Int, cardWidths: FloatArray?): FloatArray =
    FloatArray(count) { index -> resolvedWidth(cardWidths?.getOrNull(index) ?: GraphMetrics.CARD_W) }

private fun bounded(
    nodes: List<PlacedNode>,
    links: List<PlacedLink>,
    indexById: Map<String, Int>,
    widths: FloatArray,
): ModuleMap {
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    nodes.forEachIndexed { index, node ->
        val halfW = resolvedWidth(widths.getOrElse(index) { GraphMetrics.CARD_W }) * HALF
        val halfH = GraphMetrics.CARD_H * HALF
        if (node.x - halfW < minX) minX = node.x - halfW
        if (node.x + halfW > maxX) maxX = node.x + halfW
        if (node.y - halfH < minY) minY = node.y - halfH
        if (node.y + halfH > maxY) maxY = node.y + halfH
    }
    return ModuleMap(
        nodes = nodes.toPersistentList(),
        links = links.toPersistentList(),
        minX = minX - X_GAP,
        minY = minY - Y_GAP,
        maxX = maxX + X_GAP,
        maxY = maxY + Y_GAP,
        indexById = indexById.toPersistentMap(),
    )
}

private fun resolvedWidth(cardWidth: Float): Float =
    cardWidth.takeIf { it.isFinite() && it > 0f } ?: GraphMetrics.CARD_W

private const val ORPHAN = -1

fun searchModuleIndices(map: ModuleMap, query: String): ImmutableList<Int> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty() || map.nodes.isEmpty()) return persistentListOf()
    val ranked = ArrayList<SearchHit>(16)
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
        ranked += SearchHit(index, rank)
    }
    ranked.sortWith(compareBy({ it.rank }, { map.nodes[it.index].id }))
    return ranked.map { it.index }.toPersistentList()
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

