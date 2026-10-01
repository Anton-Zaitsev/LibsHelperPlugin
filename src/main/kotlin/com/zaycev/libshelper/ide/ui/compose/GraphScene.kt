package com.zaycev.libshelper.ide.ui.compose

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.zaycev.libshelper.core.graph.CameraFrame
import com.zaycev.libshelper.core.graph.GraphMetrics
import com.zaycev.libshelper.core.graph.GraphScheme
import com.zaycev.libshelper.core.graph.GraphStroke
import com.zaycev.libshelper.core.graph.ModuleMap
import com.zaycev.libshelper.core.graph.ModuleRoutes
import com.zaycev.libshelper.core.graph.SpatialGrid
import com.zaycev.libshelper.core.graph.cardWidthForLabel
import com.zaycev.libshelper.core.graph.moduleRoutes
import com.zaycev.libshelper.core.graph.relatedNodeIds
import com.zaycev.libshelper.core.graph.screenFromWorld
import com.zaycev.libshelper.core.graph.spatialGridOf
import com.zaycev.libshelper.core.graph.spreadCards
import com.zaycev.libshelper.core.model.ModuleKind
import com.zaycev.libshelper.core.model.ModuleLinkKind
import com.zaycev.libshelper.ide.diagnostics.LibsHelperDiagnostics
import org.jetbrains.skia.Picture
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.RTreeFactory
import org.jetbrains.skia.Rect as SkRect

internal enum class GraphLod {
    Far,
    Mid,
    Near,
}

