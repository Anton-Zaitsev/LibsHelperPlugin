package com.zaycev.libshelper.ide.ui.compose

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import com.zaycev.libshelper.core.graph.GraphMetrics
import com.zaycev.libshelper.core.graph.ModuleMap
import com.zaycev.libshelper.core.graph.SpatialGrid
import com.zaycev.libshelper.core.graph.cardConnector
import com.zaycev.libshelper.core.graph.expandVisibleKmpChildren
import com.zaycev.libshelper.core.model.ModuleLinkKind
import kotlinx.collections.immutable.ImmutableSet
import kotlin.math.roundToInt

internal const val GRAPH_HEIGHT_DP = 480
internal const val QUERY_CAP = 4096

private const val SCALE_CARD = 0.16f
private const val SCALE_BADGE = 0.4f
private const val MAX_DRAW_NODES = 720
private const val MAX_DRAW_ALL = 4096
private const val MAX_DRAW_EDGES = 1000
private const val VIEW_PAD = 48f
private const val DEPTH_FAR = 0.18f
private const val DEPTH_MID = 0.32f
private const val DEPTH_NEAR = 0.55f
private const val RADIUS_DOT = 3.4f
private const val DOT_DRAW = 3.2f
private const val FADE_ALPHA = 0.45f
private const val CARD_CORNER = 14f
private const val CARD_STRIPE = 7f
private const val CARD_PAD = 10f
private const val CARD_SHADOW = 3f
private const val CARD_BORDER = 1.2f
private const val STRIPE_SCALE_MIN = 0.7f
private const val CORNER_SCALE_MAX = 1.2f
private const val RING_STROKE = 2.6f
private const val EDGE_HIERARCHY = 3.2f
private const val EDGE_TARGET = 2.8f
private const val EDGE_DEP = 2.6f
private const val DASH = 10f
private const val GAP = 7f
private const val HALF = 0.5f
private const val MIN_TEXT_PX = 16
private const val BADGE_MIN = 16f
private const val BADGE_MAX = 26f
private const val BADGE_SHARE = 0.52f
private val CardFaceDark = Color(0xFF2C3038)
private val CardFaceLight = Color(0xFFF7F8FA)
private val CardBorderDark = Color(0xFF4A5160)
private val CardBorderLight = Color(0xFFD0D5DE)
private val ShadowDark = Color(0x66000000)
private val ShadowLight = Color(0x33000000)

@Stable
internal class GraphCam {
    var scale = 1f
    var x = 0f
    var y = 0f
}

@Stable
internal class GraphScratch {
    val queryBuf = IntArray(QUERY_CAP)
    val querySize = IntArray(1)
    val visibleSet = HashSet<Int>(MAX_DRAW_ALL)
    val hierarchyPath = Path()
    val depPath = Path()
    val targetPath = Path()
    val dash = PathEffect.dashPathEffect(floatArrayOf(DASH, GAP), 0f)
}

@Immutable
internal data class GraphHighlight(
    val hoverIndex: Int,
    val selectedId: String?,
    val glow: ImmutableSet<String>,
    val matchIds: ImmutableSet<String>,
    val revealAll: Boolean,
)

@Stable
internal class GraphText(
    val measurer: TextMeasurer,
    val labelStyle: TextStyle,
    val glyphStyle: TextStyle,
)

internal fun DrawScope.drawModuleGraph(
    map: ModuleMap,
    grid: SpatialGrid,
    scratch: GraphScratch,
    text: GraphText,
    palette: GraphPalette,
    dark: Boolean,
    cam: GraphCam,
    highlight: GraphHighlight,
) {
    if (map.nodes.isEmpty() || size.width < 1f) return
    val halfW = size.width * HALF
    val halfH = size.height * HALF
    val world = worldView(cam, halfW, halfH)
    collectVisible(map, grid, scratch, world, cam.scale, highlight)
    drawGraphEdges(map, scratch, palette, cam, halfW, halfH, world, highlight)
    drawGraphNodes(map, scratch, text, palette, dark, cam, halfW, halfH, highlight)
}

