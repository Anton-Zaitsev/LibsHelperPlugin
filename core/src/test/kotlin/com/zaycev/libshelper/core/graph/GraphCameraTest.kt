package com.zaycev.libshelper.core.graph

import com.zaycev.libshelper.core.model.ModuleKind
import com.zaycev.libshelper.core.network.parseHttpUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphCameraTest {
    @Test
    fun zoomKeepsWorldPointUnderPivot() {
        val pivotX = 100f
        val pivotY = 50f
        val viewW = 200f
        val viewH = 100f
        val camX = 10f
        val camY = 20f
        val scale = 1f
        val before = worldFromScreen(pivotX, pivotY, viewW, viewH, camX, camY, scale)
        val zoomed = zoomCamera(pivotX, pivotY, viewW, viewH, camX, camY, scale, 2f)
        val after = worldFromScreen(pivotX, pivotY, viewW, viewH, zoomed.x, zoomed.y, zoomed.scale)
        assertEquals(before.first, after.first, absoluteTolerance = TOLERANCE)
        assertEquals(before.second, after.second, absoluteTolerance = TOLERANCE)
        assertEquals(2f, zoomed.scale, absoluteTolerance = TOLERANCE)
    }

    @Test
    fun panMovesCameraByScreenDeltaOverScale() {
        val panned = panCamera(20f, 10f, 0f, 0f, 2f)
        val gain = GraphMetrics.PAN_GAIN / 2f
        assertEquals(-20f * gain, panned.x, absoluteTolerance = TOLERANCE)
        assertEquals(-10f * gain, panned.y, absoluteTolerance = TOLERANCE)
        assertEquals(2f, panned.scale)
    }

    @Test
    fun fitPlacesBoundsInsideTheViewport() {
        val cam = fitCamera(0f, 0f, 100f, 50f, 200f, 100f)
        val topLeft = screenFromWorld(0f, 0f, 200f, 100f, cam.x, cam.y, cam.scale)
        val bottomRight = screenFromWorld(100f, 50f, 200f, 100f, cam.x, cam.y, cam.scale)
        assertTrue(topLeft.first >= 0f && topLeft.second >= 0f)
        assertTrue(bottomRight.first <= 200f && bottomRight.second <= 100f)
        assertEquals(50f, cam.x, absoluteTolerance = TOLERANCE)
        assertEquals(25f, cam.y, absoluteTolerance = TOLERANCE)
    }

    @Test
    fun scrollZoomUsesDeltaMagnitude() {
        val small = zoomFactorFromScroll(1f)
        val large = zoomFactorFromScroll(4f)
        assertTrue(small < 1f)
        assertTrue(large < small)
        val inward = zoomFactorFromScroll(-2f)
        assertTrue(inward > 1f)
    }

    @Test
    fun connectorLeavesParentBottomAndEntersChildTop() {
        val parentX = 0f
        val parentY = 0f
        val childX = 80f
        val childY = GraphMetrics.Y_GAP
        val path = cardConnector(parentX, parentY, childX, childY)
        val halfH = GraphMetrics.CARD_H / 2f
        assertEquals(parentY + halfH, path.first().second, absoluteTolerance = TOLERANCE)
        assertEquals(childY - halfH, path.last().second, absoluteTolerance = TOLERANCE)
        path.forEach { point ->
            assertTrue(point.second >= parentY + halfH - TOLERANCE)
            assertTrue(point.second <= childY - halfH + TOLERANCE)
        }
    }

    @Test
    fun pathLabelShowsParentThenChild() {
        assertEquals("root", modulePathLabel(":", null))
        assertEquals(":feature", modulePathLabel(":feature", ":"))
        assertEquals(":feature → :feature:card", modulePathLabel(":feature:card", ":feature"))
        assertEquals(":shared → :shared#android", modulePathLabel(":shared#android", ":shared"))
    }

    @Test
    fun hitTestUsesCardBounds() {
        val node = PlacedNode(
            id = ":feature:card",
            label = "card",
            kind = ModuleKind.Android,
            x = 0f,
            y = 0f,
            depth = 1,
            parentId = ":feature",
            pathLabel = ":feature → :feature:card",
        )
        val map = ModuleMap(
            nodes = kotlinx.collections.immutable.persistentListOf(node),
            links = kotlinx.collections.immutable.persistentListOf(),
            minX = -GraphMetrics.X_GAP,
            minY = -GraphMetrics.Y_GAP,
            maxX = GraphMetrics.X_GAP,
            maxY = GraphMetrics.Y_GAP,
            indexById = kotlinx.collections.immutable.persistentMapOf(node.id to 0),
        )
        val grid = spatialGridOf(map)
        val buffer = IntArray(8)
        val sizeOut = IntArray(1)
        val inside = hitCardIndex(map, grid, GraphMetrics.CARD_W / 2f - 1f, 0f, buffer, sizeOut)
        val outside = hitCardIndex(map, grid, GraphMetrics.CARD_W / 2f + 1f, 0f, buffer, sizeOut)
        assertEquals(0, inside)
        assertEquals(-1, outside)
    }

    @Test
    fun buttonZoomStaysInsideLimits() {
        val zoomedIn = zoomCamera(50f, 50f, 100f, 100f, 0f, 0f, 1f, GraphMetrics.BUTTON_ZOOM)
        val zoomedOut = zoomCamera(50f, 50f, 100f, 100f, 0f, 0f, 1f, 1f / GraphMetrics.BUTTON_ZOOM)
        assertEquals(GraphMetrics.BUTTON_ZOOM, zoomedIn.scale, absoluteTolerance = TOLERANCE)
        assertTrue(zoomedOut.scale < 1f)
        val doubleClick = zoomCamera(50f, 50f, 100f, 100f, 0f, 0f, 1f, GraphMetrics.DOUBLE_CLICK_ZOOM)
        assertEquals(GraphMetrics.DOUBLE_CLICK_ZOOM, doubleClick.scale, absoluteTolerance = TOLERANCE)
    }

    @Test
    fun focusCameraCentersNodeAndRaisesScale() {
        val far = focusCamera(40f, 80f, 0.2f)
        assertEquals(40f, far.x, absoluteTolerance = TOLERANCE)
        assertEquals(80f, far.y, absoluteTolerance = TOLERANCE)
        assertEquals(GraphMetrics.FOCUS_SCALE, far.scale, absoluteTolerance = TOLERANCE)
        val close = focusCamera(40f, 80f, 2f)
        assertEquals(2f, close.scale, absoluteTolerance = TOLERANCE)
    }

    @Test
    fun lerpCameraHitsTheMidpoint() {
        val from = CameraFrame(0f, 0f, 1f)
        val to = CameraFrame(10f, 4f, 2f)
        val mid = lerpCamera(from, to, 0.5f)
        assertEquals(5f, mid.x, absoluteTolerance = TOLERANCE)
        assertEquals(2f, mid.y, absoluteTolerance = TOLERANCE)
        assertEquals(1.5f, mid.scale, absoluteTolerance = TOLERANCE)
        assertTrue(cameraNear(from, from))
        assertTrue(!cameraNear(from, to))
        val duration = cameraFlyDurationMs(from, to)
        assertTrue(duration in GraphMetrics.FLY_MIN_MS..GraphMetrics.FLY_MAX_MS)
    }

    @Test
    fun parseHttpUriRejectsNonHttp() {
        assertEquals("repo.example", parseHttpUri("https://repo.example/maven")?.host)
        assertNull(parseHttpUri("file:///tmp/secret"))
        assertNull(parseHttpUri("javascript:alert(1)"))
        assertNull(parseHttpUri("not a url"))
    }

    private companion object {
        const val TOLERANCE = 0.0001f
    }
}