internal class GraphScene(
    val map: ModuleMap,
    val widths: FloatArray,
    val palette: GraphPalette,
    val grid: SpatialGrid,
    private val routes: ModuleRoutes,
    private val labels: Array<TextLayoutResult?>,
    private val glyphs: Array<TextLayoutResult?>,
    private val dark: Boolean,
) {
    val far: Picture? = pictureFor(GraphLod.Far)
    val mid: Picture? = pictureFor(GraphLod.Mid)
    val near: Picture? = pictureFor(GraphLod.Near)

    fun recordFocus(selectedId: String): Picture? {
        val ids = relatedNodeIds(map, selectedId)
        if (ids.isEmpty() || map.nodes.isEmpty()) return null
        return record(GraphLod.Near, nodeIds = ids)
    }

    fun close() {
        far?.close()
        mid?.close()
        near?.close()
    }

    private fun pictureFor(lod: GraphLod): Picture? {
        if (map.nodes.isEmpty()) return null
        return record(lod, nodeIds = null)
    }

    private fun record(lod: GraphLod, nodeIds: Set<String>?): Picture {
        val cull = cullRect(map)
        val recorder = PictureRecorder()
        try {
            val canvas = recorder.beginRecording(cull.toSkia(), RTreeFactory())
            CanvasDrawScope().draw(
                density = SCENE_DENSITY,
                layoutDirection = LayoutDirection.Ltr,
                canvas = canvas.asComposeCanvas(),
                size = Size(cull.width, cull.height),
            ) {
                drawLod(lod, nodeIds, cull)
            }
            return recorder.finishRecordingAsPicture()
        } finally {
            recorder.close()
        }
    }

    private fun DrawScope.drawLod(lod: GraphLod, nodeIds: Set<String>?, cull: Rect) {
        if (lod == GraphLod.Far) {
            drawFarLinks(nodeIds)
            drawFarNodes(nodeIds)
            return
        }
        if (lod == GraphLod.Near) drawDependencies(nodeIds, cull)
        drawBuses(lod, nodeIds, cull)
        drawCards(lod, nodeIds)
    }

    private fun DrawScope.drawFarLinks(nodeIds: Set<String>?) {
        for (link in map.links) {
            if (link.kind == ModuleLinkKind.ProjectDep) continue
            val from = map.nodes[link.fromIndex]
            val to = map.nodes[link.toIndex]
            if (nodeIds != null && (from.id !in nodeIds || to.id !in nodeIds)) continue
            val color = if (link.kind == ModuleLinkKind.Target) palette.targetLink else palette.hierarchy
            drawLine(color, Offset(from.x, from.y), Offset(to.x, to.y), strokeWidth = STROKE_FAR, cap = StrokeCap.Round)
        }
    }

    private fun DrawScope.drawFarNodes(nodeIds: Set<String>?) {
        map.nodes.forEachIndexed { index, node ->
            if (nodeIds != null && node.id !in nodeIds) return@forEachIndexed
            drawCircle(graphKindFill(node.kind, dark), DOT_RADIUS, Offset(node.x, node.y))
        }
    }

    private fun DrawScope.drawBuses(lod: GraphLod, nodeIds: Set<String>?, cull: Rect) {
        val width = if (lod == GraphLod.Near) STROKE_NEAR else STROKE_MID
        drawStrokeKind(ModuleLinkKind.Hierarchy, palette.hierarchy, width, dashed = false, cull, nodeIds)
        drawStrokeKind(ModuleLinkKind.Target, palette.targetLink, width, dashed = false, cull, nodeIds)
    }

    private fun DrawScope.drawDependencies(nodeIds: Set<String>?, cull: Rect) {
        drawStrokeKind(ModuleLinkKind.ProjectDep, palette.projectDep, STROKE_NEAR, dashed = true, cull, nodeIds)
    }

    private fun DrawScope.drawStrokeKind(
        kind: ModuleLinkKind,
        color: Color,
        width: Float,
        dashed: Boolean,
        cull: Rect,
        nodeIds: Set<String>?,
    ) {
        var any = false
        for (stroke in routes.strokes) {
            if (stroke.kind != kind || stroke.dashed != dashed || !keeps(stroke, nodeIds)) continue
            any = true
            break
        }
        if (!any) return
        val layer = Paint().apply { alpha = color.alpha }
        drawContext.canvas.saveLayer(cull, layer)
        val opaque = color.copy(alpha = 1f)
        val style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (stroke in routes.strokes) {
            if (stroke.kind != kind || stroke.dashed != dashed || !keeps(stroke, nodeIds)) continue
            drawPath(pathOf(stroke.xy, dashed), opaque, style = style)
        }
        drawContext.canvas.restore()
    }

    private fun keeps(stroke: GraphStroke, nodeIds: Set<String>?): Boolean {
        if (nodeIds == null) return true
        val from = map.nodes.getOrNull(stroke.fromIndex)?.id ?: return false
        val to = map.nodes.getOrNull(stroke.toIndex)?.id ?: return false
        return from in nodeIds && to in nodeIds
    }

    private fun DrawScope.drawCards(lod: GraphLod, nodeIds: Set<String>?) {
        val showText = lod == GraphLod.Near
        val border = if (showText) BORDER_NEAR else BORDER_MID
        val face = if (dark) CardFaceDark else CardFaceLight
        val edge = if (dark) CardBorderDark else CardBorderLight
        val shadow = if (dark) ShadowDark else ShadowLight
        map.nodes.forEachIndexed { index, node ->
            if (nodeIds != null && node.id !in nodeIds) return@forEachIndexed
            val width = widths.getOrElse(index) { GraphMetrics.CARD_W }
            val left = node.x - width * HALF
            val top = node.y - GraphMetrics.CARD_H * HALF
            val size = Size(width, GraphMetrics.CARD_H)
            val radius = CornerRadius(CARD_CORNER, CARD_CORNER)
            drawRoundRect(shadow, Offset(left + SHADOW_OFFSET, top + SHADOW_OFFSET), size, radius)
            drawRoundRect(face, Offset(left, top), size, radius)
            drawRoundRect(edge, Offset(left, top), size, radius, style = Stroke(width = border))
            val fill = graphKindFill(node.kind, dark)
            drawRoundRect(fill, Offset(left, top), Size(CARD_STRIPE, GraphMetrics.CARD_H), CornerRadius(CARD_CORNER * HALF, CARD_CORNER * HALF))
            val badgeCx = left + CARD_STRIPE + CARD_PAD + BADGE * HALF
            drawCircle(fill, BADGE * HALF, Offset(badgeCx, node.y))
            glyphs.getOrNull(node.kind.ordinal)?.let { glyph ->
                drawText(
                    glyph,
                    topLeft = Offset(badgeCx - glyph.size.width * HALF, node.y - glyph.size.height * HALF),
                )
            }
            if (!showText) return@forEachIndexed
            val label = labels.getOrNull(index) ?: return@forEachIndexed
            val textLeft = badgeCx + BADGE * HALF + CARD_PAD
            drawText(label, topLeft = Offset(textLeft, node.y - label.size.height * HALF))
        }
    }
}

