package com.zaycev.libshelper.mcp

import java.security.SecureRandom
import java.util.Base64

object DefaultMcpClientSettings : McpClientSettings {
    override fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    override fun claudeCode(port: Int, token: String): String = """
        {
          "mcpServers": {
            "libshelper": {
              "type": "http",
              "url": "http://127.0.0.1:$port/mcp",
              "headers": { "Authorization": "Bearer $token" }
            }
          }
        }
    """.trimIndent()

    override fun claudeDesktop(port: Int, token: String): String = """
        {
          "mcpServers": {
            "libshelper": {
              "command": "npx",
              "args": ["-y", "mcp-remote", "http://127.0.0.1:$port/mcp", "--header", "Authorization: Bearer $token"]
            }
          }
        }
    """.trimIndent()

    override fun cursor(port: Int, token: String): String = """
        {
          "mcpServers": {
            "libshelper": {
              "url": "http://127.0.0.1:$port/mcp",
              "headers": { "Authorization": "Bearer $token" }
            }
          }
        }
    """.trimIndent()

    private const val TOKEN_BYTES = 32
}
