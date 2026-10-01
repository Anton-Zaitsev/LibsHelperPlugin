package com.zaycev.libshelper.core.graph

import com.zaycev.libshelper.core.model.ModuleLinkKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class GraphStroke(
    val kind: ModuleLinkKind,
    val fromIndex: Int,
    val toIndex: Int,
    val xy: FloatArray,
    val dashed: Boolean,
)

class ModuleRoutes(
    val strokes: List<GraphStroke>,
    val linkXy: Array<FloatArray>,
)

fun moduleRoutes(map: ModuleMap, cardWidths: FloatArray? = null): ModuleRoutes {
    val links = map.links
    val linkXy = Array(links.size) { FloatArray(0) }
    if (links.isEmpty() || map.nodes.isEmpty()) return ModuleRoutes(emptyList(), linkXy)
    val strokes = ArrayList<GraphStroke>(links.size)
    val groups = HashMap<Long, IntArrayBuilder>(8)
    links.forEachIndexed { index, link ->
        if (link.kind == ModuleLinkKind.ProjectDep) return@forEachIndexed
        if (link.fromIndex !in map.nodes.indices || link.toIndex !in map.nodes.indices) return@forEachIndexed
        val key = groupKey(link.fromIndex, link.kind)
        val builder = groups.getOrPut(key) { IntArrayBuilder() }
        builder.add(index)
    }
    for (builder in groups.values) {
        routeGroup(map, cardWidths, builder.toArray(), linkXy, strokes)
    }
    routeDependencies(map, cardWidths, linkXy, strokes)
    return ModuleRoutes(strokes, linkXy)
}

private fun routeGroup(
    map: ModuleMap,
    cardWidths: FloatArray?,
    linkIndexes: IntArray,
    linkXy: Array<FloatArray>,
    strokes: MutableList<GraphStroke>,
) {
    if (linkIndexes.isEmpty()) return
    val first = map.links[linkIndexes[0]]
    val parent = map.nodes[first.fromIndex]
    val targets = linkIndexes.map { map.links[it].toIndex }
    val rows = rowsOf(map, targets)
    val bars = FloatArray(rows.size)
    val parentBottom = parent.y + HALF_H
    for (row in rows.indices) {
        val belowTop = map.nodes[rows[row][0]].y - HALF_H
        val aboveBottom = if (row == 0) parentBottom else map.nodes[rows[row - 1][0]].y + HALF_H
        bars[row] = barBetween(aboveBottom, belowTop)
    }
    val gutter = if (rows.size > 1) gutterX(map, cardWidths, targets) else 0f
    val rowOf = HashMap<Int, Int>(targets.size)
    rows.forEachIndexed { row, indexes ->
        for (index in indexes) rowOf[index] = row
    }
    for (linkIndex in linkIndexes) {
        val link = map.links[linkIndex]
        val row = rowOf[link.toIndex] ?: 0
        val ortho = if (rows.size > 1 && row > 0) {
            gutterElbow(parent.x, parentBottom, bars, gutter, row, map.nodes[link.toIndex])
        } else if (isDown(parent.y, map.nodes[link.toIndex].y)) {
            elbow(parent.x, parentBottom, bars[row], map.nodes[link.toIndex])
        } else {
            sideways(map, cardWidths, link.fromIndex, link.toIndex)
        }
        linkXy[linkIndex] = ortho
        if (!isDown(parent.y, map.nodes[link.toIndex].y) && !(rows.size > 1 && row > 0)) {
            addRounded(strokes, link.kind, link.fromIndex, link.toIndex, ortho)
        }
    }
    appendComb(map, linkIndexes, rows, bars, gutter, parent.x, parentBottom, first.kind, strokes)
}

