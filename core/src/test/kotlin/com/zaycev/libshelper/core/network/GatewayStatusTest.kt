package com.zaycev.libshelper.core.network

import com.sun.net.httpserver.HttpServer
import com.zaycev.libshelper.core.auth.NoOpRepositoryAuthenticator
import com.zaycev.libshelper.core.concurrency.standardDispatcherProvider
import com.zaycev.libshelper.core.log.NoOpLibsHelperLogger
import com.zaycev.libshelper.core.metadata.connectStatusOf
import com.zaycev.libshelper.core.model.ConnectStatus
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GatewayStatusTest {
    @Test
    fun statusCodesMapToFailures() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        listOf(401, 403, 404, 500).forEach { status ->
            server.createContext("/$status") { exchange ->
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
        }
        server.start()
        val clients = LibsHelperHttpClients()
        try {
            val gateway = JdkMetadataGateway(
                clients,
                NoOpRepositoryAuthenticator(),
                standardDispatcherProvider(),
                NoOpLibsHelperLogger(),
            )
            val base = "http://127.0.0.1:${server.address.port}"
            assertIs<HttpFailure.Unauthorized>((gateway.get("$base/401", null, null, false) as HttpGetResult.Failure).error)
            assertIs<HttpFailure.Forbidden>((gateway.get("$base/403", null, null, false) as HttpGetResult.Failure).error)
            assertIs<HttpFailure.NotFound>((gateway.get("$base/404", null, null, false) as HttpGetResult.Failure).error)
            assertIs<HttpFailure.HttpStatus>((gateway.get("$base/500", null, null, false) as HttpGetResult.Failure).error)
            val timeout = HttpFailure.Timeout("$base/slow", NetworkTimeouts.REQUEST_MS)
            val unreachable = HttpFailure.Unreachable("$base/down", "down")
            assertEquals("timeout:8", timeout.userMessage)
            assertEquals("down", unreachable.userMessage)
            assertEquals(ConnectStatus.Timeout, connectStatusOf(timeout))
            assertEquals(ConnectStatus.Unauthorized, connectStatusOf(HttpFailure.Unauthorized("$base/401", "127.0.0.1")))
            assertEquals(ConnectStatus.Forbidden, connectStatusOf(HttpFailure.Forbidden("$base/403")))
            assertEquals(ConnectStatus.Unreachable, connectStatusOf(HttpFailure.NotFound("$base/404")))
            assertEquals(ConnectStatus.Unreachable, connectStatusOf(unreachable))
            assertEquals(ConnectStatus.Unreachable, connectStatusOf(HttpFailure.HttpStatus("$base/500", 500)))
            assertEquals(ConnectStatus.Online, connectStatusOf(null))
        } finally {
            server.stop(0)
            clients.close()
        }
    }
}
