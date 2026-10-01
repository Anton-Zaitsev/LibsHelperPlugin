package com.zaycev.libshelper.core.graph

fun cardConnector(
    fromX: Float,
    fromY: Float,
    toX: Float,
    toY: Float,
    fromHalfW: Float = GraphMetrics.CARD_W * HALF,
    toHalfW: Float = GraphMetrics.CARD_W * HALF,
): List<GraphPoint> {
    val halfH = GraphMetrics.CARD_H * HALF
    val down = toY - fromY >= GraphMetrics.CARD_H * HALF
    if (down) {
        val startY = fromY + halfH
        val endY = toY - halfH
        val midY = if (endY > startY) (startY + endY) * HALF else startY + GraphMetrics.EDGE_STUB
        return listOf(
            GraphPoint(fromX, startY),
            GraphPoint(fromX, midY),
            GraphPoint(toX, midY),
            GraphPoint(toX, endY.coerceAtLeast(midY)),
        )
    }
    val up = fromY - toY >= GraphMetrics.CARD_H * HALF
    if (up) {
        val startY = fromY - halfH
        val endY = toY + halfH
        val midY = if (startY > endY) (startY + endY) * HALF else startY - GraphMetrics.EDGE_STUB
        return listOf(
            GraphPoint(fromX, startY),
            GraphPoint(fromX, midY),
            GraphPoint(toX, midY),
            GraphPoint(toX, endY.coerceAtMost(midY)),
        )
    }
    val rightward = toX >= fromX
    val startX = fromX + if (rightward) fromHalfW else -fromHalfW
    val endX = toX + if (rightward) -toHalfW else toHalfW
    val underY = maxOf(fromY, toY) + halfH + GraphMetrics.EDGE_STUB
    return listOf(
        GraphPoint(startX, fromY),
        GraphPoint(startX, underY),
        GraphPoint(endX, underY),
        GraphPoint(endX, toY),
    )
}

class EdgeIndex internal constructor(
    val count: Int,
    private val offsets: IntArray,
    private val xy: FloatArray,
    private val bounds: FloatArray,
    private val lookup: EdgeLookup,
) {
    fun pointCount(index: Int): Int {
        val start = offsets[index]
        val end = if (index + 1 < count) offsets[index + 1] else xy.size / 2
        return end - start
    }

    fun x(index: Int, point: Int): Float = xy[(offsets[index] + point) * 2]

    fun y(index: Int, point: Int): Float = xy[(offsets[index] + point) * 2 + 1]

    fun intersects(index: Int, left: Float, top: Float, right: Float, bottom: Float): Boolean {
        val base = index * 4
        if (bounds[base + 2] < left || bounds[base] > right || bounds[base + 3] < top || bounds[base + 1] > bottom) {
            return false
        }
        val points = pointCount(index)
        if (points == 0) return false
        var previous = 0
        while (previous < points) {
            val px = x(index, previous)
            val py = y(index, previous)
            if (px in left..right && py in top..bottom) return true
            if (previous > 0) {
                val qx = x(index, previous - 1)
                val qy = y(index, previous - 1)
                if (segmentHitsRect(qx, qy, px, py, left, top, right, bottom)) return true
            }
            previous++
        }
        return false
    }

    fun query(left: Float, top: Float, right: Float, bottom: Float, out: IntArray, outSize: IntArray) {
        lookup.query(left, top, right, bottom, out, outSize)
    }
}

fun edgeIndexOf(map: ModuleMap, cardWidths: FloatArray? = null): EdgeIndex {
    val routes = moduleRoutes(map, cardWidths)
    val links = map.links
    val offsets = IntArray(links.size)
    val xy = ArrayList<Float>(links.size * 8)
    val bounds = FloatArray(links.size * 4)
    links.indices.forEach { index ->
        offsets[index] = xy.size / 2
        val coords = routes.linkXy[index]
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var point = 0
        while (point + 1 < coords.size) {
            val x = coords[point]
            val y = coords[point + 1]
            xy += x
            xy += y
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
            point += 2
        }
        val base = index * 4
        bounds[base] = minX
        bounds[base + 1] = minY
        bounds[base + 2] = maxX
        bounds[base + 3] = maxY
    }
    val lookup = EdgeLookup(map.minX, map.minY, GraphMetrics.X_GAP, links.size)
    links.indices.forEach { index ->
        val base = index * 4
        lookup.add(index, bounds[base], bounds[base + 1], bounds[base + 2], bounds[base + 3])
    }
    return EdgeIndex(links.size, offsets, xy.toFloatArray(), bounds, lookup)
}