private fun appendComb(
    map: ModuleMap,
    linkIndexes: IntArray,
    rows: List<IntArray>,
    bars: FloatArray,
    gutter: Float,
    parentX: Float,
    parentBottom: Float,
    kind: ModuleLinkKind,
    strokes: MutableList<GraphStroke>,
) {
    if (linkIndexes.isEmpty() || rows.isEmpty()) return
    val fromIndex = map.links[linkIndexes[0]].fromIndex
    val parentY = map.nodes[fromIndex].y
    val spine = ArrayList<Float>(rows.size * SEGMENT * 2)
    var drawing = false
    for (row in rows.indices) {
        val down = downwardChildren(map, rows[row], parentY, rows.size, row)
        if (down.isEmpty()) continue
        val bar = bars[row]
        if (!drawing) {
            addPoint(spine, parentX, parentBottom)
            addPoint(spine, parentX, bar)
            drawing = true
        } else {
            addPoint(spine, gutter, bars[row - 1])
            addPoint(spine, gutter, bar)
        }
        addPoint(spine, map.nodes[down.first()].x, bar)
        addPoint(spine, map.nodes[down.last()].x, bar)
        for (childIndex in down) {
            val child = map.nodes[childIndex]
            addRounded(
                strokes,
                kind,
                fromIndex,
                childIndex,
                floatArrayOf(child.x, bar, child.x, child.y - HALF_H),
            )
        }
    }
    if (drawing) addRounded(strokes, kind, fromIndex, map.links[linkIndexes[0]].toIndex, spine.toFloatArray())
}

private fun downwardChildren(
    map: ModuleMap,
    indexes: IntArray,
    parentY: Float,
    rowCount: Int,
    row: Int,
): IntArray {
    val kept = IntArrayBuilder()
    for (childIndex in indexes) {
        val routed = isDown(parentY, map.nodes[childIndex].y) || (rowCount > 1 && row > 0)
        if (routed) kept.add(childIndex)
    }
    return kept.toArray()
}

private fun addPoint(out: MutableList<Float>, x: Float, y: Float) {
    if (out.size >= 2 && hypot(out[out.lastIndex - 1] - x, out[out.lastIndex] - y) <= MIN_DASH) return
    out += x
    out += y
}

private fun addRounded(
    strokes: MutableList<GraphStroke>,
    kind: ModuleLinkKind,
    fromIndex: Int,
    toIndex: Int,
    xy: FloatArray,
) {
    val rounded = roundPolyline(xy, GraphScheme.BUS_RADIUS)
    if (rounded.size >= SEGMENT) strokes += GraphStroke(kind, fromIndex, toIndex, rounded, dashed = false)
}

private fun routeDependencies(
    map: ModuleMap,
    cardWidths: FloatArray?,
    linkXy: Array<FloatArray>,
    strokes: MutableList<GraphStroke>,
) {
    val groups = HashMap<Long, IntArrayBuilder>(8)
    map.links.forEachIndexed { index, link ->
        if (link.kind != ModuleLinkKind.ProjectDep) return@forEachIndexed
        if (link.fromIndex !in map.nodes.indices || link.toIndex !in map.nodes.indices) return@forEachIndexed
        val key = channelKey(map.nodes[link.fromIndex].y, map.nodes[link.toIndex].y)
        groups.getOrPut(key) { IntArrayBuilder() }.add(index)
    }
    for (builder in groups.values) {
        val members = sortedDependencies(map, builder.toArray())
        val separate = members.size <= GraphScheme.MAX_SEPARATE_DEPENDENCIES
        for (lane in members.indices) {
            val index = members[lane]
            val link = map.links[index]
            val samples = dependencyPath(
                map,
                cardWidths,
                link.fromIndex,
                link.toIndex,
                lane = if (separate) lane else 0,
                lanes = if (separate) members.size else 1,
            )
            linkXy[index] = samples
            if (!separate) continue
            addDashed(strokes, link.kind, link.fromIndex, link.toIndex, samples)
        }
        if (!separate) addRail(map, members, strokes)
    }
}

private fun addDashed(
    strokes: MutableList<GraphStroke>,
    kind: ModuleLinkKind,
    fromIndex: Int,
    toIndex: Int,
    samples: FloatArray,
) {
    val dashes = dashAlong(samples)
    if (dashes.size >= SEGMENT) strokes += GraphStroke(kind, fromIndex, toIndex, dashes, dashed = true)
}

