package com.zaycev.libshelper.core.graph

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min

data class CameraFrame(
    val x: Float,
    val y: Float,
    val scale: Float,
)

object GraphMetrics {
    const val CARD_W = 248f
    const val CARD_H = 72f
    const val X_GAP = 280f
    const val Y_GAP = 156f
    const val EDGE_STUB = 20f
    const val MIN_SCALE = 0.06f
    const val MAX_SCALE = 3.5f
    const val FIT_PAD = 0.88f
    const val FIT_MAX = 1.35f
    const val ZOOM_SENSITIVITY = 0.055f
    const val PAN_GAIN = 1.45f
    const val SCROLL_CLAMP = 6f
    const val BUTTON_ZOOM = 1.25f
    const val DOUBLE_CLICK_ZOOM = 1.75f
    const val FOCUS_SCALE = 1.1f
    const val FLY_BASE_MS = 320
    const val FLY_DIST_MS = 160f
    const val FLY_SCALE_MS = 220f
    const val FLY_MIN_MS = 280
    const val FLY_MAX_MS = 560
    const val FLY_NEAR_DIST2 = 4f
    const val FLY_NEAR_SCALE = 0.012f
}

fun worldFromScreen(
    screenX: Float,
    screenY: Float,
    viewW: Float,
    viewH: Float,
    camX: Float,
    camY: Float,
    scale: Float,
): Pair<Float, Float> {
    val safeScale = scale.coerceAtLeast(GraphMetrics.MIN_SCALE)
    return (screenX - viewW * HALF) / safeScale + camX to
        (screenY - viewH * HALF) / safeScale + camY
}

fun screenFromWorld(
    worldX: Float,
    worldY: Float,
    viewW: Float,
    viewH: Float,
    camX: Float,
    camY: Float,
    scale: Float,
): Pair<Float, Float> {
    val safeScale = scale.coerceAtLeast(GraphMetrics.MIN_SCALE)
    return (worldX - camX) * safeScale + viewW * HALF to
        (worldY - camY) * safeScale + viewH * HALF
}

fun zoomFactorFromScroll(deltaY: Float): Float {
    val clamped = deltaY.coerceIn(-GraphMetrics.SCROLL_CLAMP, GraphMetrics.SCROLL_CLAMP)
    return exp(-clamped * GraphMetrics.ZOOM_SENSITIVITY)
}

fun zoomCamera(
    pivotX: Float,
    pivotY: Float,
    viewW: Float,
    viewH: Float,
    camX: Float,
    camY: Float,
    scale: Float,
    factor: Float,
): CameraFrame {
    if (viewW < MIN_VIEW || viewH < MIN_VIEW) {
        return CameraFrame(camX, camY, scale.coerceIn(GraphMetrics.MIN_SCALE, GraphMetrics.MAX_SCALE))
    }
    val (worldX, worldY) = worldFromScreen(pivotX, pivotY, viewW, viewH, camX, camY, scale)
    val next = (scale * factor).coerceIn(GraphMetrics.MIN_SCALE, GraphMetrics.MAX_SCALE)
    return CameraFrame(
        x = worldX - (pivotX - viewW * HALF) / next,
        y = worldY - (pivotY - viewH * HALF) / next,
        scale = next,
    )
}

fun panCamera(
    deltaScreenX: Float,
    deltaScreenY: Float,
    camX: Float,
    camY: Float,
    scale: Float,
): CameraFrame {
    val safeScale = scale.coerceAtLeast(GraphMetrics.MIN_SCALE)
    val gain = GraphMetrics.PAN_GAIN / safeScale
    return CameraFrame(
        x = camX - deltaScreenX * gain,
        y = camY - deltaScreenY * gain,
        scale = safeScale,
    )
}

fun fitCamera(
    minX: Float,
    minY: Float,
    maxX: Float,
    maxY: Float,
    viewW: Float,
    viewH: Float,
): CameraFrame {
    val worldW = (maxX - minX).coerceAtLeast(MIN_VIEW)
    val worldH = (maxY - minY).coerceAtLeast(MIN_VIEW)
    val safeW = viewW.coerceAtLeast(MIN_VIEW)
    val safeH = viewH.coerceAtLeast(MIN_VIEW)
    val scale = (min(safeW / worldW, safeH / worldH) * GraphMetrics.FIT_PAD)
        .coerceIn(GraphMetrics.MIN_SCALE, GraphMetrics.FIT_MAX)
    return CameraFrame(
        x = (minX + maxX) * HALF,
        y = (minY + maxY) * HALF,
        scale = scale,
    )
}

fun focusCamera(nodeX: Float, nodeY: Float, currentScale: Float): CameraFrame =
    CameraFrame(
        x = nodeX,
        y = nodeY,
        scale = currentScale
            .coerceAtLeast(GraphMetrics.FOCUS_SCALE)
            .coerceIn(GraphMetrics.MIN_SCALE, GraphMetrics.MAX_SCALE),
    )

fun lerpCamera(from: CameraFrame, to: CameraFrame, t: Float): CameraFrame {
    val k = t.coerceIn(0f, 1f)
    return CameraFrame(
        x = from.x + (to.x - from.x) * k,
        y = from.y + (to.y - from.y) * k,
        scale = from.scale + (to.scale - from.scale) * k,
    )
}

fun cameraNear(from: CameraFrame, to: CameraFrame): Boolean {
    val dx = from.x - to.x
    val dy = from.y - to.y
    return dx * dx + dy * dy <= GraphMetrics.FLY_NEAR_DIST2 &&
        abs(from.scale - to.scale) <= GraphMetrics.FLY_NEAR_SCALE
}

fun cameraFlyDurationMs(from: CameraFrame, to: CameraFrame): Int {
    val dist = hypot(to.x - from.x, to.y - from.y)
    val distPart = dist / GraphMetrics.X_GAP * GraphMetrics.FLY_DIST_MS
    val scalePart = abs(to.scale - from.scale) * GraphMetrics.FLY_SCALE_MS
    return (GraphMetrics.FLY_BASE_MS + distPart + scalePart)
        .toInt()
        .coerceIn(GraphMetrics.FLY_MIN_MS, GraphMetrics.FLY_MAX_MS)
}

fun modulePathLabel(id: String, parentId: String?): String {
    if (id == ":") return "root"
    val hash = id.substringAfter('#', missingDelimiterValue = "")
    if (hash.isNotEmpty()) {
        val module = id.substringBefore('#')
        return "$module → $id"
    }
    val parent = parentId ?: return id
    if (parent == ":") return id
    return "$parent → $id"
}

private const val HALF = 0.5f
private const val MIN_VIEW = 1f
