package com.zaycev.libshelper.mcp

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay

class McpHttpTest {
    @Test
    fun bearerIsRequiredAndToolsAnswer() {
        val host = LibsHelperMcpHost(FakeBackend(), "test-token", 0, "test")
        host.start()
        val client = HttpClient.newHttpClient()
        try {
            val denied = client.send(post(host.port, null, INITIALIZE), HttpResponse.BodyHandlers.ofString())
            assertEquals(401, denied.statusCode())
            val opened = client.send(post(host.port, "test-token", INITIALIZE), HttpResponse.BodyHandlers.ofString())
            assertTrue(opened.statusCode() in 200..299, "initialize ${opened.statusCode()} ${opened.body()}")
            val session = opened.headers().firstValue("mcp-session-id").orElse(null)
            client.send(
                post(host.port, "test-token", """{"jsonrpc":"2.0","method":"notifications/initialized"}""", session),
                HttpResponse.BodyHandlers.ofString(),
            )
            val calls = listOf(
                ToolCall("list_projects", "{}"),
                ToolCall("analyze_project", """{"projectPath":"/tmp/demo"}"""),
                ToolCall("list_dependencies", """{"projectPath":"/tmp/demo","outdatedOnly":"true"}"""),
                ToolCall("get_dependency", """{"projectPath":"/tmp/demo","key":"g:a"}"""),
                ToolCall("recommend_updates", """{"projectPath":"/tmp/demo"}"""),
                ToolCall("check_update_impact", """{"projectPath":"/tmp/demo","key":"g:a","version":"2"}"""),
                ToolCall("apply_update", """{"projectPath":"/tmp/demo","key":"g:a","version":"2","dryRun":"true"}"""),
                ToolCall("list_build_settings", """{"projectPath":"/tmp/demo"}"""),
                ToolCall("get_module_graph", """{"projectPath":"/tmp/demo","mermaid":"true"}"""),
                ToolCall("list_repositories", """{"projectPath":"/tmp/demo"}"""),
            )
            for (call in calls) {
                val name = call.name
                val arguments = call.arguments
                val listed = client.send(
                    post(host.port, "test-token", toolCall(name, arguments), session),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertTrue(listed.statusCode() in 200..299, "$name ${listed.body()}")
                assertTrue(!listed.body().contains("\"error\""), listed.body())
            }
            val report = client.send(
                post(
                    host.port,
                    "test-token",
                    """{"jsonrpc":"2.0","id":3,"method":"resources/read","params":{"uri":"libshelper://report/demo"}}""",
                    session,
                ),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertTrue(report.body().contains("g:a"), report.body())
            val prompt = client.send(
                post(
                    host.port,
                    "test-token",
                    """{"jsonrpc":"2.0","id":4,"method":"prompts/get","params":{"name":"review-dependency-updates","arguments":{"projectPath":"/tmp/demo"}}}""",
                    session,
                ),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertTrue(prompt.body().contains("safe"), prompt.body())
        } finally {
            host.stop()
        }
    }

    @Test
    fun configsKeepTheTokenOnLocalhost() {
        val cursor = DefaultMcpClientSettings.cursor(9, "abc")
        val claude = DefaultMcpClientSettings.claudeCode(9, "abc")
        val desktop = DefaultMcpClientSettings.claudeDesktop(9, "abc")
        assertTrue(cursor.contains("127.0.0.1:9"))
        assertTrue(claude.contains("Bearer abc"))
        assertTrue(desktop.contains("mcp-remote"))
        assertTrue(DefaultMcpClientSettings.newToken().length > 20)
    }

    @Test
    fun floodIsRejectedAndOversizedBodiesAreRefused() {
        val limits = McpLimits(maxRequestsPerWindow = 2, windowMs = 60_000, maxBodyBytes = 32)
        val host = LibsHelperMcpHost(FakeBackend(), "test-token", 0, "test", limits)
        host.start()
        val client = HttpClient.newHttpClient()
        try {
            val statuses = (1..5).map { client.send(post(host.port, null, INITIALIZE), HttpResponse.BodyHandlers.ofString()).statusCode() }
            assertTrue(statuses.any { it == 429 }, statuses.toString())
        } finally {
            host.stop()
        }
        val sized = LibsHelperMcpHost(FakeBackend(), "test-token", 0, "test", McpLimits(maxBodyBytes = 32))
        sized.start()
        try {
            val oversized = "x".repeat(80)
            val denied = client.send(post(sized.port, "test-token", oversized), HttpResponse.BodyHandlers.ofString())
            assertEquals(413, denied.statusCode())
            val broken = client.send(post(sized.port, "test-token", "{"), HttpResponse.BodyHandlers.ofString())
            assertTrue(broken.statusCode() >= 400, broken.body())
        } finally {
            sized.stop()
        }
    }

    @Test
    fun slowBackendHitsTheDeadlineAndFailuresStayJson() {
        val host = LibsHelperMcpHost(
            SlowBackend(),
            "test-token",
            0,
            "test",
            McpLimits(requestTimeoutMs = 50),
        )
        host.start()
        val client = HttpClient.newHttpClient()
        try {
            val opened = client.send(post(host.port, "test-token", INITIALIZE), HttpResponse.BodyHandlers.ofString())
            val session = opened.headers().firstValue("mcp-session-id").orElse(null)
            client.send(
                post(host.port, "test-token", """{"jsonrpc":"2.0","method":"notifications/initialized"}""", session),
                HttpResponse.BodyHandlers.ofString(),
            )
            val timed = client.send(
                post(host.port, "test-token", toolCall("analyze_project", """{"projectPath":"/tmp/demo"}"""), session),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertTrue(timed.statusCode() == 504 || timed.body().contains("timeout"), timed.body())
            val failedHost = LibsHelperMcpHost(BrokenBackend(), "test-token", 0, "test")
            failedHost.start()
            try {
                val failedOpen = client.send(post(failedHost.port, "test-token", INITIALIZE), HttpResponse.BodyHandlers.ofString())
                val failedSession = failedOpen.headers().firstValue("mcp-session-id").orElse(null)
                val failed = client.send(
                    post(failedHost.port, "test-token", toolCall("analyze_project", """{"projectPath":"/tmp/demo"}"""), failedSession),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertTrue(failed.statusCode() >= 400 || failed.body().contains("broken"), failed.body())
            } finally {
                failedHost.stop()
            }
        } finally {
            host.stop()
        }
    }

    @Test
    fun windowGateLimitsASingleRemote() {
        val gate = WindowRequestGate(McpLimits(maxRequestsPerWindow = 2, windowMs = 60_000, maxInFlight = 4))
        assertEquals(GateDecision.Allow, gate.admit("10.0.0.8", 1_000))
        assertEquals(GateDecision.Allow, gate.admit("10.0.0.8", 1_010))
        assertEquals(GateDecision.RateLimited, gate.admit("10.0.0.8", 1_020))
        gate.release()
        gate.release()
        assertEquals(GateDecision.Allow, gate.admit("10.0.0.9", 1_020))
    }
}

private data class ToolCall(val name: String, val arguments: String)

private fun post(port: Int, token: String?, body: String, session: String? = null): HttpRequest {
    val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/mcp"))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofString(body))
    if (token != null) builder.header("Authorization", "Bearer $token")
    if (session != null) builder.header("Mcp-Session-Id", session)
    return builder.build()
}

private fun toolCall(name: String, arguments: String): String =
    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}"""

private const val INITIALIZE =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""

private open class FakeBackend : LibsHelperBackend {
    override fun projects() = listOf(McpProjectRef("demo", "/tmp/demo"))

    override suspend fun analyze(projectPath: String) = "libraries=1"

    override suspend fun dependencies(projectPath: String, outdatedOnly: Boolean) = listOf(
        McpDependencyView("g:a", "1", "2", "Stable", true, "Safe", emptyList(), listOf("1", "2")),
    )

    override suspend fun dependency(projectPath: String, key: String) = dependencies(projectPath, false).firstOrNull()

    override suspend fun recommend(projectPath: String) = listOf(McpUpdateGroup("safe", dependencies(projectPath, true)))

    override suspend fun impact(projectPath: String, key: String, version: String) = "$key -> $version"

    override suspend fun applyUpdate(projectPath: String, key: String, version: String, dryRun: Boolean) =
        if (dryRun) "dry-run" else "applied"

    override suspend fun buildSettings(projectPath: String) = listOf("compileSdk 35")

    override suspend fun moduleGraph(projectPath: String, mermaid: Boolean) = if (mermaid) "flowchart LR" else "a -> b"

    override suspend fun repositories(projectPath: String) = listOf("https://repo1.maven.org/maven2/")
}

private class SlowBackend : FakeBackend() {
    override suspend fun analyze(projectPath: String): String {
        delay(400)
        return super.analyze(projectPath)
    }
}

private class BrokenBackend : FakeBackend() {
    override suspend fun analyze(projectPath: String): String = error("broken")
}