private class WorldView(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

private fun worldView(cam: GraphCam, halfW: Float, halfH: Float): WorldView {
    val safeScale = cam.scale.coerceAtLeast(GraphMetrics.MIN_SCALE)
    val pad = VIEW_PAD / safeScale
    return WorldView(
        left = cam.x - halfW / safeScale - pad,
        top = cam.y - halfH / safeScale - pad,
        right = cam.x + halfW / safeScale + pad,
        bottom = cam.y + halfH / safeScale + pad,
    )
}

private fun collectVisible(
    map: ModuleMap,
    grid: SpatialGrid,
    scratch: GraphScratch,
    world: WorldView,
    scale: Float,
    highlight: GraphHighlight,
) {
    val depthCap = maxDepthForScale(scale)
    val drawCap = if (highlight.revealAll) Int.MAX_VALUE else MAX_DRAW_NODES
    grid.query(world.left, world.top, world.right, world.bottom, scratch.queryBuf, scratch.querySize)
    scratch.visibleSet.clear()
    var i = 0
    var drawn = 0
    val visibleCount = scratch.querySize[0]
    while (i < visibleCount && drawn < drawCap) {
        val index = scratch.queryBuf[i]
        val node = map.nodes[index]
        val keep = highlight.revealAll ||
            node.depth <= depthCap ||
            node.id == highlight.selectedId ||
            node.id in highlight.glow ||
            node.id in highlight.matchIds ||
            index == highlight.hoverIndex
        if (keep) {
            scratch.visibleSet += index
            drawn++
        }
        i++
    }
    expandVisibleKmpChildren(map, scratch.visibleSet)
}

private fun DrawScope.drawGraphEdges(
    map: ModuleMap,
    scratch: GraphScratch,
    palette: GraphPalette,
    cam: GraphCam,
    halfW: Float,
    halfH: Float,
    world: WorldView,
    highlight: GraphHighlight,
) {
    scratch.hierarchyPath.rewind()
    scratch.depPath.rewind()
    scratch.targetPath.rewind()
    var edges = 0
    map.links.forEach { link ->
        if (!highlight.revealAll && edges >= MAX_DRAW_EDGES) return@forEach
        if (link.fromIndex !in scratch.visibleSet && link.toIndex !in scratch.visibleSet) return@forEach
        val from = map.nodes[link.fromIndex]
        val to = map.nodes[link.toIndex]
        if (!keepEdge(link.kind, cam.scale, from.id, to.id, highlight.selectedId, highlight.revealAll)) return@forEach
        if (!segmentVisible(from.x, from.y, to.x, to.y, world.left, world.top, world.right, world.bottom)) return@forEach
        val path = when (link.kind) {
            ModuleLinkKind.Hierarchy -> scratch.hierarchyPath
            ModuleLinkKind.ProjectDep -> scratch.depPath
            ModuleLinkKind.Target -> scratch.targetPath
        }
        appendConnector(path, from.x, from.y, to.x, to.y, cam, halfW, halfH)
        edges++
    }
    drawPath(scratch.hierarchyPath, palette.hierarchy, style = Stroke(width = EDGE_HIERARCHY, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(scratch.targetPath, palette.targetLink, style = Stroke(width = EDGE_TARGET, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(
        scratch.depPath,
        palette.projectDep,
        style = Stroke(
            width = EDGE_DEP,
            pathEffect = scratch.dash,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}

private fun appendConnector(
    path: Path,
    fromX: Float,
    fromY: Float,
    toX: Float,
    toY: Float,
    cam: GraphCam,
    halfW: Float,
    halfH: Float,
) {
    val points = cardConnector(fromX, fromY, toX, toY)
    if (points.isEmpty()) return
    val first = points.first()
    path.moveTo(sx(first.first, cam.x, cam.scale, halfW), sy(first.second, cam.y, cam.scale, halfH))
    var i = 1
    while (i < points.size) {
        val point = points[i]
        path.lineTo(sx(point.first, cam.x, cam.scale, halfW), sy(point.second, cam.y, cam.scale, halfH))
        i++
    }
}

private fun keepEdge(
    kind: ModuleLinkKind,
    scale: Float,
    fromId: String,
    toId: String,
    selectedId: String?,
    revealAll: Boolean,
): Boolean {
    if (revealAll) return true
    return when (kind) {
        ModuleLinkKind.ProjectDep -> scale >= SCALE_BADGE || fromId == selectedId || toId == selectedId
        ModuleLinkKind.Target -> true
        ModuleLinkKind.Hierarchy -> true
    }
}

private fun DrawScope.drawGraphNodes(
    map: ModuleMap,
    scratch: GraphScratch,
    text: GraphText,
    palette: GraphPalette,
    dark: Boolean,
    cam: GraphCam,
    halfW: Float,
    halfH: Float,
    highlight: GraphHighlight,
) {
    val cardW = GraphMetrics.CARD_W * cam.scale
    val cardH = GraphMetrics.CARD_H * cam.scale
    val corner = CARD_CORNER * cam.scale.coerceAtMost(CORNER_SCALE_MAX)
    val stripe = CARD_STRIPE * cam.scale.coerceAtLeast(STRIPE_SCALE_MIN)
    val dimOthers = highlight.glow.isNotEmpty() && cam.scale >= SCALE_BADGE
    val face = if (dark) CardFaceDark else CardFaceLight
    val border = if (dark) CardBorderDark else CardBorderLight
    val shadow = if (dark) ShadowDark else ShadowLight
    scratch.visibleSet.forEach { index ->
        val node = map.nodes[index]
        val x = sx(node.x, cam.x, cam.scale, halfW)
        val y = sy(node.y, cam.y, cam.scale, halfH)
        val fill = graphKindFill(node.kind, dark)
        val hot = node.id == highlight.selectedId || index == highlight.hoverIndex || node.id in highlight.matchIds
        val faded = dimOthers && node.id !in highlight.glow && !hot
        val color = if (faded) fill.copy(alpha = FADE_ALPHA) else fill
        val ring = when {
            node.id == highlight.selectedId -> palette.selected
            node.id in highlight.matchIds -> palette.match
            index == highlight.hoverIndex -> palette.label
            else -> null
        }
        if (cam.scale < SCALE_CARD) {
            drawCircle(color, radius = DOT_DRAW, center = Offset(x, y))
            if (ring != null) {
                drawCircle(ring, radius = RADIUS_DOT + RING_STROKE, center = Offset(x, y), style = Stroke(width = RING_STROKE))
            }
            return@forEach
        }
        val left = x - cardW * HALF
        val top = y - cardH * HALF
        val cardSize = Size(cardW, cardH)
        val radius = CornerRadius(corner, corner)
        drawRoundRect(shadow, Offset(left + CARD_SHADOW, top + CARD_SHADOW), cardSize, radius)
        drawRoundRect(if (faded) face.copy(alpha = FADE_ALPHA) else face, Offset(left, top), cardSize, radius)
        drawRoundRect(border, Offset(left, top), cardSize, radius, style = Stroke(width = CARD_BORDER))
        drawRoundRect(color, Offset(left, top), Size(stripe, cardH), CornerRadius(corner * HALF, corner * HALF))
        if (ring != null) {
            drawRoundRect(ring, Offset(left, top), cardSize, radius, style = Stroke(width = RING_STROKE))
        }
        val badge = (cardH * BADGE_SHARE).coerceIn(BADGE_MIN, BADGE_MAX)
        val badgeCx = left + stripe + CARD_PAD + badge * HALF
        val badgeCy = y
        drawCircle(color, radius = badge * HALF, center = Offset(badgeCx, badgeCy))
        val glyphBox = badge.roundToInt().coerceAtLeast(1)
        val glyph = measureSafe(text.measurer, graphKindGlyph(node.kind), text.glyphStyle, glyphBox, glyphBox)
        if (glyph != null) {
            drawText(
                glyph,
                topLeft = Offset(
                    badgeCx - glyph.size.width * HALF,
                    badgeCy - glyph.size.height * HALF,
                ),
            )
        }
        val textLeft = badgeCx + badge * HALF + CARD_PAD
        val textWidth = left + cardW - CARD_PAD - textLeft
        val textHeight = cardH - CARD_PAD
        val labelColor = when {
            faded -> palette.muted
            else -> palette.label
        }
        val label = measureSafe(
            text.measurer,
            node.pathLabel,
            text.labelStyle.copy(color = labelColor),
            textWidth.roundToInt(),
            textHeight.roundToInt(),
        )
        if (label != null) {
            drawText(
                label,
                topLeft = Offset(textLeft, y - label.size.height * HALF),
            )
        }
    }
}

private fun measureSafe(
    measurer: TextMeasurer,
    value: String,
    style: TextStyle,
    width: Int,
    height: Int,
): TextLayoutResult? {
    val maxW = width.coerceAtLeast(0)
    val maxH = height.coerceAtLeast(0)
    if (maxW < MIN_TEXT_PX || maxH < MIN_TEXT_PX) return null
    return measurer.measure(
        text = value,
        style = style,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(minWidth = 0, maxWidth = maxW, minHeight = 0, maxHeight = maxH),
    )
}

private fun maxDepthForScale(scale: Float): Int = when {
    scale < DEPTH_FAR -> 1
    scale < DEPTH_MID -> 2
    scale < DEPTH_NEAR -> 4
    else -> Int.MAX_VALUE
}

private fun sx(worldX: Float, camX: Float, scale: Float, halfW: Float): Float =
    (worldX - camX) * scale + halfW

private fun sy(worldY: Float, camY: Float, scale: Float, halfH: Float): Float =
    (worldY - camY) * scale + halfH

private fun segmentVisible(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
): Boolean {
    if (x1 < left && x2 < left) return false
    if (x1 > right && x2 > right) return false
    if (y1 < top && y2 < top) return false
    if (y1 > bottom && y2 > bottom) return false
    return true
}