internal class EdgeLookup(
    private val originX: Float,
    private val originY: Float,
    private val cell: Float,
    count: Int,
) {
    private val buckets = HashMap<Long, IntArray>(64)
    private val counts = HashMap<Long, Int>(64)
    private val overflow = ArrayList<Int>()
    private val stamp = IntArray(count)
    private var epoch = 0

    fun add(index: Int, minX: Float, minY: Float, maxX: Float, maxY: Float) {
        if (!minX.isFinite() || !minY.isFinite() || !maxX.isFinite() || !maxY.isFinite()) return
        val c0 = col(minX)
        val c1 = col(maxX)
        val r0 = row(minY)
        val r1 = row(maxY)
        val cols = c1 - c0 + 1
        val rows = r1 - r0 + 1
        if (cols <= 0 || rows <= 0 || cols > MAX_EDGE_CELLS || rows > MAX_EDGE_CELLS) {
            overflow += index
            return
        }
        var column = c0
        while (column <= c1) {
            var row = r0
            while (row <= r1) {
                insert(pack(column, row), index)
                row++
            }
            column++
        }
    }

    fun query(left: Float, top: Float, right: Float, bottom: Float, out: IntArray, outSize: IntArray) {
        epoch++
        if (epoch == Int.MAX_VALUE) {
            stamp.fill(0)
            epoch = 1
        }
        var size = 0
        val c0 = col(left)
        val c1 = col(right)
        val r0 = row(top)
        val r1 = row(bottom)
        var column = c0
        while (column <= c1 && size < out.size) {
            var row = r0
            while (row <= r1 && size < out.size) {
                size = emitBucket(pack(column, row), out, size)
                row++
            }
            column++
        }
        var index = 0
        while (index < overflow.size && size < out.size) {
            size = emit(overflow[index], out, size)
            index++
        }
        outSize[0] = size
    }

    private fun emitBucket(key: Long, out: IntArray, start: Int): Int {
        val bucket = buckets[key] ?: return start
        val used = counts[key] ?: return start
        var size = start
        var index = 0
        while (index < used && size < out.size) {
            size = emit(bucket[index], out, size)
            index++
        }
        return size
    }

    private fun emit(index: Int, out: IntArray, size: Int): Int {
        if (index !in stamp.indices || stamp[index] == epoch) return size
        stamp[index] = epoch
        out[size] = index
        return size + 1
    }

    private fun insert(key: Long, index: Int) {
        val current = buckets[key]
        val used = counts[key] ?: 0
        if (current == null) {
            val fresh = IntArray(4)
            fresh[0] = index
            buckets[key] = fresh
            counts[key] = 1
            return
        }
        if (used == current.size) {
            val grown = current.copyOf(current.size * 2)
            grown[used] = index
            buckets[key] = grown
        } else {
            current[used] = index
        }
        counts[key] = used + 1
    }

    private fun col(x: Float): Int = kotlin.math.floor((x - originX) / cell).toInt()

    private fun row(y: Float): Int = kotlin.math.floor((y - originY) / cell).toInt()

    private fun pack(column: Int, row: Int): Long =
        (column.toLong() shl CELL_SHIFT) xor (row.toLong() and LOW_BITS)
}

private fun segmentHitsRect(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
): Boolean {
    val minX = minOf(x1, x2)
    val maxX = maxOf(x1, x2)
    val minY = minOf(y1, y2)
    val maxY = maxOf(y1, y2)
    return maxX >= left && minX <= right && maxY >= top && minY <= bottom
}

data class PngFrame(
    val width: Int,
    val height: Int,
    val pixelScale: Float,
)

fun pngFrame(
    worldW: Float,
    worldH: Float,
    requestedScale: Float = EXPORT_PIXELS_PER_WORLD,
    maxSide: Int = EXPORT_MAX_SIDE,
    maxPixels: Int = EXPORT_MAX_PIXELS,
): PngFrame {
    val baseW = worldW.coerceAtLeast(1f)
    val baseH = worldH.coerceAtLeast(1f)
    val sideLimit = maxSide.coerceAtLeast(1)
    val pixelLimit = maxPixels.coerceAtLeast(1).toLong()
    var scale = requestedScale.coerceAtLeast(MIN_EXPORT_SCALE)
    var width = rawSide(baseW, scale)
    var height = rawSide(baseH, scale)
    if (width > sideLimit || height > sideLimit) {
        scale *= minOf(sideLimit.toFloat() / width, sideLimit.toFloat() / height)
        width = rawSide(baseW, scale)
        height = rawSide(baseH, scale)
    }
    repeat(PIXEL_FIT_PASSES) {
        val pixels = width.toLong() * height.toLong()
        if (pixels <= pixelLimit && width <= sideLimit && height <= sideLimit) return@repeat
        val sideShrink = if (width > sideLimit || height > sideLimit) {
            minOf(sideLimit.toFloat() / width, sideLimit.toFloat() / height)
        } else {
            1f
        }
        val pixelShrink = if (pixels > pixelLimit) {
            kotlin.math.sqrt(pixelLimit.toDouble() / pixels.toDouble()).toFloat()
        } else {
            1f
        }
        scale *= minOf(sideShrink, pixelShrink)
        width = rawSide(baseW, scale)
        height = rawSide(baseH, scale)
    }
    return PngFrame(
        width.coerceIn(1, sideLimit),
        height.coerceIn(1, sideLimit),
        scale,
    )
}

private fun rawSide(world: Float, scale: Float): Int {
    val pixels = kotlin.math.ceil((world * scale).toDouble())
    if (!pixels.isFinite() || pixels >= Int.MAX_VALUE) return Int.MAX_VALUE
    return pixels.toInt().coerceAtLeast(1)
}

private const val MAX_EDGE_CELLS = 6
private const val CELL_SHIFT = 32
private const val LOW_BITS = 0xffffffffL
private const val PIXEL_FIT_PASSES = 2

private const val MIN_EXPORT_SCALE = 0.05f
const val EXPORT_PIXELS_PER_WORLD = 16f
const val EXPORT_MAX_SIDE = 65_536
const val EXPORT_MAX_PIXELS = 128_000_000

private const val HALF = 0.5f

fun connectorIntersects(
    fromX: Float,
    fromY: Float,
    toX: Float,
    toY: Float,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
): Boolean {
    val points = cardConnector(fromX, fromY, toX, toY)
    if (points.isEmpty()) return false
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    for (point in points) {
        if (point.x < minX) minX = point.x
        if (point.y < minY) minY = point.y
        if (point.x > maxX) maxX = point.x
        if (point.y > maxY) maxY = point.y
    }
    return maxX >= left && minX <= right && maxY >= top && minY <= bottom
}
