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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.intellij.openapi.project.Project
import com.zaycev.libshelper.core.graph.CameraFrame
import com.zaycev.libshelper.core.graph.GraphMetrics
import com.zaycev.libshelper.core.graph.ModuleMap
import com.zaycev.libshelper.core.graph.PlacedNode
import com.zaycev.libshelper.core.graph.cameraFlyDurationMs
import com.zaycev.libshelper.core.graph.cameraNear
import com.zaycev.libshelper.core.graph.fitCamera
import com.zaycev.libshelper.core.graph.focusCamera
import com.zaycev.libshelper.core.graph.hitCardIndex
import com.zaycev.libshelper.core.graph.lerpCamera
import com.zaycev.libshelper.core.graph.panCamera
import com.zaycev.libshelper.core.graph.searchModuleIndices
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
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import kotlin.math.hypot

@Stable
private class GraphUiCam {
    var worldX = 0f
    var worldY = 0f
    var worldScale = 1f
    var camera by mutableStateOf(CameraFrame(0f, 0f, 1f))
    var viewW = 0f
    var viewH = 0f
    var hoverPlain = -1
    var hoverIndex by mutableIntStateOf(-1)
    var hoverTick by mutableIntStateOf(0)
    var hoverX = Float.NaN
    var hoverY = Float.NaN
    var selectedId by mutableStateOf<String?>(null)
    var fitted = false
    var pressX = 0f
    var pressY = 0f
    var dragging = false
    var flying = false
    var lastClickMs = 0L
    var lastClickX = 0f
    var lastClickY = 0f
    var flyTarget by mutableStateOf<CameraFrame?>(null)
    val hitBuffer = IntArray(HIT_CAP)
    val hitSize = IntArray(1)

    fun stopFly() {
        val wasFlying = flying
        flying = false
        flyTarget = null
        if (wasFlying && hoverX.isFinite()) hoverTick++
    }

    fun requestFly(target: CameraFrame) {
        val from = CameraFrame(worldX, worldY, worldScale)
        if (cameraNear(from, target)) {
            stopFly()
            applyFrame(target)
            return
        }
        hoverPlain = -1
        if (hoverIndex != -1) hoverIndex = -1
        flying = true
        flyTarget = target
    }

    fun beginDrag() {
        dragging = true
        setHover(-1)
        stopFly()
    }

    fun endDrag() {
        dragging = false
    }

    fun applyFrame(frame: CameraFrame) {
        worldX = frame.x
        worldY = frame.y
        worldScale = frame.scale
        camera = frame
        if (!flying && !dragging && hoverX.isFinite()) hoverTick++
    }

    fun setHover(index: Int) {
        hoverPlain = index
        if (index < 0) {
            hoverX = Float.NaN
            hoverY = Float.NaN
        }
        if (index != hoverIndex) hoverIndex = index
    }

    fun pointHover(x: Float, y: Float) {
        hoverX = x
        hoverY = y
        hoverTick++
    }
}

