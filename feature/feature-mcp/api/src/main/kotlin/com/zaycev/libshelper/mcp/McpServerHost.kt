package com.zaycev.libshelper.mcp

interface McpServerHost : AutoCloseable {
    val port: Int

    fun start()

    fun stop()

    override fun close() {
        stop()
    }
}
