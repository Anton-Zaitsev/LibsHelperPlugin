package com.zaycev.libshelper.core.graph

fun cardConnector(
    fromX: Float,
    fromY: Float,
    toX: Float,
    toY: Float,
): List<Pair<Float, Float>> {
    val halfW = GraphMetrics.CARD_W * HALF
    val halfH = GraphMetrics.CARD_H * HALF
    val down = toY - fromY >= GraphMetrics.CARD_H * HALF
    if (down) {
        val startY = fromY + halfH
        val endY = toY - halfH
        val midY = if (endY > startY) (startY + endY) * HALF else startY + GraphMetrics.EDGE_STUB
        return listOf(
            fromX to startY,
            fromX to midY,
            toX to midY,
            toX to endY.coerceAtLeast(midY),
        )
    }
    val up = fromY - toY >= GraphMetrics.CARD_H * HALF
    if (up) {
        val startY = fromY - halfH
        val endY = toY + halfH
        val midY = if (startY > endY) (startY + endY) * HALF else startY - GraphMetrics.EDGE_STUB
        return listOf(
            fromX to startY,
            fromX to midY,
            toX to midY,
            toX to endY.coerceAtMost(midY),
        )
    }
    val rightward = toX >= fromX
    val startX = fromX + if (rightward) halfW else -halfW
    val endX = toX + if (rightward) -halfW else halfW
    val underY = maxOf(fromY, toY) + halfH + GraphMetrics.EDGE_STUB
    return listOf(
        startX to fromY,
        startX to underY,
        endX to underY,
        endX to toY,
    )
}

private const val HALF = 0.5f
