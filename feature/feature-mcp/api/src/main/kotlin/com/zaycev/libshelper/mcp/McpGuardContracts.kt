package com.zaycev.libshelper.mcp

data class McpLimits(
    val requestTimeoutMs: Long = 10_000,
    val maxRequestsPerWindow: Int = 40,
    val windowMs: Long = 1_000,
    val maxBodyBytes: Int = 262_144,
    val maxInFlight: Int = 16,
)

enum class GateDecision { Allow, RateLimited, Busy }

interface McpRequestGate {
    fun admit(remote: String, nowMs: Long): GateDecision
    fun release()
}

interface McpCredentialCheck {
    fun accepts(authorization: String?): Boolean
}

data class McpFailureBody(
    val code: String,
    val message: String,
)

interface McpClientSettings {
    fun newToken(): String
    fun cursor(port: Int, token: String): String
    fun claudeCode(port: Int, token: String): String
    fun claudeDesktop(port: Int, token: String): String
}
