package com.zaycev.libshelper.core.graph

class SpatialGrid(
    private val cell: Float,
    private val originX: Float,
    private val originY: Float,
) {
    init {
        require(cell > 0f) { "cell must be positive" }
    }
    private val buckets = HashMap<Long, IntArray>(64)
    private val counts = HashMap<Long, Int>(64)

    fun insert(index: Int, x: Float, y: Float) {
        val key = pack(col(x), row(y))
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

    fun query(left: Float, top: Float, right: Float, bottom: Float, out: IntArray, outSize: IntArray) {
        var size = 0
        val c0 = col(left)
        val c1 = col(right)
        val r0 = row(top)
        val r1 = row(bottom)
        var col = c0
        while (col <= c1) {
            var row = r0
            while (row <= r1) {
                val key = pack(col, row)
                val bucket = buckets[key]
                val used = counts[key] ?: 0
                if (bucket != null) {
                    var i = 0
                    while (i < used && size < out.size) {
                        out[size++] = bucket[i]
                        i++
                    }
                }
                row++
            }
            col++
        }
        outSize[0] = size
    }

    private fun col(x: Float): Int = ((x - originX) / cell).toInt()

    private fun row(y: Float): Int = ((y - originY) / cell).toInt()

    private fun pack(col: Int, row: Int): Long =
        (col.toLong() shl CELL_SHIFT) xor (row.toLong() and LOW_BITS)
}

private const val CELL_SHIFT = 32
private const val LOW_BITS = 0xffffffffL

fun spatialGridOf(map: ModuleMap, cell: Float = GraphMetrics.X_GAP): SpatialGrid {
    val grid = SpatialGrid(cell, map.minX, map.minY)
    map.nodes.forEachIndexed { index, node ->
        grid.insert(index, node.x, node.y)
    }
    return grid
}

fun nearestNodeIndex(
    map: ModuleMap,
    grid: SpatialGrid,
    worldX: Float,
    worldY: Float,
    maxDist: Float,
    buffer: IntArray,
    sizeOut: IntArray,
): Int {
    grid.query(worldX - maxDist, worldY - maxDist, worldX + maxDist, worldY + maxDist, buffer, sizeOut)
    val limit = sizeOut[0]
    var best = -1
    var bestDist = maxDist * maxDist
    var i = 0
    while (i < limit) {
        val index = buffer[i]
        val node = map.nodes[index]
        val dx = node.x - worldX
        val dy = node.y - worldY
        val dist = dx * dx + dy * dy
        if (dist <= bestDist) {
            bestDist = dist
            best = index
        }
        i++
    }
    return best
}

fun hitCardIndex(
    map: ModuleMap,
    grid: SpatialGrid,
    worldX: Float,
    worldY: Float,
    buffer: IntArray,
    sizeOut: IntArray,
    cardWidths: FloatArray? = null,
): Int {
    val halfH = GraphMetrics.CARD_H * HALF_CARD
    val queryHalf = cardWidths?.let { widestHalf(it) } ?: (GraphMetrics.CARD_W * HALF_CARD)
    grid.query(worldX - queryHalf, worldY - halfH, worldX + queryHalf, worldY + halfH, buffer, sizeOut)
    val limit = sizeOut[0]
    var best = -1
    var bestDist = Float.MAX_VALUE
    var i = 0
    while (i < limit) {
        val index = buffer[i]
        val node = map.nodes[index]
        val halfW = halfWidthAt(cardWidths, index)
        val dx = node.x - worldX
        val dy = node.y - worldY
        val inside = dx <= halfW && dx >= -halfW && dy <= halfH && dy >= -halfH
        if (inside) {
            val dist = dx * dx + dy * dy
            if (dist <= bestDist) {
                bestDist = dist
                best = index
            }
        }
        i++
    }
    return best
}

private fun widestHalf(cardWidths: FloatArray): Float {
    var widest = GraphMetrics.CARD_W
    for (width in cardWidths) {
        if (width.isFinite() && width > widest) widest = width
    }
    return widest * HALF_CARD
}

private fun halfWidthAt(cardWidths: FloatArray?, index: Int): Float {
    val width = cardWidths?.getOrNull(index) ?: GraphMetrics.CARD_W
    if (!width.isFinite() || width <= 0f) return GraphMetrics.CARD_W * HALF_CARD
    return width * HALF_CARD
}

private const val HALF_CARD = 0.5f
