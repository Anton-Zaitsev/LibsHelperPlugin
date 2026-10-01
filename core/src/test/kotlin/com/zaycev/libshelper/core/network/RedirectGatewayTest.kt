package com.zaycev.libshelper.core.network

import com.sun.net.httpserver.HttpServer
import com.zaycev.libshelper.core.auth.NoOpRepositoryAuthenticator
import com.zaycev.libshelper.core.auth.RepositoryAuth
import com.zaycev.libshelper.core.auth.RepositoryAuthScheme
import com.zaycev.libshelper.core.concurrency.standardDispatcherProvider
import com.zaycev.libshelper.core.log.NoOpLibsHelperLogger
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RedirectGatewayTest {
    @Test
    fun authorizationIsDroppedWhenTheHostChanges() = runTest {
        val seen = AtomicReference<String?>(null)
        val second = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        second.createContext("/meta") { exchange ->
            seen.set(exchange.requestHeaders.getFirst("Authorization"))
            val body = "ok".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        second.start()
        val first = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        first.createContext("/start") { exchange ->
            exchange.responseHeaders.add("Location", "http://localhost:${second.address.port}/meta")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        first.start()
        val clients = LibsHelperHttpClients()
        try {
            val gateway = JdkMetadataGateway(
                clients,
                NoOpRepositoryAuthenticator(),
                standardDispatcherProvider(),
                NoOpLibsHelperLogger(),
            )
            val result = gateway.get(
                url = "http://127.0.0.1:${first.address.port}/start",
                httpProxy = null,
                credentials = RepositoryAuth(RepositoryAuthScheme.Bearer, "", "secret-token"),
                allowAuthPrompt = false,
            )
            val success = result as HttpGetResult.Success
            assertEquals("ok", success.body)
            assertNull(seen.get())
        } finally {
            first.stop(0)
            second.stop(0)
            clients.close()
        }
    }
}
