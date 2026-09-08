package com.zaycev.libshelper.core.network

import com.zaycev.libshelper.core.di.AppScope
import com.zaycev.libshelper.core.model.HttpProxySettings
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@SingleIn(AppScope::class)
@Inject
internal class LibsHelperHttpClients : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val clients = ConcurrentHashMap<String, HttpClient>()

    fun client(httpProxy: HttpProxySettings?): HttpClient {
        check(!closed.get()) { "HttpClient already closed" }
        val key = httpProxy?.let { "${it.host}:${it.port}" } ?: DIRECT_KEY
        return clients.computeIfAbsent(key) { create(httpProxy) }
    }

    private fun create(httpProxy: HttpProxySettings?): HttpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofMillis(NetworkTimeouts.CONNECTION_MS))
            .proxy(proxySelector(httpProxy))
            .build()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
    }
}

internal fun proxySelector(httpProxy: HttpProxySettings?): ProxySelector {
    val host = httpProxy?.host?.trim().orEmpty()
    val port = httpProxy?.port ?: 0
    if (host.isEmpty() || port !in MIN_PROXY_PORT..MAX_PROXY_PORT) {
        return ProxySelector.of(null)
    }
    return ProxySelector.of(InetSocketAddress(host, port))
}

private const val DIRECT_KEY = "direct"
private const val MIN_PROXY_PORT = 1
private const val MAX_PROXY_PORT = 65535