@Composable
fun ModuleGraphView(
    project: Project,
    map: ModuleMap,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onOpenModule: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val dark = JewelTheme.isDark
    val palette = rememberGraphPalette()
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val labelStyle = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp, color = palette.label)
    val glyphStyle = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp, color = Color.White)
    val scene = remember(map, palette, dark) {
        buildGraphScene(map, measurer, palette, dark, labelStyle, glyphStyle)
    }
    val focusCache = remember(scene) { GraphFocusCache() }
    val lodPicker = remember { GraphLodPicker() }
    val frameStats = remember { GraphFrameStats() }
    val ui = remember { GraphUiCam() }
    val searchState = rememberTextFieldState()
    var matchCursor by remember { mutableIntStateOf(0) }
    val query = searchState.text.toString()
    val found = remember(query, scene) { searchModuleIndices(scene.map, query) }
    val kinds = remember(scene) { scene.map.nodes.map { it.kind }.distinct().sortedBy { it.ordinal }.toPersistentList() }
    val matchIds = remember(found, scene) {
        if (found.isEmpty()) persistentSetOf() else found.map { scene.map.nodes[it].id }.toPersistentSet()
    }

    fun focusNode(node: PlacedNode) {
        ui.selectedId = node.id
        ui.requestFly(focusCamera(node.x, node.y, ui.worldScale))
    }

    DisposableEffect(scene) {
        onDispose {
            focusCache.close()
            scene.close()
        }
    }

    LaunchedEffect(scene) {
        ui.fitted = false
        ui.stopFly()
        ui.setHover(-1)
        if (ui.viewW > 1f && ui.viewH > 1f) ui.applyFit(scene.map)
    }

    LaunchedEffect(ui.flyTarget) {
        val target = ui.flyTarget ?: return@LaunchedEffect
        val from = CameraFrame(ui.worldX, ui.worldY, ui.worldScale)
        val durationNs = cameraFlyDurationMs(from, target) * NANOS_PER_MS
        val startNs = withFrameNanos { it }
        while (ui.flyTarget === target) {
            val elapsed = withFrameNanos { it } - startNs
            val t = (elapsed.toFloat() / durationNs).coerceIn(0f, 1f)
            ui.applyFrame(lerpCamera(from, target, FastOutSlowInEasing.transform(t)))
            if (t >= 1f) break
        }
        if (ui.flyTarget === target) ui.stopFly()
    }

    LaunchedEffect(scene) {
        snapshotFlow { ui.hoverTick }.collect {
            withFrameNanos { }
            if (ui.flying || ui.dragging) return@collect
            val screenX = ui.hoverX
            val screenY = ui.hoverY
            if (!screenX.isFinite() || !screenY.isFinite() || ui.viewW < 1f) return@collect
            val hit = hitCard(scene, ui, screenX, screenY)
            if (hit != ui.hoverPlain) ui.setHover(hit)
        }
    }

    DisposableEffect(query, scene) {
        matchCursor = 0
        if (query.isNotBlank()) found.firstOrNull()?.let { focusNode(scene.map.nodes[it]) }
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
                focusNode(scene.map.nodes[found[matchCursor]])
            },
            onShowAll = {
                ui.fitted = false
                ui.applyFit(scene.map)
            },
            onToggleFullscreen = onToggleFullscreen,
            onExport = { scope.launch { exportModuleGraphPng(project, scene) } },
        )
        GraphStatus(query, found, matchCursor, scene.map.nodes.size)
        GraphViewport(
            scene = scene,
            ui = ui,
            lodPicker = lodPicker,
            frameStats = frameStats,
            focusCache = focusCache,
            matchIds = matchIds,
            fullscreen = fullscreen,
            onOpenModule = onOpenModule,
            onSelectNode = { node -> focusNode(node) },
            modifier = if (fullscreen) Modifier.weight(1f).fillMaxWidth() else Modifier,
        )
        GraphLegend(kinds = kinds, dark = dark)
        GraphCaption(scene.map, ui)
    }
}

