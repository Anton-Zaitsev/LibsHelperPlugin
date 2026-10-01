package com.zaycev.libshelper.core.graph

import kotlin.math.ceil
import kotlin.math.sqrt

internal class TidyPlace(
    val x: FloatArray,
    val y: FloatArray,
    val depth: IntArray,
)

internal fun tidyPlace(
    count: Int,
    parentOf: IntArray,
    widths: FloatArray,
    siblingOrder: IntArray,
): TidyPlace {
    val xs = FloatArray(count)
    val ys = FloatArray(count)
    val depths = IntArray(count)
    if (count == 0) return TidyPlace(xs, ys, depths)
    val nodes = Array(count) { index ->
        TidyNode(index, cardWidthAt(widths, index))
    }
    val childLists = Array(count) { ArrayList<Int>() }
    val roots = ArrayList<TidyNode>()
    for (index in 0 until count) {
        val parent = parentOf.getOrElse(index) { ORPHAN }
        if (parent < 0 || parent >= count || parent == index) {
            roots += nodes[index]
        } else {
            childLists[parent].add(index)
        }
    }
    for (list in childLists) {
        list.sortBy { value -> siblingOrder.getOrElse(value) { it } }
    }
    roots.sortBy { node -> siblingOrder.getOrElse(node.index) { node.index } }
    val state = IntArray(count)
    val grids = ArrayList<TidyNode>()
    fun build(node: TidyNode) {
        val index = node.index
        if (index < 0 || state[index] == DONE) return
        if (state[index] == VISITING) return
        state[index] = VISITING
        val kids = childLists[index]
        val leaves = kids.isNotEmpty() && kids.all { childLists[it].isEmpty() }
        if (leaves && kids.size >= LEAF_GRID_MIN) {
            attachGrid(node, kids, nodes, grids)
            for (kid in kids) state[kid] = DONE
        } else {
            for (kid in kids) {
                if (state[kid] == VISITING) continue
                val child = nodes[kid]
                child.parent = node
                child.number = node.children.size
                node.children += child
            }
            for (child in node.children) build(child)
        }
        state[index] = DONE
    }
    val superRoot = TidyNode(ORPHAN, 0f)
    fun adopt(node: TidyNode) {
        node.parent = superRoot
        node.number = superRoot.children.size
        superRoot.children += node
        build(node)
    }
    for (root in roots) adopt(root)
    for (node in nodes) {
        if (state[node.index] != DONE) adopt(node)
    }
    if (superRoot.children.isNotEmpty()) {
        firstWalk(superRoot)
        secondWalk(superRoot, 0f, ROOT_DEPTH, xs, ys, depths)
        placeGrids(grids, xs, ys, depths)
        shiftToOrigin(xs, widths)
    }
    return TidyPlace(xs, ys, depths)
}

private fun attachGrid(
    node: TidyNode,
    kids: List<Int>,
    nodes: Array<TidyNode>,
    grids: MutableList<TidyNode>,
) {
    val columns = ceil(sqrt(kids.size.toDouble())).toInt().coerceAtLeast(1)
    var slot = 0f
    for (kid in kids) {
        val width = nodes[kid].width + GraphMetrics.CARD_GAP
        if (width > slot) slot = width
    }
    val rows = (kids.size + columns - 1) / columns
    val occupied = (columns * slot - GraphMetrics.CARD_GAP).coerceAtLeast(nodes[kids[0]].width)
    node.gridColumns = columns
    node.gridSlot = slot
    node.gridLeaves = kids.toIntArray()
    grids += node
    var spineParent = node
    repeat(rows) {
        val spine = TidyNode(ORPHAN, occupied)
        spine.parent = spineParent
        spine.number = 0
        spineParent.children += spine
        spineParent = spine
    }
}

private fun placeGrids(
    grids: List<TidyNode>,
    xs: FloatArray,
    ys: FloatArray,
    depths: IntArray,
) {
    for (node in grids) {
        val leaves = node.gridLeaves ?: continue
        val columns = node.gridColumns
        val slot = node.gridSlot
        if (columns <= 0 || slot <= 0f) continue
        val left = node.x - columns * slot * HALF
        val rowDepth = node.depth + 1
        for (index in leaves.indices) {
            val leaf = leaves[index]
            if (leaf !in xs.indices) continue
            val column = index % columns
            val row = index / columns
            xs[leaf] = left + column * slot + slot * HALF
            ys[leaf] = (rowDepth + row) * GraphMetrics.Y_GAP
            depths[leaf] = rowDepth + row
        }
    }
}

private fun shiftToOrigin(xs: FloatArray, widths: FloatArray) {
    var minLeft = Float.POSITIVE_INFINITY
    for (index in xs.indices) {
        val left = xs[index] - cardWidthAt(widths, index) * HALF
        if (left < minLeft) minLeft = left
    }
    if (!minLeft.isFinite() || minLeft == 0f) return
    for (index in xs.indices) xs[index] -= minLeft
}

private fun firstWalk(node: TidyNode) {
    val kids = node.children
    if (kids.isEmpty()) {
        val left = leftSibling(node)
        node.prelim = if (left == null) 0f else left.prelim + sep(left, node)
        return
    }
    val fallback = AncestorRef(kids[0])
    for (child in kids) {
        firstWalk(child)
        apportion(child, fallback)
    }
    executeShifts(node)
    val mid = (kids.first().prelim + kids.last().prelim) * HALF
    val left = leftSibling(node)
    if (left != null) {
        node.prelim = left.prelim + sep(left, node)
        node.mod = node.prelim - mid
    } else {
        node.prelim = mid
    }
}