private fun addRail(map: ModuleMap, members: IntArray, strokes: MutableList<GraphStroke>) {
    if (members.isEmpty()) return
    val anchor = map.links[members[0]]
    val from = map.nodes[anchor.fromIndex]
    val to = map.nodes[anchor.toIndex]
    val downward = to.y >= from.y
    val edgeY = if (downward) from.y + HALF_H else from.y - HALF_H
    val sign = if (downward) 1f else -1f
    val rail = edgeY + sign * GraphScheme.DEPENDENCY_STRIP
    var left = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    for (index in members) {
        val link = map.links[index]
        val start = map.nodes[link.fromIndex].x
        val end = map.nodes[link.toIndex].x
        if (start < left) left = start
        if (end < left) left = end
        if (start > right) right = start
        if (end > right) right = end
    }
    if (right - left <= MIN_DASH) return
    addDashed(strokes, ModuleLinkKind.ProjectDep, anchor.fromIndex, anchor.toIndex, floatArrayOf(left, rail, right, rail))
}

private fun sortedDependencies(map: ModuleMap, members: IntArray): IntArray =
    members.toList().sortedWith(
        compareBy(
            { map.nodes[map.links[it].fromIndex].x },
            { map.nodes[map.links[it].toIndex].x },
        ),
    ).toIntArray()

private fun dependencyPath(
    map: ModuleMap,
    cardWidths: FloatArray?,
    fromIndex: Int,
    toIndex: Int,
    lane: Int,
    lanes: Int,
): FloatArray {
    val from = map.nodes[fromIndex]
    val to = map.nodes[toIndex]
    val fromHalf = halfWidth(cardWidths, fromIndex)
    val toHalf = halfWidth(cardWidths, toIndex)
    val dy = to.y - from.y
    if (abs(dy) < GraphMetrics.CARD_H) {
        return sameRowPath(from, to, fromHalf, toHalf, lane, lanes)
    }
    val downward = dy > 0f
    val startY = from.y + if (downward) HALF_H else -HALF_H
    val endY = to.y - if (downward) HALF_H else -HALF_H
    val gap = abs(endY - startY)
    if (gap <= GraphMetrics.Y_GAP) return laneElbow(from.x, startY, to.x, endY, lane, lanes, downward)
    return gutterPath(map, from.x, startY, to.x, endY, lane, lanes, downward)
}

private fun sameRowPath(
    from: PlacedNode,
    to: PlacedNode,
    fromHalf: Float,
    toHalf: Float,
    lane: Int,
    lanes: Int,
): FloatArray {
    val sign = if (to.x >= from.x) 1f else -1f
    val startX = from.x + sign * fromHalf
    val endX = to.x - sign * toHalf
    val under = maxOf(from.y, to.y) + HALF_H + laneOffset(lane, lanes, GraphScheme.DEPENDENCY_STRIP)
    return floatArrayOf(startX, from.y, startX, under, endX, under, endX, to.y)
}

private fun laneElbow(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    lane: Int,
    lanes: Int,
    downward: Boolean,
): FloatArray {
    val gap = abs(y1 - y0)
    val room = minOf(gap, GraphScheme.DEPENDENCY_STRIP)
    val usable = (room - GraphScheme.LANE_MARGIN * 2f).coerceAtLeast(GraphScheme.LANE_PITCH)
    val across = if (lanes <= 1) HALF else lane.toFloat() / (lanes - 1).toFloat()
    val offset = GraphScheme.LANE_MARGIN + usable * across
    val bar = if (downward) y0 + offset else y0 - offset
    return floatArrayOf(x0, y0, x0, bar, x1, bar, x1, y1)
}

private fun gutterPath(
    map: ModuleMap,
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    lane: Int,
    lanes: Int,
    downward: Boolean,
): FloatArray {
    val sign = if (downward) 1f else -1f
    val gutter = map.minX + laneOffset(lane, lanes, GraphMetrics.X_GAP)
    val leave = y0 + sign * GraphMetrics.EDGE_STUB
    val approach = y1 - sign * GraphMetrics.EDGE_STUB
    return floatArrayOf(x0, y0, x0, leave, gutter, leave, gutter, approach, x1, approach, x1, y1)
}

