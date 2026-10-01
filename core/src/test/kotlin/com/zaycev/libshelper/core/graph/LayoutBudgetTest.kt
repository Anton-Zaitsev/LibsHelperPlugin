package com.zaycev.libshelper.core.graph

import com.zaycev.libshelper.core.model.ModuleGraph
import com.zaycev.libshelper.core.model.ModuleKind
import com.zaycev.libshelper.core.model.ModuleLinkKind
import com.zaycev.libshelper.core.model.ModuleNode
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.collections.immutable.toPersistentList

class LayoutBudgetTest {
    @Test
    fun twelveHundredModulesStayInsideTheTimeBudget() {
        val nodes = (0 until 1200).map { index ->
            ModuleNode(
                id = ":m$index",
                label = "m$index",
                kind = ModuleKind.Jvm,
                parentId = if (index == 0) null else ":m${index / 8}",
            )
        }
        val links = (1 until 1200).map { index ->
            com.zaycev.libshelper.core.model.ModuleLink(":m${index / 8}", ":m$index", ModuleLinkKind.Hierarchy)
        }
        val graph = ModuleGraph(nodes.toPersistentList(), links.toPersistentList())
        val elapsed = measureTimeMillis {
            val map = layoutModuleMap(graph)
            edgeIndexOf(map)
        }
        assertTrue(elapsed < 2_000, "layout took ${elapsed}ms")
    }

    @Test
    fun twoThousandModulesStayInsideTheTimeBudget() {
        val nodes = ArrayList<ModuleNode>(MODULE_COUNT + 1)
        nodes += ModuleNode(":", "root", ModuleKind.Root, null)
        repeat(GROUP_COUNT) { group ->
            val groupId = ":g$group"
            nodes += ModuleNode(groupId, "g$group", ModuleKind.Gradle, ":")
            repeat(LEAVES_PER_GROUP) { child ->
                nodes += ModuleNode("$groupId:m$child", "m$child", ModuleKind.Jvm, groupId)
            }
        }
        val links = nodes.drop(1).map { node ->
            com.zaycev.libshelper.core.model.ModuleLink(node.parentId ?: ":", node.id, ModuleLinkKind.Hierarchy)
        }
        val graph = ModuleGraph(nodes.toPersistentList(), links.toPersistentList())
        lateinit var map: ModuleMap
        val elapsed = measureTimeMillis {
            map = layoutModuleMap(graph)
            moduleRoutes(map)
            edgeIndexOf(map)
        }
        assertTrue(elapsed < 2_000, "layout took ${elapsed}ms")
        assertEquals(nodes.size, map.nodes.size)
        assertNoCardOverlap(map)
        val leaves = map.nodes.filter { it.parentId == ":g0" }
        assertTrue(leaves.map { it.y }.distinct().size > 1)
        val span = leaves.maxOf { it.x } - leaves.minOf { it.x }
        assertTrue(span < GraphMetrics.X_GAP * 8f)
    }
}

private fun assertNoCardOverlap(map: ModuleMap) {
    val cell = GraphMetrics.X_GAP
    val buckets = HashMap<Long, MutableList<Int>>()
    map.nodes.forEachIndexed { index, node ->
        val key = packCell(kotlin.math.floor(node.x / cell).toInt(), kotlin.math.floor(node.y / cell).toInt())
        buckets.getOrPut(key) { ArrayList() }.add(index)
    }
    map.nodes.forEachIndexed { index, node ->
        val column = kotlin.math.floor(node.x / cell).toInt()
        val row = kotlin.math.floor(node.y / cell).toInt()
        for (dx in -1..1) {
            for (dy in -1..1) {
                val near = buckets[packCell(column + dx, row + dy)] ?: continue
                for (other in near) {
                    if (other <= index) continue
                    val nodeB = map.nodes[other]
                    val gapX = kotlin.math.abs(node.x - nodeB.x) - GraphMetrics.CARD_W
                    val gapY = kotlin.math.abs(node.y - nodeB.y) - GraphMetrics.CARD_H
                    assertTrue(gapX >= -OVERLAP || gapY >= -OVERLAP, "${node.id} overlaps ${nodeB.id}")
                }
            }
        }
    }
}

private fun packCell(column: Int, row: Int): Long = (column.toLong() shl 32) xor (row.toLong() and 0xffffffffL)

private const val MODULE_COUNT = 2_000
private const val GROUP_COUNT = 40
private const val LEAVES_PER_GROUP = 50
private const val OVERLAP = 0.6f