private fun apportion(node: TidyNode, fallback: AncestorRef) {
    val left = leftSibling(node) ?: return
    var rightInner = node
    var rightOuter = node
    var leftInner = left
    var leftOuter = leftmostSibling(node) ?: return
    var rightInnerShift = node.mod
    var rightOuterShift = node.mod
    var leftInnerShift = leftInner.mod
    var leftOuterShift = leftOuter.mod
    var guard = 0
    while (guard++ < WALK_LIMIT) {
        val nextLeftInner = nextRight(leftInner) ?: break
        val nextRightInner = nextLeft(rightInner) ?: break
        leftInner = nextLeftInner
        rightInner = nextRightInner
        leftOuter = nextLeft(leftOuter) ?: leftOuter
        rightOuter = nextRight(rightOuter) ?: rightOuter
        rightOuter.ancestor = node
        val shift = (leftInner.prelim + leftInnerShift) - (rightInner.prelim + rightInnerShift) + sep(leftInner, rightInner)
        if (shift > 0f) {
            moveSubtree(ancestorOf(leftInner, node, fallback.node), node, shift)
            rightInnerShift += shift
            rightOuterShift += shift
        }
        leftInnerShift += leftInner.mod
        rightInnerShift += rightInner.mod
        leftOuterShift += leftOuter.mod
        rightOuterShift += rightOuter.mod
    }
    if (nextRight(leftInner) != null && nextRight(rightOuter) == null) {
        rightOuter.thread = nextRight(leftInner)
        rightOuter.mod += leftInnerShift - rightOuterShift
    } else {
        if (nextLeft(rightInner) != null && nextLeft(leftOuter) == null) {
            leftOuter.thread = nextLeft(rightInner)
            leftOuter.mod += rightInnerShift - leftOuterShift
        }
        fallback.node = node
    }
}

private fun executeShifts(node: TidyNode) {
    var shift = 0f
    var change = 0f
    var index = node.children.lastIndex
    while (index >= 0) {
        val child = node.children[index]
        child.prelim += shift
        child.mod += shift
        change += child.change
        shift += child.shift + change
        index--
    }
}

private fun moveSubtree(from: TidyNode, to: TidyNode, shift: Float) {
    val subtrees = (to.number - from.number).toFloat()
    if (subtrees <= 0f) return
    to.change -= shift / subtrees
    to.shift += shift
    from.change += shift / subtrees
    to.prelim += shift
    to.mod += shift
}

private fun secondWalk(
    node: TidyNode,
    shift: Float,
    depth: Int,
    xs: FloatArray,
    ys: FloatArray,
    depths: IntArray,
) {
    val x = node.prelim + shift
    node.x = x
    node.depth = depth
    val index = node.index
    if (index in xs.indices) {
        xs[index] = x
        ys[index] = depth * GraphMetrics.Y_GAP
        depths[index] = depth
    }
    val childShift = shift + node.mod
    val childDepth = depth + 1
    for (child in node.children) {
        secondWalk(child, childShift, childDepth, xs, ys, depths)
    }
}

private fun ancestorOf(node: TidyNode, sibling: TidyNode, fallback: TidyNode): TidyNode {
    val parent = node.ancestor.parent
    return if (parent != null && parent == sibling.parent) node.ancestor else fallback
}

private fun leftmostSibling(node: TidyNode): TidyNode? = node.parent?.children?.firstOrNull()

private fun leftSibling(node: TidyNode): TidyNode? {
    val parent = node.parent ?: return null
    val index = node.number - 1
    if (index < 0 || index > parent.children.lastIndex) return null
    return parent.children[index]
}

private fun nextLeft(node: TidyNode): TidyNode? =
    if (node.children.isNotEmpty()) node.children[0] else node.thread

private fun nextRight(node: TidyNode): TidyNode? =
    if (node.children.isNotEmpty()) node.children.last() else node.thread

private fun sep(left: TidyNode, right: TidyNode): Float =
    (left.width + right.width) * HALF + GraphMetrics.CARD_GAP

private fun cardWidthAt(widths: FloatArray, index: Int): Float {
    val width = widths.getOrElse(index) { GraphMetrics.CARD_W }
    if (!width.isFinite() || width <= 0f) return GraphMetrics.CARD_W
    return width
}

private class AncestorRef(var node: TidyNode)

private class TidyNode(
    val index: Int,
    val width: Float,
) {
    val children = ArrayList<TidyNode>()
    var parent: TidyNode? = null
    var thread: TidyNode? = null
    var ancestor: TidyNode = this
    var number: Int = 0
    var prelim: Float = 0f
    var mod: Float = 0f
    var shift: Float = 0f
    var change: Float = 0f
    var x: Float = 0f
    var depth: Int = 0
    var gridColumns: Int = 0
    var gridSlot: Float = 0f
    var gridLeaves: IntArray? = null
}

private const val ORPHAN = -1
private const val VISITING = 1
private const val DONE = 2
private const val ROOT_DEPTH = -1
private const val LEAF_GRID_MIN = 9
private const val WALK_LIMIT = 100_000
private const val HALF = 0.5f