internal class GraphLodPicker {
    private var lod = GraphLod.Mid
    private var primed = false

    fun pick(scale: Float): GraphLod {
        val safe = scale.coerceIn(GraphMetrics.MIN_SCALE, GraphMetrics.MAX_SCALE)
        lod = if (!primed) {
            primed = true
            when {
                safe <= FAR_ENTER -> GraphLod.Far
                safe >= NEAR_ENTER -> GraphLod.Near
                else -> GraphLod.Mid
            }
        } else {
            nextLod(lod, safe)
        }
        return lod
    }
}

internal class GraphFocusCache {
    private var selectedId: String? = null
    private var picture: Picture? = null

    fun use(scene: GraphScene, id: String?): Picture? {
        if (id == selectedId) return picture
        picture?.close()
        picture = null
        selectedId = id
        picture = id?.let { scene.recordFocus(it) }
        return picture
    }

    fun close() {
        picture?.close()
        picture = null
        selectedId = null
    }
}

internal class GraphFrameStats {
    private val samples = FloatArray(FRAME_WINDOW)
    private var cursor = 0
    private var filled = 0
    private var seen = 0

    private var moving = false

    fun record(ms: Float, movingNow: Boolean) {
        if (movingNow && ms.isFinite() && ms >= 0f) {
            samples[cursor] = ms
            cursor = (cursor + 1) % FRAME_WINDOW
            if (filled < FRAME_WINDOW) filled++
            seen++
        }
        val stopped = moving && !movingNow
        moving = movingNow
        val windowFull = movingNow && filled == FRAME_WINDOW && seen % FRAME_WINDOW == 0
        if ((stopped || windowFull) && filled >= MIN_FRAME_SAMPLES) publish()
    }

    private fun publish() {
        val ordered = samples.copyOf(filled)
        ordered.sort()
        var sum = 0f
        for (sample in ordered) sum += sample
        val mean = sum / ordered.size
        val index = ((ordered.size - 1) * P99_PARTS) / P99_BASE
        val detail = "mean=${formatMs(mean)}ms p99=${formatMs(ordered[index])}ms n=$filled"
        LibsHelperDiagnostics.record("graph", "frame", detail)
        LibsHelperDiagnostics.log("graph", detail)
        filled = 0
        cursor = 0
        seen = 0
    }
}

internal fun buildGraphScene(
    map: ModuleMap,
    measurer: TextMeasurer,
    palette: GraphPalette,
    dark: Boolean,
    labelStyle: TextStyle,
    glyphStyle: TextStyle,
): GraphScene {
    if (map.nodes.isEmpty()) {
        return GraphScene(map, FloatArray(0), palette, spatialGridOf(map), moduleRoutes(map), emptyArray(), emptyArray(), dark)
    }
    val measured = measureLabels(measurer, map, labelStyle)
    val placed = spreadCards(map, measured.widths)
    return GraphScene(
        map = placed,
        widths = measured.widths,
        palette = palette,
        grid = spatialGridOf(placed),
        routes = moduleRoutes(placed, measured.widths),
        labels = measured.labels,
        glyphs = measureGlyphs(measurer, glyphStyle),
        dark = dark,
    )
}

internal fun DrawScope.playGraphPicture(picture: Picture?, camera: CameraFrame) {
    if (picture == null) return
    val scale = camera.scale.coerceAtLeast(GraphMetrics.MIN_SCALE)
    val skia = drawContext.canvas.toSkia()
    skia.save()
    skia.translate(size.width * HALF, size.height * HALF)
    skia.scale(scale, scale)
    skia.translate(-camera.x, -camera.y)
    picture.playback(skia) { false }
    skia.restore()
}

