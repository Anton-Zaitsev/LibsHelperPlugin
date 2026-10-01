package com.zaycev.libshelper.core.inventory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModuleFactsParserTest {
    @Test
    fun multilineIncludeAndProjectsAccessor() {
        val settings = """
            include(
                ":app",
                ":core"
            )
        """.trimIndent()
        assertEquals(listOf(":app", ":core"), parseIncludedModules(settings))
        val script = """
            dependencies {
                implementation(projects.core.data)
            }
        """.trimIndent()
        val facts = parseModuleFacts(script)
        assertTrue(facts.projectDeps.contains(":core:data"))
    }
}
