package com.zaycev.libshelper.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpModelTest {
    @Test
    fun viewsKeepTheCoordinatesAndTheChannel() {
        val view = McpDependencyView(
            key = "com.example:demo",
            current = "1.0.0",
            recommended = "1.2.0",
            channel = "Stable",
            outdated = true,
            score = "Safe",
            reasons = listOf("shared:demo"),
            versions = listOf("1.0.0", "1.2.0"),
        )
        val group = McpUpdateGroup(title = "safe", items = listOf(view))
        assertEquals("com.example:demo", group.items.single().key)
        assertTrue(group.items.single().outdated)
        assertEquals("/tmp/demo", McpProjectRef("demo", "/tmp/demo").path)
    }

    @Test
    fun limitsFailureAndHostClose() {
        val limits = McpLimits()
        assertEquals(262_144, limits.maxBodyBytes)
        assertEquals(McpFailureBody("busy", "busy"), McpFailureBody("busy", "busy").copy())
        var stopped = false
        val host = object : McpServerHost {
            override val port: Int = 9
            override fun start() = Unit
            override fun stop() {
                stopped = true
            }
        }
        host.close()
        assertTrue(stopped)
        assertEquals(GateDecision.Allow, GateDecision.Allow)
    }
}
