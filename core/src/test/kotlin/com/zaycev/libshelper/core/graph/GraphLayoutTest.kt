package com.zaycev.libshelper.core.graph

import com.zaycev.libshelper.core.model.ModuleKind
import com.zaycev.libshelper.core.model.ModuleLink
import com.zaycev.libshelper.core.model.ModuleLinkKind
import com.zaycev.libshelper.core.model.ModuleNode
import com.zaycev.libshelper.core.model.ModuleGraph
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphLayoutTest {
    @Test
    fun sixteenLeavesPackIntoAGrid() {
        val nodes = ArrayList<ModuleNode>(LEAF_COUNT + 1)
        nodes += ModuleNode(":", "root", ModuleKind.Root, null)
        repeat(LEAF_COUNT) { index ->
            nodes += ModuleNode(":m$index", "m$index", ModuleKind.Jvm, ":")
        }
        val links = (1..LEAF_COUNT).map { index ->
            ModuleLink(":", ":m$index", ModuleLinkKind.Hierarchy)
        }
        val map = layoutModuleMap(ModuleGraph(nodes.toPersistentList(), links.toPersistentList()))
        val leaves = map.nodes.filter { it.parentId == ":" }
        assertEquals(LEAF_COUNT, leaves.size)
        assertTrue(leaves.map { it.y }.distinct().size > 1)
        val span = leaves.maxOf { it.x } - leaves.minOf { it.x }
        assertTrue(span < GraphMetrics.X_GAP * 8f)
        val root = map.nodes.first { it.id == ":" }
        assertTrue(leaves.all { it.y > root.y })
    }

    @Test
    fun hierarchyBusMeetsBothCardEdges() {
        val graph = com.zaycev.libshelper.core.inventory.buildModuleGraph(
            listOf(":", ":app", ":feature:home"),
            emptyMap(),
        )
        val map = layoutModuleMap(graph)
        val routes = moduleRoutes(map)
        val halfH = GraphMetrics.CARD_H * 0.5f
        map.links.forEachIndexed { index, link ->
            if (link.kind == ModuleLinkKind.ProjectDep) return@forEachIndexed
            val xy = routes.linkXy[index]
            val from = map.nodes[link.fromIndex]
            val to = map.nodes[link.toIndex]
            assertTrue(xy.size >= 4)
            assertEquals(from.x, xy[0], absoluteTolerance = TOLERANCE)
            assertEquals(from.y + halfH, xy[1], absoluteTolerance = TOLERANCE)
            assertEquals(to.x, xy[xy.size - 2], absoluteTolerance = TOLERANCE)
            assertEquals(to.y - halfH, xy[xy.size - 1], absoluteTolerance = TOLERANCE)
            val reachesChild = routes.strokes.any { stroke ->
                !stroke.dashed &&
                    stroke.toIndex == link.toIndex &&
                    abs(stroke.xy[stroke.xy.size - 2] - to.x) < TOLERANCE &&
                    abs(stroke.xy[stroke.xy.size - 1] - (to.y - halfH)) < TOLERANCE
            }
            assertTrue(reachesChild)
        }
    }

    @Test
    fun dependencyLanesStayOutOfTheCards() {
        val from = PlacedNode("a", "a", ModuleKind.Jvm, 0f, 0f, 0, null, "a")
        val near = PlacedNode("b", "b", ModuleKind.Jvm, 40f, GraphMetrics.Y_GAP, 1, null, "b")
        val beside = PlacedNode("c", "c", ModuleKind.Android, SIDE_BY_SIDE, 0f, 0, null, "c")
        val far = PlacedNode("d", "d", ModuleKind.Jvm, 80f, GraphMetrics.Y_GAP * 3f, 2, null, "d")
        val map = ModuleMap(
            nodes = persistentListOf(from, near, beside, far),
            links = persistentListOf(
                PlacedLink(0, 1, ModuleLinkKind.ProjectDep),
                PlacedLink(0, 2, ModuleLinkKind.ProjectDep),
                PlacedLink(0, 3, ModuleLinkKind.ProjectDep),
            ),
            minX = -GraphMetrics.X_GAP,
            minY = -GraphMetrics.Y_GAP,
            maxX = SIDE_BY_SIDE + GraphMetrics.X_GAP,
            maxY = GraphMetrics.Y_GAP * 4f,
            indexById = persistentMapOf("a" to 0, "b" to 1, "c" to 2, "d" to 3),
        )
        val routes = moduleRoutes(map)
        val adjacent = routes.linkXy[0]
        val barY = adjacent[3]
        val cardBottom = from.y + GraphMetrics.CARD_H * 0.5f
        val cardTop = near.y - GraphMetrics.CARD_H * 0.5f
        assertTrue(barY > cardBottom)
        assertTrue(barY < cardTop)
        val side = routes.linkXy[1]
        val under = side[3]
        assertTrue(under > cardBottom)
        val jump = routes.linkXy[2]
        val gutter = jump.filterIndexed { index, _ -> index % 2 == 0 }.min()
        assertTrue(gutter < from.x - GraphMetrics.CARD_W * 0.5f)
    }

    @Test
    fun crowdedDependencyChannelCollapsesToOneRail() {
        val count = GraphScheme.MAX_SEPARATE_DEPENDENCIES + 3
        val nodes = ArrayList<PlacedNode>(count * 2)
        val links = ArrayList<PlacedLink>(count)
        repeat(count) { index ->
            nodes += PlacedNode("s$index", "s$index", ModuleKind.Jvm, index * GraphMetrics.X_GAP, 0f, 0, null, "s$index")
        }
        repeat(count) { index ->
            nodes += PlacedNode(
                "t$index",
                "t$index",
                ModuleKind.Android,
                index * GraphMetrics.X_GAP,
                GraphMetrics.Y_GAP,
                1,
                null,
                "t$index",
            )
            links += PlacedLink(index, count + index, ModuleLinkKind.ProjectDep)
        }
        val map = ModuleMap(
            nodes = nodes.toPersistentList(),
            links = links.toPersistentList(),
            minX = -GraphMetrics.X_GAP,
            minY = -GraphMetrics.Y_GAP,
            maxX = count * GraphMetrics.X_GAP,
            maxY = GraphMetrics.Y_GAP * 2f,
            indexById = nodes.mapIndexed { index, node -> node.id to index }.toMap().toPersistentMap(),
        )
        val routes = moduleRoutes(map)
        val dashed = routes.strokes.filter { it.dashed }
        assertEquals(1, dashed.size)
        val ys = dashed[0].xy.filterIndexed { index, _ -> index % 2 == 1 }
        val span = ys.max() - ys.min()
        assertTrue(span < GraphScheme.DEPENDENCY_STRIP)
        val rail = ys.first()
        val aboveBottom = GraphMetrics.CARD_H * 0.5f
        val belowTop = GraphMetrics.Y_GAP - GraphMetrics.CARD_H * 0.5f
        assertTrue(rail > aboveBottom)
        assertTrue(rail < belowTop)
    }

    @Test
    fun dependencyCurveLeavesTheCardEdge() {
        val from = PlacedNode("a", "a", ModuleKind.Jvm, 0f, 0f, 0, null, "a")
        val to = PlacedNode("b", "b", ModuleKind.Jvm, 40f, GraphMetrics.Y_GAP, 1, null, "b")
        val map = ModuleMap(
            nodes = persistentListOf(from, to),
            links = persistentListOf(PlacedLink(0, 1, ModuleLinkKind.ProjectDep)),
            minX = -GraphMetrics.X_GAP,
            minY = -GraphMetrics.Y_GAP,
            maxX = GraphMetrics.X_GAP,
            maxY = GraphMetrics.Y_GAP * 2f,
            indexById = persistentMapOf("a" to 0, "b" to 1),
        )
        val widths = floatArrayOf(WIDE, GraphMetrics.CARD_W)
        val xy = moduleRoutes(map, widths).linkXy[0]
        val halfH = GraphMetrics.CARD_H * 0.5f
        assertEquals(from.x, xy[0], absoluteTolerance = TOLERANCE)
        assertEquals(from.y + halfH, xy[1], absoluteTolerance = TOLERANCE)
        assertEquals(to.x, xy[xy.size - 2], absoluteTolerance = TOLERANCE)
        assertEquals(to.y - halfH, xy[xy.size - 1], absoluteTolerance = TOLERANCE)
        val side = moduleRoutes(
            map.copy(
                nodes = persistentListOf(from, to.copy(x = SIDE_BY_SIDE, y = 0f)),
            ),
            widths,
        ).linkXy[0]
        assertEquals(WIDE * 0.5f, side[0], absoluteTolerance = TOLERANCE)
        assertEquals(0f, side[1], absoluteTolerance = TOLERANCE)
        val endX = side[side.size - 2]
        assertTrue(abs(endX - (SIDE_BY_SIDE - GraphMetrics.CARD_W * 0.5f)) < TOLERANCE)
    }

    @Test
    fun hitTestUsesTheRealCardWidth() {
        val node = PlacedNode(":lib", "lib", ModuleKind.Jvm, 0f, 0f, 0, null, ":lib")
        val map = ModuleMap(
            nodes = persistentListOf(node),
            links = persistentListOf(),
            minX = -WIDE,
            minY = -GraphMetrics.Y_GAP,
            maxX = WIDE,
            maxY = GraphMetrics.Y_GAP,
            indexById = persistentMapOf(node.id to 0),
        )
        val grid = spatialGridOf(map)
        val buffer = IntArray(8)
        val sizeOut = IntArray(1)
        val widths = floatArrayOf(WIDE)
        val inside = hitCardIndex(map, grid, WIDE * 0.5f - 2f, 0f, buffer, sizeOut, widths)
        val outside = hitCardIndex(map, grid, WIDE * 0.5f + 2f, 0f, buffer, sizeOut, widths)
        assertEquals(0, inside)
        assertEquals(-1, outside)
    }

    @Test
    fun dashedCurveStillReachesTheTargetCard() {
        val from = PlacedNode("a", "a", ModuleKind.Jvm, 0f, 0f, 0, null, "a")
        val to = PlacedNode("b", "b", ModuleKind.Android, 0f, GraphMetrics.Y_GAP * 2f, 1, null, "b")
        val map = ModuleMap(
            nodes = persistentListOf(from, to),
            links = persistentListOf(PlacedLink(0, 1, ModuleLinkKind.ProjectDep)),
            minX = -GraphMetrics.X_GAP,
            minY = -GraphMetrics.Y_GAP,
            maxX = GraphMetrics.X_GAP,
            maxY = GraphMetrics.Y_GAP * 3f,
            indexById = persistentMapOf("a" to 0, "b" to 1),
        )
        val dashes = moduleRoutes(map).strokes.first { it.dashed }.xy
        assertTrue(dashes.size >= 4)
        val halfH = GraphMetrics.CARD_H * 0.5f
        val startGap = hypot(dashes[0] - from.x, dashes[1] - (from.y + halfH))
        assertTrue(startGap < TOLERANCE)
    }

    private companion object {
        const val LEAF_COUNT = 16
        const val WIDE = 800f
        const val SIDE_BY_SIDE = 900f
        const val TOLERANCE = 0.05f
    }
}