private fun laneOffset(lane: Int, lanes: Int, room: Float): Float {
    val usable = (room - GraphScheme.LANE_MARGIN * 2f).coerceAtLeast(0f)
    if (lanes <= 1) return GraphScheme.LANE_MARGIN + usable * HALF
    val pitch = minOf(GraphScheme.LANE_PITCH, usable / (lanes - 1).toFloat())
    return GraphScheme.LANE_MARGIN + lane * pitch
}

private fun channelKey(fromY: Float, toY: Float): Long {
    val fromRow = (fromY / GraphMetrics.CARD_H).toInt().toLong()
    val toRow = (toY / GraphMetrics.CARD_H).toInt().toLong()
    return (fromRow shl ROW_SHIFT) xor (toRow and ROW_MASK)
}

private fun rowsOf(map: ModuleMap, targets: List<Int>): List<IntArray> {
    val sorted = targets.sortedBy { map.nodes[it].y }
    val rows = ArrayList<IntArrayBuilder>()
    var current = IntArrayBuilder()
    var rowY = 0f
    for (target in sorted) {
        val y = map.nodes[target].y
        if (current.size > 0 && abs(y - rowY) >= GraphMetrics.CARD_H) {
            rows += current
            current = IntArrayBuilder()
        }
        if (current.size == 0) rowY = y
        current.add(target)
    }
    if (current.size > 0) rows += current
    return rows.map { builder ->
        builder.toArray().toList().sortedBy { map.nodes[it].x }.toIntArray()
    }
}

private fun gutterX(map: ModuleMap, cardWidths: FloatArray?, targets: List<Int>): Float {
    var left = Float.POSITIVE_INFINITY
    for (target in targets) {
        val edge = map.nodes[target].x - halfWidth(cardWidths, target)
        if (edge < left) left = edge
    }
    if (!left.isFinite()) return map.nodes[targets[0]].x
    return left - GraphMetrics.EDGE_STUB
}

private fun elbow(parentX: Float, parentBottom: Float, barY: Float, child: PlacedNode): FloatArray {
    val childTop = child.y - HALF_H
    return floatArrayOf(parentX, parentBottom, parentX, barY, child.x, barY, child.x, childTop)
}

private fun gutterElbow(
    parentX: Float,
    parentBottom: Float,
    bars: FloatArray,
    gutter: Float,
    row: Int,
    child: PlacedNode,
): FloatArray {
    val childTop = child.y - HALF_H
    return floatArrayOf(
        parentX,
        parentBottom,
        parentX,
        bars[0],
        gutter,
        bars[0],
        gutter,
        bars[row],
        child.x,
        bars[row],
        child.x,
        childTop,
    )
}

private fun sideways(map: ModuleMap, cardWidths: FloatArray?, fromIndex: Int, toIndex: Int): FloatArray {
    val from = map.nodes[fromIndex]
    val to = map.nodes[toIndex]
    val points = cardConnector(
        from.x,
        from.y,
        to.x,
        to.y,
        fromHalfW = halfWidth(cardWidths, fromIndex),
        toHalfW = halfWidth(cardWidths, toIndex),
    )
    val xy = FloatArray(points.size * 2)
    points.forEachIndexed { index, point ->
        xy[index * 2] = point.x
        xy[index * 2 + 1] = point.y
    }
    return xy
}

private fun dashAlong(samples: FloatArray): FloatArray {
    if (samples.size < SEGMENT) return FloatArray(0)
    val out = ArrayList<Float>(samples.size)
    val period = GraphScheme.DASH_ON + GraphScheme.DASH_OFF
    var cursor = 0f
    var index = 2
    while (index + 1 < samples.size) {
        val x0 = samples[index - 2]
        val y0 = samples[index - 1]
        val x1 = samples[index]
        val y1 = samples[index + 1]
        val length = hypot(x1 - x0, y1 - y0)
        var traveled = 0f
        while (length > 0f && traveled < length) {
            val phase = cursor - period * (cursor / period).toInt()
            val drawing = phase < GraphScheme.DASH_ON
            val remain = if (drawing) GraphScheme.DASH_ON - phase else period - phase
            val step = minOf(remain, length - traveled)
            if (drawing && step > MIN_DASH) {
                val t0 = traveled / length
                val t1 = (traveled + step) / length
                out += x0 + (x1 - x0) * t0
                out += y0 + (y1 - y0) * t0
                out += x0 + (x1 - x0) * t1
                out += y0 + (y1 - y0) * t1
            }
            traveled += step
            cursor += step
        }
        index += 2
    }
    return out.toFloatArray()
}

