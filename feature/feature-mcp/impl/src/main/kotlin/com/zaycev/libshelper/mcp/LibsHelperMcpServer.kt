package com.zaycev.libshelper.mcp

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking

class LibsHelperMcpHost(
    private val backend: LibsHelperBackend,
    private val token: String,
    requestedPort: Int,
    private val serverVersion: String,
    private val limits: McpLimits = McpLimits(),
) : McpServerHost {
    private val portRef = AtomicInt(requestedPort)
    private val serverRef = AtomicReference<EmbeddedServer<*, *>?>(null)
    private val lifecycle = Any()

    override val port: Int
        get() = portRef.load()

    override fun start() {
        synchronized(lifecycle) {
            if (serverRef.load() != null) return
            val requestGate = WindowRequestGate(limits)
            val requestLimits = limits
            val embedded = embeddedServer(CIO, host = LOOPBACK, port = port) {
                installMcpErrors()
                install(RequestBudget) {
                    gate = requestGate
                    this.limits = requestLimits
                }
                install(BearerAuth) {
                    auth = BearerCredentialCheck(token)
                }
                mcpStreamableHttp(
                    path = "/mcp",
                    allowedOrigins = listOf("http://127.0.0.1", "http://localhost", "http://[::1]"),
                ) {
                    buildServer(backend, serverVersion)
                }
            }
            try {
                embedded.start(wait = false)
                val bound = runBlocking {
                    embedded.engine.resolvedConnectors().first().port
                }
                portRef.store(bound)
                serverRef.store(embedded)
            } catch (error: Exception) {
                runCatching { embedded.stop(GRACE_MS, GRACE_MS) }
                throw IllegalStateException("mcp_bind_failed", error)
            }
        }
    }

    override fun stop() {
        synchronized(lifecycle) {
            serverRef.exchange(null)?.stop(GRACE_MS, GRACE_MS)
        }
    }
}

