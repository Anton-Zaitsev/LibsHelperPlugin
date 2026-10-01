package com.zaycev.libshelper.mcp

data class McpProjectRef(
    val name: String,
    val path: String,
)

data class McpDependencyView(
    val key: String,
    val current: String?,
    val recommended: String?,
    val channel: String?,
    val outdated: Boolean,
    val score: String?,
    val reasons: List<String>,
    val versions: List<String>,
)

data class McpUpdateGroup(
    val title: String,
    val items: List<McpDependencyView>,
)

interface LibsHelperBackend {
    fun projects(): List<McpProjectRef>

    suspend fun analyze(projectPath: String): String

    suspend fun dependencies(projectPath: String, outdatedOnly: Boolean): List<McpDependencyView>

    suspend fun dependency(projectPath: String, key: String): McpDependencyView?

    suspend fun recommend(projectPath: String): List<McpUpdateGroup>

    suspend fun impact(projectPath: String, key: String, version: String): String

    suspend fun applyUpdate(projectPath: String, key: String, version: String, dryRun: Boolean): String

    suspend fun buildSettings(projectPath: String): List<String>

    suspend fun moduleGraph(projectPath: String, mermaid: Boolean): String

    suspend fun repositories(projectPath: String): List<String>
}