@Composable
private fun GraphToolbar(
    searchState: TextFieldState,
    fullscreen: Boolean,
    onCycle: () -> Unit,
    onShowAll: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onExport: () -> Unit,
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
        OutlinedButton(onClick = onExport) {
            Text(msg("analytics.graph.export"))
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
    ui: GraphUiCam,
    modifier: Modifier = Modifier,
) {
    val captionIndex = when {
        ui.hoverIndex >= 0 -> ui.hoverIndex
        ui.selectedId != null -> map.indexById[ui.selectedId] ?: -1
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
    scene: GraphScene,
    ui: GraphUiCam,
    lodPicker: GraphLodPicker,
    frameStats: GraphFrameStats,
    focusCache: GraphFocusCache,
    matchIds: ImmutableSet<String>,
    fullscreen: Boolean,
    onOpenModule: (String) -> Unit,
    onSelectNode: (PlacedNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val consumeScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = available
        }
    }
    val shape = RoundedCornerShape(10.dp)
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
            .background(scene.palette.canvas)
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
                    if (!ui.fitted && scene.map.nodes.isNotEmpty() && ui.viewW > 1f && ui.viewH > 1f) {
                        ui.applyFit(scene.map)
                    }
                }
                .pointerInput(scene) {
                    awaitPointerEventScope {
                        while (true) {
                            applyGraphPointer(
                                event = awaitPointerEvent(PointerEventPass.Initial),
                                scene = scene,
                                ui = ui,
                                onOpenModule = openModule,
                                onSelectNode = selectNode,
                            )
                        }
                    }
                },
        ) {
            val camera = ui.camera
            val started = System.nanoTime()
            val lod = lodPicker.pick(camera.scale)
            val focus = focusCache.use(scene, ui.selectedId)
            drawGraphFrame(
                scene = scene,
                camera = camera,
                lod = lod,
                hoverIndex = ui.hoverIndex,
                selectedId = ui.selectedId,
                matchIds = matchIds,
                focus = focus,
                palette = scene.palette,
            )
            frameStats.record((System.nanoTime() - started) / NANOS_PER_MS, ui.dragging || ui.flying)
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

private fun GraphUiCam.applyFit(map: ModuleMap) {
    if (map.nodes.isEmpty() || viewW < 1f || viewH < 1f) return
    stopFly()
    applyFrame(fitCamera(map.minX, map.minY, map.maxX, map.maxY, viewW, viewH))
    fitted = true
}

private fun GraphUiCam.zoomAt(pivot: Offset, factor: Float) {
    if (viewW < 1f || viewH < 1f) return
    stopFly()
    applyFrame(zoomCamera(pivot.x, pivot.y, viewW, viewH, worldX, worldY, worldScale, factor))
}

private fun GraphUiCam.zoomAroundCenter(factor: Float) {
    zoomAt(Offset(viewW * HALF_VIEW, viewH * HALF_VIEW), factor)
}

private fun applyGraphPointer(
    event: PointerEvent,
    scene: GraphScene,
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
                    ui.worldX,
                    ui.worldY,
                    ui.worldScale,
                    factor,
                ),
            )
            change.consume()
        }
        PointerEventType.Move -> {
            if (change.pressed) {
                if (!ui.dragging) {
                    val slop = hypot(change.position.x - ui.pressX, change.position.y - ui.pressY)
                    if (slop > CLICK_SLOP) ui.beginDrag()
                }
                if (ui.dragging) {
                    val dx = change.position.x - change.previousPosition.x
                    val dy = change.position.y - change.previousPosition.y
                    ui.applyFrame(panCamera(dx, dy, ui.worldX, ui.worldY, ui.worldScale))
                    change.consume()
                }
            } else if (!ui.flying) {
                ui.pointHover(change.position.x, change.position.y)
            }
        }
        PointerEventType.Release -> {
            if (ui.dragging) {
                ui.endDrag()
                return
            }
            val now = change.uptimeMillis
            val doubleClick = now - ui.lastClickMs <= DOUBLE_CLICK_MS &&
                hypot(change.position.x - ui.lastClickX, change.position.y - ui.lastClickY) <= CLICK_SLOP
            ui.lastClickMs = now
            ui.lastClickX = change.position.x
            ui.lastClickY = change.position.y
            val hit = hitCard(scene, ui, change.position.x, change.position.y)
            if (doubleClick) {
                if (hit >= 0) onOpenModule(scene.map.nodes[hit].id) else ui.zoomAt(change.position, GraphMetrics.DOUBLE_CLICK_ZOOM)
                change.consume()
                return
            }
            if (hit >= 0) onSelectNode(scene.map.nodes[hit])
        }
        PointerEventType.Exit -> ui.setHover(-1)
        else -> Unit
    }
}

private fun hitCard(scene: GraphScene, ui: GraphUiCam, screenX: Float, screenY: Float): Int {
    if (ui.viewW < 1f || ui.viewH < 1f) return -1
    val world = worldFromScreen(screenX, screenY, ui.viewW, ui.viewH, ui.worldX, ui.worldY, ui.worldScale)
    return hitCardIndex(scene.map, scene.grid, world.x, world.y, ui.hitBuffer, ui.hitSize, scene.widths)
}

private const val GRAPH_HEIGHT_DP = 480
private const val CLICK_SLOP = 12f
private const val DOUBLE_CLICK_MS = 400L
private const val HALF_VIEW = 0.5f
private const val NANOS_PER_MS = 1_000_000f
private const val HIT_CAP = 2048