@Suppress("LongMethod")
internal fun buildServer(backend: LibsHelperBackend, serverVersion: String): Server {
    val server = Server(
        serverInfo = Implementation(name = "libshelper", version = serverVersion),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
                resources = ServerCapabilities.Resources(listChanged = false, subscribe = false),
                prompts = ServerCapabilities.Prompts(listChanged = false),
            ),
        ),
    )
    server.addTool(
        name = "analyze_project",
        description = "Runs analysis for an open project and returns how many libraries were checked.",
        inputSchema = schema("projectPath"),
    ) { request ->
        val path = arg(request, "projectPath") ?: return@addTool text("bad_argument")
        text(backend.analyze(path))
    }
    server.addTool(
        name = "list_projects",
        description = "Open Gradle projects LibsHelper can analyze.",
    ) {
        text(backend.projects().joinToString("\n") { project -> "${project.name} ${project.path}" }.ifBlank { "(none)" })
    }
    server.addTool(
        name = "list_dependencies",
        description = "Declared libraries and whether a newer version is available.",
        inputSchema = schema("projectPath", "outdatedOnly"),
    ) { request ->
        val path = arg(request, "projectPath") ?: return@addTool text("bad_argument")
        val outdatedOnly = arg(request, "outdatedOnly") ?: return@addTool text("bad_argument")
        val outdated = outdatedOnly == "true"
        val rows = backend.dependencies(path, outdated)
        text(rows.joinToString("\n") { row ->
            "${row.key} current=${row.current} recommended=${row.recommended ?: "-"} score=${row.score ?: "-"}"
        }.ifBlank { "(none)" })
    }
    server.addTool(
        name = "get_dependency",
        description = "Versions, conflicts, and where one library is declared.",
        inputSchema = schema("projectPath", "key"),
    ) { request ->
        val path = arg(request, "projectPath") ?: return@addTool text("bad_argument")
        val key = arg(request, "key") ?: return@addTool text("bad_argument")
        val found = backend.dependency(path, key)
        text(found?.let { view ->
            buildString {
                appendLine(view.key)
                appendLine("current=${view.current} recommended=${view.recommended} channel=${view.channel}")
                appendLine(view.reasons.joinToString("; ").ifBlank { "no warnings" })
                append(view.versions.joinToString(", "))
            }
        } ?: "not found")
    }
    server.addTool(
        name = "recommend_updates",
        description = "Groups updates into safe, careful, and avoid, with reasons.",
        inputSchema = schema("projectPath"),
    ) { request ->
        val path = arg(request, "projectPath") ?: return@addTool text("bad_argument")
        val groups = backend.recommend(path)
        text(groups.joinToString("\n\n") { group ->
            group.title + "\n" + group.items.joinToString("\n") { item ->
                "- ${item.key} -> ${item.recommended ?: "?"} (${item.reasons.joinToString("; ")})"
            }
        }.ifBlank { "(none)" })
    }
    server.addTool(
        name = "check_update_impact",
        description = "Explains what changes if a library moves to a version.",
        inputSchema = schema("projectPath", "key", "version"),
    ) { request ->
        val path = arg(request, "projectPath") ?: return@addTool text("bad_argument")
        val key = arg(request, "key") ?: return@addTool text("bad_argument")
        val version = arg(request, "version") ?: return@addTool text("bad_argument")
        text(backend.impact(path, key, version))
    }
    server.addTool(
        name = "apply_update",
        description = "Writes a version only when dryRun is false and the IDE allows file changes. Default is a diff preview.",
        inputSchema = schema("projectPath", "key", "version", "dryRun"),
    ) { request ->
        val path = arg(request, "projectPath") ?: return@addTool text("bad_argument")
        val key = arg(request, "key") ?: return@addTool text("bad_argument")
        val version = arg(request, "version") ?: return@addTool text("bad_argument")
        val dryRun = arg(request, "dryRun") ?: return@addTool text("bad_argument")
        val dry = dryRun.ifBlank { "true" } != "false"
        text(backend.applyUpdate(path, key, version, dry))
    }
    server.addTool(
        name = "list_build_settings",
        description = "SDK, NDK, and JDK toolchain keys and suggested values.",
        inputSchema = schema("projectPath"),
    ) { request ->
        val path = arg(request, "projectPath") ?: return@addTool text("bad_argument")
        text(backend.buildSettings(path).joinToString("\n").ifBlank { "(none)" })
    }
    server.addTool(
        name = "get_module_graph",
        description = "Module map as mermaid or a plain edge list.",
        inputSchema = schema("projectPath", "mermaid"),
    ) { request ->
        val path = arg(request, "projectPath") ?: return@addTool text("bad_argument")
        val mermaid = arg(request, "mermaid") ?: return@addTool text("bad_argument")
        text(backend.moduleGraph(path, mermaid != "false"))
    }
    server.addTool(
        name = "list_repositories",
        description = "Repositories the project declared and how LibsHelper classified them.",
        inputSchema = schema("projectPath"),
    ) { request ->
        val path = arg(request, "projectPath") ?: return@addTool text("bad_argument")
        text(backend.repositories(path).joinToString("\n").ifBlank { "(none)" })
    }
    server.addResourceTemplate(
        uriTemplate = "libshelper://report/{project}",
        name = "project-report",
        description = "Dependency report for an open project. The path segment is the project path, URL-encoded.",
        mimeType = "text/plain",
    ) { request, variables ->
        val project = java.net.URLDecoder.decode(variables["project"].orEmpty(), Charsets.UTF_8)
        ReadResourceResult(
            contents = listOf(
                TextResourceContents(
                    text = reportText(backend, project),
                    uri = request.uri,
                    mimeType = "text/plain",
                ),
            ),
        )
    }
    server.addPrompt(
        name = "review-dependency-updates",
        description = "Review which dependency updates are safe, careful, or better avoided.",
        arguments = listOf(
            PromptArgument(
                name = "projectPath",
                description = "Absolute path of the open Gradle project.",
                required = true,
            ),
        ),
    ) { request ->
        val project = request.arguments?.get("projectPath").orEmpty()
        GetPromptResult(
            description = "Review dependency updates",
            messages = listOf(
                PromptMessage(
                    role = Role.User,
                    content = TextContent(text = reportText(backend, project)),
                ),
            ),
        )
    }
    return server
}

private suspend fun reportText(backend: LibsHelperBackend, project: String): String {
    val groups = backend.recommend(project)
    return groups.joinToString("\n\n") { group ->
        group.title + "\n" + group.items.joinToString("\n") { item ->
            "- ${item.key} -> ${item.recommended ?: "?"} (${item.reasons.joinToString("; ")})"
        }
    }.ifBlank { "(none)" }
}

private fun schema(vararg names: String): ToolSchema = ToolSchema(
    properties = buildJsonObject {
        names.forEach { name ->
            put(name, buildJsonObject { put("type", "string") })
        }
    },
)

private fun arg(request: io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest, name: String): String? {
    val value = request.arguments?.get(name) ?: return ""
    val primitive = value as? JsonPrimitive ?: return null
    val booleanValue = primitive.booleanOrNull
    if (!primitive.isString && booleanValue == null) return null
    return primitive.content
}

private fun text(value: String): CallToolResult = CallToolResult(content = listOf(TextContent(text = value)))

private const val LOOPBACK = "127.0.0.1"
private const val GRACE_MS = 200L