private fun androidx.compose.ui.graphics.Canvas.toSkia(): org.jetbrains.skia.Canvas = skiaCanvasOf(this)

private val skiaCanvasOf: (androidx.compose.ui.graphics.Canvas) -> org.jetbrains.skia.Canvas = run {
    val composeCanvas = androidx.compose.ui.graphics.Canvas::class.java
    val backed = Class.forName("androidx.compose.ui.graphics.SkiaBackedCanvas")
    val legacy = backed.methods.firstOrNull { it.name == "getSkia" && it.parameterCount == 0 }
    if (legacy != null) {
        return@run { canvas -> legacy.invoke(canvas) as org.jetbrains.skia.Canvas }
    }
    val current = Class.forName("androidx.compose.ui.graphics.SkiaBackedCanvas_skikoKt")
        .getMethod("getSkiaCanvas", composeCanvas)
    return@run { canvas -> current.invoke(null, canvas) as org.jetbrains.skia.Canvas }
}

internal fun DrawScope.drawGraphFrame(
    scene: GraphScene,
    camera: CameraFrame,
    lod: GraphLod,
    hoverIndex: Int,
    selectedId: String?,
    matchIds: Set<String>,
    focus: Picture?,
    palette: GraphPalette,
) {
    val picture = when (lod) {
        GraphLod.Far -> scene.far
        GraphLod.Mid -> scene.mid
        GraphLod.Near -> scene.near
    }
    playGraphPicture(picture, camera)
    if (selectedId != null) {
        drawRect(palette.canvas.copy(alpha = DIM_ALPHA))
        playGraphPicture(focus, camera)
    }
    for (id in matchIds) {
        val index = scene.map.indexById[id] ?: continue
        drawCardRing(scene, index, palette.match, camera)
    }
    if (hoverIndex in scene.map.nodes.indices && scene.map.nodes[hoverIndex].id != selectedId) {
        drawCardRing(scene, hoverIndex, palette.label, camera)
    }
    val selected = selectedId?.let { scene.map.indexById[it] } ?: -1
    if (selected >= 0) drawCardRing(scene, selected, palette.selected, camera)
}

private fun DrawScope.drawCardRing(scene: GraphScene, index: Int, color: Color, camera: CameraFrame) {
    val node = scene.map.nodes[index]
    val scale = camera.scale.coerceAtLeast(GraphMetrics.MIN_SCALE)
    val screen = screenFromWorld(node.x, node.y, size.width, size.height, camera.x, camera.y, scale)
    val width = scene.widths.getOrElse(index) { GraphMetrics.CARD_W } * scale
    val height = GraphMetrics.CARD_H * scale
    val corner = CARD_CORNER * scale
    drawRoundRect(
        color = color,
        topLeft = Offset(screen.x - width * HALF, screen.y - height * HALF),
        size = Size(width, height),
        cornerRadius = CornerRadius(corner, corner),
        style = Stroke(width = RING_STROKE),
    )
}

private fun measureLabels(measurer: TextMeasurer, map: ModuleMap, style: TextStyle): MeasuredLabels {
    val widths = FloatArray(map.nodes.size)
    val labels = arrayOfNulls<TextLayoutResult>(map.nodes.size)
    map.nodes.forEachIndexed { index, node ->
        if (node.pathLabel.isEmpty()) {
            widths[index] = GraphMetrics.CARD_W
            return@forEachIndexed
        }
        val layout = measurer.measure(
            text = node.pathLabel,
            style = style,
            overflow = TextOverflow.Visible,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(maxWidth = Constraints.Infinity),
            density = SCENE_DENSITY,
        )
        labels[index] = layout
        widths[index] = cardWidthForLabel(layout.size.width.toFloat(), LABEL_CHROME)
    }
    return MeasuredLabels(widths, labels)
}

private fun measureGlyphs(measurer: TextMeasurer, style: TextStyle): Array<TextLayoutResult?> {
    return Array(ModuleKind.entries.size) { ordinal ->
        measurer.measure(
            text = graphKindGlyph(ModuleKind.entries[ordinal]),
            style = style,
            overflow = TextOverflow.Visible,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(maxWidth = Constraints.Infinity),
            density = SCENE_DENSITY,
        )
    }
}

