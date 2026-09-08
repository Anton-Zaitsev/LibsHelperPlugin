package com.zaycev.libshelper.ide.ui.compose

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.zaycev.libshelper.core.graph.CameraFrame
import com.zaycev.libshelper.core.graph.GraphMetrics
import com.zaycev.libshelper.core.graph.ModuleMap
import com.zaycev.libshelper.core.graph.PlacedNode
import com.zaycev.libshelper.core.graph.SpatialGrid
import com.zaycev.libshelper.core.graph.cameraFlyDurationMs
import com.zaycev.libshelper.core.graph.cameraNear
import com.zaycev.libshelper.core.graph.fitCamera
import com.zaycev.libshelper.core.graph.focusCamera
import com.zaycev.libshelper.core.graph.hitCardIndex
import com.zaycev.libshelper.core.graph.lerpCamera
import com.zaycev.libshelper.core.graph.panCamera
import com.zaycev.libshelper.core.graph.relatedNodeIds
import com.zaycev.libshelper.core.graph.searchModuleIndices
import com.zaycev.libshelper.core.graph.spatialGridOf
import com.zaycev.libshelper.core.graph.worldFromScreen
import com.zaycev.libshelper.core.graph.zoomCamera
import com.zaycev.libshelper.core.graph.zoomFactorFromScroll
import com.zaycev.libshelper.core.model.ModuleKind
import com.zaycev.libshelper.ide.i18n.msg
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import kotlin.math.hypot
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

@Stable
private class GraphUiCam {
    var scale by mutableFloatStateOf(1f)
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var viewW by mutableFloatStateOf(0f)
    var viewH by mutableFloatStateOf(0f)
    var hoverIndex by mutableIntStateOf(-1)
    var selectedId by mutableStateOf<String?>(null)
    var fitted by mutableStateOf(false)
    var revealAll by mutableStateOf(false)
    var pressX = 0f
    var pressY = 0f
    var dragging = false
    var lastClickMs = 0L
    var lastClickX = 0f
    var lastClickY = 0f
    var flyTarget by mutableStateOf<CameraFrame?>(null)

    fun stopFly() {
        flyTarget = null
    }

    fun requestFly(target: CameraFrame) {
        val from = CameraFrame(x, y, scale)
        if (cameraNear(from, target)) {
            flyTarget = null
            applyFrame(target)
            return
        }
        flyTarget = target
    }
}

