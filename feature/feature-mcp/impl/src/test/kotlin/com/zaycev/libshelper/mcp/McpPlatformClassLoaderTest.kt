package com.zaycev.libshelper.mcp

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URI
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class McpPlatformClassLoaderTest {
    @Test
    fun serverBindsWhenPlatformCoroutinesHideRunBlockingK() {
        val parent = URLClassLoader(
            urls("mcp.stdlib", "mcp.serializationCore", "mcp.serializationJson", "mcp.slf4j", "mcp.platformCoroutines"),
            ClassLoader.getPlatformClassLoader(),
        )
        val plugin = URLClassLoader(urls("mcp.shadowJar", "mcp.apiClasses", "mcp.utilsClasses"), parent)
        val backendType = plugin.loadClass("com.zaycev.libshelper.mcp.LibsHelperBackend")
        val backend = Proxy.newProxyInstance(plugin, arrayOf(backendType)) { _, method, _ ->
            if (method.name == "projects") emptyList<Any>() else null
        }
        val hostType = plugin.loadClass("com.zaycev.libshelper.mcp.LibsHelperMcpHost")
        val limits = plugin.loadClass("com.zaycev.libshelper.mcp.McpLimits").getDeclaredConstructor().newInstance()
        val host = hostType.getConstructor(
            backendType,
            String::class.java,
            Int::class.javaPrimitiveType,
            String::class.java,
            limits.javaClass,
        ).newInstance(backend, "test-token", 0, "test", limits)
        try {
            invoke(host, "start")
            val port = invoke(host, "getPort") as Int
            val response = HttpClient.newHttpClient().send(initialize(port), HttpResponse.BodyHandlers.ofString())
            assertEquals(401, response.statusCode(), response.body())
        } finally {
            runCatching { invoke(host, "stop") }
            plugin.close()
            parent.close()
        }
    }
}

private fun urls(vararg keys: String) = keys
    .map { Path.of(System.getProperty(it)).toUri().toURL() }
    .toTypedArray()

private fun initialize(port: Int): HttpRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/mcp"))
    .header("Content-Type", "application/json")
    .header("Accept", "application/json, text/event-stream")
    .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE))
    .build()

private fun invoke(target: Any, name: String): Any? = try {
    target.javaClass.getMethod(name).invoke(target)
} catch (error: InvocationTargetException) {
    throw error.cause ?: error
}

private const val INITIALIZE =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""