private fun pathOf(xy: FloatArray, dashed: Boolean): Path {
    val path = Path()
    if (dashed) {
        var index = 0
        while (index + 3 < xy.size) {
            path.moveTo(xy[index], xy[index + 1])
            path.lineTo(xy[index + 2], xy[index + 3])
            index += 4
        }
        return path
    }
    if (xy.size < 4) return path
    path.moveTo(xy[0], xy[1])
    var index = 2
    while (index + 1 < xy.size) {
        path.lineTo(xy[index], xy[index + 1])
        index += 2
    }
    return path
}

private fun cullRect(map: ModuleMap): Rect = Rect(
    left = map.minX - CULL_PAD,
    top = map.minY - CULL_PAD,
    right = map.maxX + CULL_PAD,
    bottom = map.maxY + CULL_PAD,
)

private fun Rect.toSkia(): SkRect = SkRect.makeLTRB(left, top, right, bottom)

private fun nextLod(lod: GraphLod, scale: Float): GraphLod = when (lod) {
    GraphLod.Far -> when {
        scale >= NEAR_ENTER -> GraphLod.Near
        scale > FAR_EXIT -> GraphLod.Mid
        else -> GraphLod.Far
    }
    GraphLod.Near -> when {
        scale <= FAR_ENTER -> GraphLod.Far
        scale < NEAR_EXIT -> GraphLod.Mid
        else -> GraphLod.Near
    }
    GraphLod.Mid -> when {
        scale <= FAR_ENTER -> GraphLod.Far
        scale >= NEAR_ENTER -> GraphLod.Near
        else -> GraphLod.Mid
    }
}

private fun formatMs(value: Float): String {
    val scaled = (value * 10f).toInt().coerceAtLeast(0)
    return "${scaled / 10}.${scaled % 10}"
}

private class MeasuredLabels(
    val widths: FloatArray,
    val labels: Array<TextLayoutResult?>,
)

private const val HALF = 0.5f
private const val CARD_CORNER = GraphScheme.CARD_CORNER
private const val CARD_STRIPE = GraphScheme.CARD_STRIPE
private const val CARD_PAD = GraphScheme.CARD_PAD
private const val BADGE = GraphScheme.BADGE
private const val LABEL_SLACK = GraphScheme.LABEL_SLACK
private const val LABEL_CHROME = CARD_STRIPE + CARD_PAD + BADGE + CARD_PAD + CARD_PAD + LABEL_SLACK
private const val SHADOW_OFFSET = GraphScheme.SHADOW_OFFSET
private const val DOT_RADIUS = GraphScheme.DOT_RADIUS
private const val STROKE_FAR = GraphScheme.STROKE_FAR
private const val STROKE_MID = GraphScheme.STROKE_MID
private const val STROKE_NEAR = GraphScheme.STROKE_NEAR
private const val BORDER_MID = GraphScheme.BORDER_MID
private const val BORDER_NEAR = GraphScheme.BORDER_NEAR
private const val RING_STROKE = GraphScheme.RING_STROKE
private const val DIM_ALPHA = GraphScheme.DIM_ALPHA
private const val CULL_PAD = GraphScheme.CULL_PAD
private const val FAR_ENTER = GraphScheme.FAR_ENTER
private const val FAR_EXIT = GraphScheme.FAR_EXIT
private const val NEAR_ENTER = GraphScheme.NEAR_ENTER
private const val NEAR_EXIT = GraphScheme.NEAR_EXIT
private const val FRAME_WINDOW = 120
private const val MIN_FRAME_SAMPLES = 8
private const val P99_PARTS = 99
private const val P99_BASE = 100
private val SCENE_DENSITY = Density(1f)
private val CardFaceDark = Color(0xFF2C3038)
private val CardFaceLight = Color(0xFFF7F8FA)
private val CardBorderDark = Color(0xFF4A5160)
private val CardBorderLight = Color(0xFFD0D5DE)
private val ShadowDark = Color(0x66000000)
private val ShadowLight = Color(0x33000000)