@Composable
fun ModuleGraphView(
    map: ModuleMap,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onOpenModule: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchState = rememberTextFieldState()
    val grid = remember(map) { spatialGridOf(map) }
    val scratch = remember { GraphScratch() }
    val camDraw = remember { GraphCam() }
    val ui = remember(map) { GraphUiCam() }
    var matchCursor by remember { mutableIntStateOf(0) }
    val query = searchState.text.toString()
    val found = remember(query, map) { searchModuleIndices(map, query) }
    val glow = remember(ui.selectedId, map) { relatedNodeIds(map, ui.selectedId) }
    val kinds = remember(map) { map.nodes.map { it.kind }.distinct().sortedBy { it.ordinal }.toPersistentList() }
    val matchIds = remember(found, map) {
        if (found.isEmpty()) persistentSetOf() else found.map { map.nodes[it].id }.toPersistentSet()
    }

    fun focusNode(node: PlacedNode) {
        ui.selectedId = node.id
        ui.requestFly(focusCamera(node.x, node.y, ui.scale))
    }

    fun showAllModules() {
        ui.revealAll = true
        ui.fitted = false
        ui.applyFit(map)
    }

    LaunchedEffect(ui.flyTarget) {
        val target = ui.flyTarget ?: return@LaunchedEffect
        val from = CameraFrame(ui.x, ui.y, ui.scale)
        val durationNs = cameraFlyDurationMs(from, target) * NANOS_PER_MS
        val startNs = withFrameNanos { it }
        while (true) {
            val elapsed = withFrameNanos { it } - startNs
            val t = (elapsed.toFloat() / durationNs).coerceIn(0f, 1f)
            ui.applyFrame(lerpCamera(from, target, FastOutSlowInEasing.transform(t)))
            if (t >= 1f) break
        }
        if (ui.flyTarget == target) ui.flyTarget = null
    }

    DisposableEffect(query, map) {
        matchCursor = 0
        if (query.isNotBlank()) {
            found.firstOrNull()?.let { focusNode(map.nodes[it]) }
        }
        onDispose { }
    }

    Column(
        modifier.then(if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GraphToolbar(
            searchState = searchState,
            fullscreen = fullscreen,
            onCycle = {
                if (found.isEmpty()) return@GraphToolbar
                matchCursor = (matchCursor + 1) % found.size
                focusNode(map.nodes[found[matchCursor]])
            },
            onShowAll = { showAllModules() },
            onToggleFullscreen = onToggleFullscreen,
        )
        GraphStatus(query, found, matchCursor, map.nodes.size)
        GraphViewport(
            map = map,
            grid = grid,
            scratch = scratch,
            camDraw = camDraw,
            ui = ui,
            glow = glow,
            matchIds = matchIds,
            fullscreen = fullscreen,
            onOpenModule = onOpenModule,
            onSelectNode = { node -> focusNode(node) },
            modifier = if (fullscreen) Modifier.weight(1f).fillMaxWidth() else Modifier,
        )
        GraphLegend(kinds = kinds, dark = JewelTheme.isDark)
        GraphCaption(map, ui.hoverIndex, ui.selectedId)
    }
}

@Composable
private fun GraphToolbar(
    searchState: TextFieldState,
    fullscreen: Boolean,
    onCycle: () -> Unit,
    onShowAll: () -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            state = searchState,
            modifier = Modifier
                .weight(1f)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || event.key != Key.Enter) return@onPreviewKeyEvent false
                    onCycle()
                    true
                },
            placeholder = { Text(msg("analytics.graph.search")) },
        )
        OutlinedButton(onClick = onShowAll) {
            Text(msg("analytics.graph.fit"))
        }
        OutlinedButton(onClick = onToggleFullscreen) {
            Text(msg(if (fullscreen) "analytics.graph.exitFullscreen" else "analytics.graph.fullscreen"))
        }
    }
}

@Composable
private fun GraphStatus(
    query: String,
    matches: ImmutableList<Int>,
    matchCursor: Int,
    nodeCount: Int,
    modifier: Modifier = Modifier,
) {
    val text = when {
        query.isBlank() -> msg("analytics.graph.nodes", nodeCount)
        matches.isEmpty() -> msg("filter.empty")
        else -> msg("analytics.graph.match", (matchCursor + 1).coerceAtMost(matches.size), matches.size)
    }
    MutedText(text, modifier)
}

@Composable
private fun GraphCaption(
    map: ModuleMap,
    hoverIndex: Int,
    selectedId: String?,
    modifier: Modifier = Modifier,
) {
    val captionIndex = when {
        hoverIndex >= 0 -> hoverIndex
        selectedId != null -> map.indexById[selectedId] ?: -1
        else -> -1
    }
    if (captionIndex >= 0) {
        val node = map.nodes[captionIndex]
        MutedText("${node.pathLabel} · ${kindTitle(node.kind)}", modifier)
    } else {
        MutedText(msg("analytics.graph.hint"), modifier)
    }
}

