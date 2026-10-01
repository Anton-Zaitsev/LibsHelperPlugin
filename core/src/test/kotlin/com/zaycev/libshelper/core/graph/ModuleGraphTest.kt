package com.zaycev.libshelper.core.graph

import com.zaycev.libshelper.core.inventory.buildModuleGraph
import com.zaycev.libshelper.core.inventory.moduleScriptCandidates
import com.zaycev.libshelper.core.inventory.parentModuleId
import com.zaycev.libshelper.core.inventory.parseIncludedModules
import com.zaycev.libshelper.core.inventory.parseModuleFacts
import com.zaycev.libshelper.core.model.ModuleKind
import com.zaycev.libshelper.core.model.ModuleLinkKind
import com.zaycev.libshelper.core.model.ModuleNode
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModuleGraphTest {
    @Test
    fun parsesKmpTargetsAndProjectDeps() {
        val facts = parseModuleFacts(
            """
            plugins {
                kotlin("multiplatform")
                id("com.android.kotlin.multiplatform.library")
            }
            kotlin {
                androidTarget()
                jvm()
                iosArm64()
                iosSimulatorArm64()
                sourceSets {
                    commonMain.dependencies {
                        implementation(projects.core.network)
                    }
                }
            }
            dependencies {
                implementation(project(":feature:home"))
            }
            """.trimIndent(),
        )
        assertEquals(ModuleKind.Kmp, facts.kind)
        assertTrue(facts.targets.containsAll(listOf(ModuleKind.Android, ModuleKind.Jvm, ModuleKind.Ios, ModuleKind.Common)))
        assertTrue(facts.projectDeps.contains(":core:network"))
        assertTrue(facts.projectDeps.contains(":feature:home"))
    }

    @Test
    fun parsesSeveralIncludes() {
        val ids = parseIncludedModules(
            """
            include(":app", ":shared")
            include(":feature:home")
            includeBuild("build-logic")
            """.trimIndent(),
        )
        assertEquals(listOf(":app", ":shared", ":feature:home"), ids)
    }

    @Test
    fun buildsHierarchyTargetsAndDepLinks() {
        val facts = mapOf(
            ":app" to parseModuleFacts(
                """
                plugins { id("com.android.application") }
                dependencies { implementation(project(":shared")) }
                """.trimIndent(),
            ),
            ":shared" to parseModuleFacts(
                """
                plugins { kotlin("multiplatform") }
                kotlin {
                    androidTarget()
                    jvm()
                }
                """.trimIndent(),
            ),
        )
        val graph = buildModuleGraph(listOf(":", ":app", ":shared"), facts)
        assertTrue(graph.nodes.any { it.id == ":shared#android" && it.kind == ModuleKind.Android })
        assertTrue(graph.nodes.any { it.id == ":shared#jvm" && it.kind == ModuleKind.Jvm })
        assertTrue(graph.links.any { it.fromId == ":app" && it.toId == ":shared" && it.kind == ModuleLinkKind.ProjectDep })
        assertTrue(graph.links.any { it.fromId == ":shared" && it.toId == ":shared#android" && it.kind == ModuleLinkKind.Target })
        assertEquals(ModuleKind.Android, graph.nodes.first { it.id == ":app" }.kind)
        assertEquals(ModuleKind.Root, graph.nodes.first { it.id == ":" }.kind)
    }

    @Test
    fun layoutPlacesKmpTargetsUnderTheModule() {
        val graph = buildModuleGraph(
            listOf(":", ":shared"),
            mapOf(
                ":shared" to parseModuleFacts(
                    """
                    plugins { kotlin("multiplatform") }
                    kotlin {
                        androidTarget()
                        jvm()
                        wasmJs()
                    }
                    """.trimIndent(),
                ),
            ),
        )
        val map = layoutModuleMap(graph)
        val shared = map.nodes.first { it.id == ":shared" }
        val android = map.nodes.first { it.id == ":shared#android" }
        val jvm = map.nodes.first { it.id == ":shared#jvm" }
        val wasm = map.nodes.first { it.id == ":shared#wasm" }
        assertTrue(android.y > shared.y)
        assertEquals(android.y, jvm.y)
        assertEquals(android.y, wasm.y)
        val visible = hashSetOf(map.indexById.getValue(":shared"))
        expandVisibleKmpChildren(map, visible)
        assertTrue(
            visible.containsAll(
                setOf(
                    map.indexById.getValue(":shared#android"),
                    map.indexById.getValue(":shared#jvm"),
                    map.indexById.getValue(":shared#wasm"),
                    map.indexById.getValue(":shared#common"),
                ),
            ),
        )
        val rootOnly = hashSetOf(map.indexById.getValue(":"))
        expandVisibleKmpChildren(map, rootOnly)
        assertEquals(setOf(map.indexById.getValue(":")), rootOnly)
    }

    @Test
    fun parentAndScriptPathsHandleKmpTargets() {
        assertEquals(":shared", parentModuleId(":shared#wasm"))
        assertEquals(":", parentModuleId(":app"))
        assertEquals(":feature", parentModuleId(":feature:home"))
        assertEquals(listOf("build.gradle.kts", "build.gradle"), moduleScriptCandidates(":"))
        assertEquals(listOf("app/build.gradle.kts", "app/build.gradle"), moduleScriptCandidates(":app"))
        assertEquals(
            listOf("feature/home/build.gradle.kts", "feature/home/build.gradle"),
            moduleScriptCandidates(":feature:home"),
        )
        assertEquals(
            listOf("shared/build.gradle.kts", "shared/build.gradle"),
            moduleScriptCandidates(":shared#android"),
        )
    }

    @Test
    fun layoutKeepsChildrenBelowParentsAndSearchFindsModule() {
        val graph = buildModuleGraph(
            listOf(":", ":app", ":feature:home"),
            emptyMap(),
        )
        val map = layoutModuleMap(graph)
        val root = map.nodes.first { it.id == ":" }
        val app = map.nodes.first { it.id == ":app" }
        val home = map.nodes.first { it.id == ":feature:home" }
        val feature = map.nodes.first { it.id == ":feature" }
        assertTrue(app.y > root.y)
        assertTrue(home.y > feature.y)
        assertEquals(":feature → :feature:home", home.pathLabel)
        assertEquals(":app", app.pathLabel)
        assertEquals(listOf(map.indexById.getValue(":app")), searchModuleIndices(map, "app"))
        assertTrue(relatedNodeIds(map, ":feature:home").containsAll(setOf(":", ":feature", ":feature:home")))
    }

    @Test
    fun spreadCardsMatchesFixedLayoutWhenCardsAreUniform() {
        val graph = buildModuleGraph(listOf(":", ":app", ":feature:home"), emptyMap())
        val map = layoutModuleMap(graph)
        val spread = spreadCards(map, FloatArray(map.nodes.size) { GraphMetrics.CARD_W })
        map.nodes.forEachIndexed { index, node ->
            assertEquals(node.x, spread.nodes[index].x, absoluteTolerance = 0.01f)
            assertEquals(node.y, spread.nodes[index].y, absoluteTolerance = 0.01f)
        }
    }

    @Test
    fun spreadCardsKeepsWideLabelsFromOverlapping() {
        val graph = buildModuleGraph(listOf(":", ":app", ":lib"), emptyMap())
        val map = layoutModuleMap(graph)
        val app = map.indexById.getValue(":app")
        val lib = map.indexById.getValue(":lib")
        val widths = FloatArray(map.nodes.size) { GraphMetrics.CARD_W }
        widths[app] = WIDE_CARD
        val spread = spreadCards(map, widths)
        val gap = spread.nodes[lib].x - spread.nodes[app].x
        val need = (widths[app] + widths[lib]) * 0.5f + GraphMetrics.CARD_GAP
        assertTrue(gap + 0.01f >= need)
        val wide = spread.nodes[app]
        assertTrue(spread.minX <= wide.x - widths[app] * 0.5f)
        assertTrue(spread.maxX >= wide.x + widths[app] * 0.5f)
    }

    @Test
    fun cardWidthStaysAtLeastTheStandardCard() {
        assertEquals(GraphMetrics.CARD_W, cardWidthForLabel(10f, LABEL_CHROME))
        assertEquals(WIDE_CARD + LABEL_CHROME, cardWidthForLabel(WIDE_CARD, LABEL_CHROME))
        assertEquals(GraphMetrics.CARD_W, cardWidthForLabel(Float.NaN, LABEL_CHROME))
    }

    @Test
    fun spatialGridAndLayoutHandleAThousandNodes() {
        val nodes = mutableListOf(ModuleNode(":", "root", ModuleKind.Root, null))
        repeat(GROUP_COUNT) { group ->
            val groupId = ":g$group"
            nodes += ModuleNode(groupId, "g$group", ModuleKind.Gradle, ":")
            repeat(CHILD_COUNT) { child ->
                nodes += ModuleNode(
                    id = "$groupId:m$child",
                    label = "m$child",
                    kind = if (child % 2 == 0) ModuleKind.Android else ModuleKind.Jvm,
                    parentId = groupId,
                )
            }
        }
        val graph = com.zaycev.libshelper.core.model.ModuleGraph(nodes.toPersistentList(), persistentListOf())
        val map = layoutModuleMap(graph)
        assertEquals(nodes.size, map.nodes.size)
        val grid = spatialGridOf(map)
        val sample = map.nodes[map.nodes.size / 2]
        val buffer = IntArray(QUERY)
        val sizeOut = IntArray(1)
        val hit = nearestNodeIndex(map, grid, sample.x, sample.y, 20f, buffer, sizeOut)
        assertEquals(sample.id, map.nodes[hit].id)
        assertTrue(sizeOut[0] > 0)
    }

    private companion object {
        const val GROUP_COUNT = 30
        const val CHILD_COUNT = 40
        const val QUERY = 64
        const val WIDE_CARD = 800f
        const val LABEL_CHROME = 75f
    }
}