private fun roundPolyline(xy: FloatArray, radius: Float): FloatArray {
    if (xy.size < 6 || radius <= 1f) return xy
    val out = ArrayList<Float>(xy.size + ARC_STEPS * 4)
    out += xy[0]
    out += xy[1]
    var corner = 2
    while (corner + 3 < xy.size) {
        val x0 = out[out.lastIndex - 1]
        val y0 = out[out.lastIndex]
        val x1 = xy[corner]
        val y1 = xy[corner + 1]
        val x2 = xy[corner + 2]
        val y2 = xy[corner + 3]
        appendCorner(out, x0, y0, x1, y1, x2, y2, radius)
        corner += 2
    }
    val endX = xy[xy.lastIndex - 1]
    val endY = xy[xy.lastIndex]
    val endGap = hypot(out[out.lastIndex - 1] - endX, out[out.lastIndex] - endY)
    if (endGap > MIN_DASH) {
        out += endX
        out += endY
    }
    return out.toFloatArray()
}

private fun appendCorner(
    out: MutableList<Float>,
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    radius: Float,
) {
    val dx1 = x1 - x0
    val dy1 = y1 - y0
    val dx2 = x2 - x1
    val dy2 = y2 - y1
    val len1 = hypot(dx1, dy1)
    val len2 = hypot(dx2, dy2)
    val turn = abs(dx1 * dy2 - dy1 * dx2)
    if (len1 < 1f || len2 < 1f || turn < 1f) {
        out += x1
        out += y1
        return
    }
    val r = minOf(radius, len1 * HALF, len2 * HALF)
    val ax = x1 - dx1 / len1 * r
    val ay = y1 - dy1 / len1 * r
    val bx = x1 + dx2 / len2 * r
    val by = y1 + dy2 / len2 * r
    out += ax
    out += ay
    val cx = ax + bx - x1
    val cy = ay + by - y1
    val start = atan2(ay - cy, ax - cx)
    var sweep = atan2(by - cy, bx - cx) - start
    if (sweep > PI) sweep -= FULL_TURN
    if (sweep < -PI) sweep += FULL_TURN
    for (step in 1..ARC_STEPS) {
        val angle = start + sweep * step / ARC_STEPS
        out += cx + r * cos(angle)
        out += cy + r * sin(angle)
    }
}

private fun barBetween(aboveBottom: Float, belowTop: Float): Float =
    if (belowTop > aboveBottom) (aboveBottom + belowTop) * HALF else aboveBottom + GraphMetrics.EDGE_STUB

private fun isDown(fromY: Float, toY: Float): Boolean = toY - fromY >= HALF_H

private fun halfWidth(cardWidths: FloatArray?, index: Int): Float {
    val width = cardWidths?.getOrNull(index) ?: GraphMetrics.CARD_W
    if (!width.isFinite() || width <= 0f) return GraphMetrics.CARD_W * HALF
    return width * HALF
}

private fun groupKey(fromIndex: Int, kind: ModuleLinkKind): Long =
    (fromIndex.toLong() shl KIND_SHIFT) or kind.ordinal.toLong()

private class IntArrayBuilder {
    private var values = IntArray(8)
    var size: Int = 0
        private set

    fun add(value: Int) {
        if (size == values.size) values = values.copyOf(values.size * 2)
        values[size++] = value
    }

    fun toArray(): IntArray = values.copyOf(size)
}

private const val HALF = 0.5f
private const val HALF_H = GraphMetrics.CARD_H * HALF
private const val ARC_STEPS = GraphScheme.ARC_STEPS
private const val ROW_SHIFT = 32
private const val ROW_MASK = 0xffffffffL
private const val SEGMENT = 4
private const val KIND_SHIFT = 2
private const val MIN_DASH = 0.25f
private const val FULL_TURN = 6.2831855f