@Composable
private fun GraphViewport(
    map: ModuleMap,
    grid: SpatialGrid,
    scratch: GraphScratch,
    camDraw: GraphCam,
    ui: GraphUiCam,
    glow: ImmutableSet<String>,
    matchIds: ImmutableSet<String>,
    fullscreen: Boolean,
    onOpenModule: (String) -> Unit,
    onSelectNode: (PlacedNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = rememberGraphPalette()
    val dark = JewelTheme.isDark
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val labelStyle = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp, color = palette.label)
    val glyphStyle = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp, color = Color.White)
    val consumeScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = available
        }
    }
    val shape = RoundedCornerShape(10.dp)
    val highlight = GraphHighlight(ui.hoverIndex, ui.selectedId, glow, matchIds, ui.revealAll)
    val text = GraphText(measurer, labelStyle, glyphStyle)
    val openModule by rememberUpdatedState(onOpenModule)
    val selectNode by rememberUpdatedState(onSelectNode)
    val heightModifier = if (fullscreen) {
        Modifier.fillMaxSize()
    } else {
        Modifier.fillMaxWidth().height(GRAPH_HEIGHT_DP.dp)
    }
    Box(
        modifier
            .then(heightModifier)
            .clip(shape)
            .background(palette.canvas)
            .border(1.dp, ComposePalette.cardBorder(), shape)
            .clipToBounds()
            .nestedScroll(consumeScroll),
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    ui.viewW = size.width.toFloat()
                    ui.viewH = size.height.toFloat()
                    if (!ui.fitted && map.nodes.isNotEmpty() && ui.viewW > 1f && ui.viewH > 1f) {
                        ui.applyFit(map)
                    }
                }
                .pointerInput(map) {
                    awaitPointerEventScope {
                        while (true) {
                            applyGraphPointer(
                                event = awaitPointerEvent(PointerEventPass.Initial),
                                map = map,
                                grid = grid,
                                scratch = scratch,
                                ui = ui,
                                onOpenModule = openModule,
                                onSelectNode = selectNode,
                            )
                        }
                    }
                },
        ) {
            camDraw.scale = ui.scale
            camDraw.x = ui.x
            camDraw.y = ui.y
            drawModuleGraph(map, grid, scratch, text, palette, dark, camDraw, highlight)
        }
        GraphZoomControls(
            onZoomIn = { ui.zoomAroundCenter(GraphMetrics.BUTTON_ZOOM) },
            onZoomOut = { ui.zoomAroundCenter(1f / GraphMetrics.BUTTON_ZOOM) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .zIndex(1f),
        )
    }
}

@Composable
private fun GraphZoomControls(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ZoomChip(label = "+", shape = shape, onClick = onZoomIn)
        ZoomChip(label = "−", shape = shape, onClick = onZoomOut)
    }
}

@Composable
private fun ZoomChip(
    label: String,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(shape)
            .background(ComposePalette.cardFill())
            .border(1.dp, ComposePalette.cardBorder(), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label)
    }
}

@Composable
private fun GraphLegend(
    kinds: ImmutableList<ModuleKind>,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        kinds.forEach { kind ->
            Pill("${graphKindGlyph(kind)} ${kindTitle(kind)}", graphKindFill(kind, dark))
        }
    }
}

@Composable
private fun rememberGraphPalette(): GraphPalette {
    val dark = JewelTheme.isDark
    return GraphPalette(
        canvas = if (dark) Color(0xFF1E2024) else Color(0xFFF3F5F8),
        label = JewelTheme.globalColors.text.normal,
        muted = ComposePalette.muted(),
        hierarchy = if (dark) Color(0x66FFFFFF) else Color(0x55000000),
        projectDep = if (dark) Color(0xAAE07A3D) else Color(0xAAC24E00),
        targetLink = if (dark) Color(0x996B9BF2) else Color(0x993D6BC9),
        selected = if (dark) Color(0xFFF0A050) else Color(0xFFC24E00),
        match = if (dark) Color(0xFFC48BE6) else Color(0xFF7A3EC8),
        dim = if (dark) Color(0x44FFFFFF) else Color(0x33000000),
    )
}

private fun kindTitle(kind: ModuleKind): String = when (kind) {
    ModuleKind.Root -> msg("analytics.graph.kind.root")
    ModuleKind.Gradle -> msg("analytics.graph.kind.gradle")
    ModuleKind.Android -> msg("analytics.graph.kind.android")
    ModuleKind.Jvm -> msg("analytics.graph.kind.jvm")
    ModuleKind.Kmp -> msg("analytics.graph.kind.kmp")
    ModuleKind.Ios -> msg("analytics.graph.kind.ios")
    ModuleKind.Js -> msg("analytics.graph.kind.js")
    ModuleKind.Wasm -> msg("analytics.graph.kind.wasm")
    ModuleKind.Native -> msg("analytics.graph.kind.native")
    ModuleKind.Desktop -> msg("analytics.graph.kind.desktop")
    ModuleKind.Common -> msg("analytics.graph.kind.common")
}

