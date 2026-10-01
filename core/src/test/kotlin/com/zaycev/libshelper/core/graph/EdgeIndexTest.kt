package com.zaycev.libshelper.core.graph

import com.zaycev.libshelper.core.model.ModuleKind
import com.zaycev.libshelper.core.model.ModuleLinkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

class EdgeIndexTest {
    @Test
    fun lineThroughTheViewportIsKeptWhenBothEndsAreOutside() {
        val map = mapOf(
            node("a", 0f, -400f),
            node("b", 0f, 400f),
            link(0, 1),
        )
        val index = edgeIndexOf(map)
        assertTrue(index.intersects(0, -20f, -20f, 20f, 20f))
    }

    @Test
    fun sidewaysConnectorUnderTheCardsIntersectsAViewportBelow() {
        val map = mapOf(
            node("a", 0f, 0f),
            node("b", 400f, 10f),
            link(0, 1),
        )
        val index = edgeIndexOf(map)
        assertTrue(index.intersects(0, 100f, 40f, 300f, 120f))
    }

    @Test
    fun edgeQueryReturnsOnlyEdgesTouchingTheViewport() {
        val nodes = persistentListOf(
            node("a", 0f, 0f),
            node("b", 0f, 200f),
            node("c", 8_000f, 8_000f),
            node("d", 8_000f, 8_200f),
        )
        val map = ModuleMap(
            nodes = nodes,
            links = persistentListOf(
                PlacedLink(0, 1, ModuleLinkKind.Hierarchy),
                PlacedLink(2, 3, ModuleLinkKind.Hierarchy),
            ),
            minX = -GraphMetrics.X_GAP,
            minY = -GraphMetrics.Y_GAP,
            maxX = 9_000f,
            maxY = 9_000f,
            indexById = persistentMapOf("a" to 0, "b" to 1, "c" to 2, "d" to 3),
        )
        val index = edgeIndexOf(map)
        val buffer = IntArray(8)
        val sizeOut = IntArray(1)
        index.query(-100f, -100f, 300f, 400f, buffer, sizeOut)
        assertTrue((0 until sizeOut[0]).any { buffer[it] == 0 })
        assertTrue((0 until sizeOut[0]).none { buffer[it] == 1 })
    }

    @Test
    fun edgeQueryKeepsALongEdgeThatCrossesTheViewport() {
        val map = mapOf(
            node("a", 0f, -2_000f),
            node("b", 0f, 2_000f),
            link(0, 1),
        )
        val index = edgeIndexOf(map)
        val buffer = IntArray(4)
        val sizeOut = IntArray(1)
        index.query(-50f, -50f, 50f, 50f, buffer, sizeOut)
        assertTrue((0 until sizeOut[0]).any { buffer[it] == 0 })
    }

    @Test
    fun exportFrameShrinksToThePixelBudget() {
        val frame = pngFrame(worldW = 20_000f, worldH = 20_000f, requestedScale = 2f, maxSide = 4096, maxPixels = 1_000_000)
        assertTrue(frame.width <= 4096)
        assertTrue(frame.height <= 4096)
        assertTrue(frame.width.toLong() * frame.height.toLong() <= 1_000_000)
        assertEquals(frame.width.toFloat() / 20_000f, frame.height.toFloat() / 20_000f, absoluteTolerance = 0.05f)
    }

    @Test
    fun exportFrameUsesVectorLikeDetailWhenTheMapFits() {
        val frame = pngFrame(worldW = 900f, worldH = 500f)
        assertEquals(EXPORT_PIXELS_PER_WORLD, frame.pixelScale, absoluteTolerance = 0.05f)
        assertTrue(frame.width >= 14_000)
        assertTrue(frame.height >= 7_500)
    }

    @Test
    fun exportFrameKeepsDetailWhenTheMapFits() {
        val frame = pngFrame(worldW = 800f, worldH = 400f, requestedScale = 4f, maxSide = 8_000, maxPixels = 20_000_000)
        assertEquals(4f, frame.pixelScale, absoluteTolerance = 0.05f)
        assertTrue(frame.width in 3_000..3_400)
        assertTrue(frame.height in 1_500..1_700)
    }

    private fun node(id: String, x: Float, y: Float) = PlacedNode(id, id, ModuleKind.Jvm, x, y, 0, null, id)

    private fun mapOf(left: PlacedNode, right: PlacedNode, link: PlacedLink) = ModuleMap(
        nodes = persistentListOf(left, right),
        links = persistentListOf(link),
        minX = minOf(left.x, right.x),
        minY = minOf(left.y, right.y),
        maxX = maxOf(left.x, right.x),
        maxY = maxOf(left.y, right.y),
        indexById = persistentMapOf(left.id to 0, right.id to 1),
    )

    private fun link(from: Int, to: Int) = PlacedLink(from, to, ModuleLinkKind.Hierarchy)
}