private fun GraphUiCam.applyFrame(frame: CameraFrame) {
    x = frame.x
    y = frame.y
    scale = frame.scale
}

private fun GraphUiCam.applyFit(map: ModuleMap) {
    if (map.nodes.isEmpty() || viewW < 1f || viewH < 1f) return
    stopFly()
    applyFrame(fitCamera(map.minX, map.minY, map.maxX, map.maxY, viewW, viewH))
    fitted = true
}

private fun GraphUiCam.zoomAt(pivot: Offset, factor: Float) {
    if (viewW < 1f || viewH < 1f) return
    stopFly()
    applyFrame(zoomCamera(pivot.x, pivot.y, viewW, viewH, x, y, scale, factor))
}

private fun GraphUiCam.zoomAroundCenter(factor: Float) {
    zoomAt(Offset(viewW * HALF_VIEW, viewH * HALF_VIEW), factor)
}

private fun applyGraphPointer(
    event: PointerEvent,
    map: ModuleMap,
    grid: SpatialGrid,
    scratch: GraphScratch,
    ui: GraphUiCam,
    onOpenModule: (String) -> Unit,
    onSelectNode: (PlacedNode) -> Unit,
) {
    val change = event.changes.firstOrNull() ?: return
    when (event.type) {
        PointerEventType.Press -> {
            ui.pressX = change.position.x
            ui.pressY = change.position.y
            ui.dragging = false
        }
        PointerEventType.Scroll -> {
            ui.stopFly()
            val factor = zoomFactorFromScroll(change.scrollDelta.y)
            ui.applyFrame(
                zoomCamera(
                    change.position.x,
                    change.position.y,
                    ui.viewW,
                    ui.viewH,
                    ui.x,
                    ui.y,
                    ui.scale,
                    factor,
                ),
            )
            change.consume()
        }
        PointerEventType.Move -> {
            if (change.pressed) {
                if (!ui.dragging) {
                    val slop = hypot(change.position.x - ui.pressX, change.position.y - ui.pressY)
                    if (slop > CLICK_SLOP) ui.dragging = true
                }
                if (ui.dragging) {
                    ui.stopFly()
                    val dx = change.position.x - change.previousPosition.x
                    val dy = change.position.y - change.previousPosition.y
                    ui.applyFrame(panCamera(dx, dy, ui.x, ui.y, ui.scale))
                    change.consume()
                }
            } else {
                ui.hoverIndex = hitAt(change.position, map, grid, scratch, ui)
            }
        }
        PointerEventType.Release -> {
            if (ui.dragging) return
            val now = change.uptimeMillis
            val doubleClick = now - ui.lastClickMs <= DOUBLE_CLICK_MS &&
                hypot(change.position.x - ui.lastClickX, change.position.y - ui.lastClickY) <= CLICK_SLOP
            ui.lastClickMs = now
            ui.lastClickX = change.position.x
            ui.lastClickY = change.position.y
            val hit = hitAt(change.position, map, grid, scratch, ui)
            if (doubleClick) {
                if (hit >= 0) onOpenModule(map.nodes[hit].id) else ui.zoomAt(change.position, GraphMetrics.DOUBLE_CLICK_ZOOM)
                change.consume()
                return
            }
            if (hit >= 0) onSelectNode(map.nodes[hit])
        }
        else -> Unit
    }
}

private fun hitAt(
    screen: Offset,
    map: ModuleMap,
    grid: SpatialGrid,
    scratch: GraphScratch,
    ui: GraphUiCam,
): Int {
    val (worldX, worldY) = worldFromScreen(
        screen.x,
        screen.y,
        ui.viewW,
        ui.viewH,
        ui.x,
        ui.y,
        ui.scale,
    )
    return hitCardIndex(map, grid, worldX, worldY, scratch.queryBuf, scratch.querySize)
}

private const val CLICK_SLOP = 12f
private const val DOUBLE_CLICK_MS = 400L
private const val HALF_VIEW = 0.5f
private const val NANOS_PER_MS = 1_000_000f
