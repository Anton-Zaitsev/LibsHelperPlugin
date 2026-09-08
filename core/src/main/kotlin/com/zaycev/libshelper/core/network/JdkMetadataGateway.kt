package com.zaycev.libshelper.core.network

import com.zaycev.libshelper.core.auth.RepositoryAuth
import com.zaycev.libshelper.core.auth.RepositoryAuthenticator
import com.zaycev.libshelper.core.auth.requestHeaders
import com.zaycev.libshelper.core.concurrency.DispatcherProvider
import com.zaycev.libshelper.core.di.AppScope
import com.zaycev.libshelper.core.log.LibsHelperLogger
import com.zaycev.libshelper.core.model.HttpProxySettings
import com.zaycev.libshelper.core.proxy.shouldBypassHttpProxy
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class JdkMetadataGateway(
    private val clients: LibsHelperHttpClients,
    private val authenticator: RepositoryAuthenticator,
    private val dispatchers: DispatcherProvider,
    private val logger: LibsHelperLogger,
) : MetadataGateway {
    private val authMutex = Mutex()

    override suspend fun get(
        url: String,
        httpProxy: HttpProxySettings?,
        credentials: RepositoryAuth?,
        allowAuthPrompt: Boolean,
    ): HttpGetResult {
        currentCoroutineContext().ensureActive()
        val proxy = httpProxy.takeUnless { shouldBypassHttpProxy(url, it) }
        val host = hostFromUrl(url)
        val stored = credentials ?: authenticator.stored(host)
        val first = execute(url, proxy, stored)
        if (!allowAuthPrompt || !needsAuthFix(first, stored)) return first
        return authMutex.withLock {
            val alreadyStored = authenticator.stored(host)
            if (alreadyStored != null && alreadyStored != stored) {
                execute(url, proxy, alreadyStored)
            } else {
                logger.warn("Репозиторий $host отклонил доступ, запрашиваем учётные данные")
                val prompted = authenticator.request(
                    host = host,
                    reason = (first as? HttpGetResult.Failure)?.error?.userMessage
                        ?: "Нужна авторизация ($host)",
                    previous = alreadyStored ?: stored,
                )
                if (prompted == null) first else execute(url, proxy, prompted)
            }
        }
    }

    private fun needsAuthFix(result: HttpGetResult, sent: RepositoryAuth?): Boolean {
        val failure = result as? HttpGetResult.Failure ?: return false
        return when (failure.error) {
            is HttpFailure.Unauthorized -> true
            is HttpFailure.Forbidden -> sent != null && !sent.isBlank
            else -> false
        }
    }

    private suspend fun execute(
        url: String,
        httpProxy: HttpProxySettings?,
        credentials: RepositoryAuth?,
    ): HttpGetResult {
        val startedNanos = System.nanoTime()
        fun elapsedMs(): Long = (System.nanoTime() - startedNanos).coerceAtLeast(0L) / NetworkTimeouts.NANOS_PER_MILLI
        val uri = parseHttpUri(url)
        if (uri == null) {
            return HttpGetResult.Failure(HttpFailure.Unreachable(url, "bad_url"), elapsedMs())
        }
        return try {
            withContext(dispatchers.io) {
                currentCoroutineContext().ensureActive()
                val client = clients.client(httpProxy)
                logger.debug("GET ${uri.host}")
                val response = client.send(buildRequest(uri, credentials), HttpResponse.BodyHandlers.ofByteArray())
                mapResponse(url, response, elapsedMs())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException()
        } catch (_: HttpTimeoutException) {
            timeoutResult(url, elapsedMs())
        } catch (unknown: UnknownHostException) {
            HttpGetResult.Failure(
                HttpFailure.Unreachable(url, "Не удалось разрешить хост: ${unknown.message}"),
                elapsedMs(),
            )
        } catch (error: Exception) {
            logger.warn("Сетевая ошибка ${uri.host}: ${error.message}", error)
            HttpGetResult.Failure(
                HttpFailure.Unreachable(url, error.message ?: "network"),
                elapsedMs(),
            )
        }
    }

    private fun buildRequest(uri: URI, credentials: RepositoryAuth?): HttpRequest {
        val builder = HttpRequest.newBuilder(uri)
            .GET()
            .timeout(Duration.ofMillis(NetworkTimeouts.REQUEST_MS))
            .header(HEADER_USER_AGENT, USER_AGENT)
        credentials?.requestHeaders()?.forEach { (name, value) ->
            if (name.isBlank() || value.isBlank()) return@forEach
            runCatching { builder.header(name, value) }
                .onFailure { logger.warn("Пропущен некорректный заголовок авторизации") }
        }
        return builder.build()
    }

    private fun mapResponse(
        url: String,
        response: HttpResponse<ByteArray>,
        elapsedMs: Long,
    ): HttpGetResult {
        val status = response.statusCode()
        val bodyBytes = response.body()
        if (bodyBytes.size > MAX_BODY_BYTES) {
            return HttpGetResult.Failure(HttpFailure.Unreachable(url, "body_too_large"), elapsedMs)
        }
        val body = String(bodyBytes, StandardCharsets.UTF_8)
        return when {
            status in SUCCESS_MIN..SUCCESS_MAX -> HttpGetResult.Success(body, status, elapsedMs)
            status == 401 -> HttpGetResult.Failure(HttpFailure.Unauthorized(url, hostFromUrl(url)), elapsedMs)
            status == 403 -> HttpGetResult.Failure(HttpFailure.Forbidden(url, hostFromUrl(url)), elapsedMs)
            status == 404 -> HttpGetResult.Failure(HttpFailure.NotFound(url), elapsedMs)
            else -> HttpGetResult.Failure(HttpFailure.HttpStatus(url, status), elapsedMs)
        }
    }

    private fun timeoutResult(url: String, elapsedMs: Long): HttpGetResult.Failure {
        logger.warn("Timeout ${NetworkTimeouts.REQUEST_MS / NetworkTimeouts.MILLIS_PER_SECOND}s for ${hostFromUrl(url)}")
        return HttpGetResult.Failure(
            HttpFailure.Timeout(url, NetworkTimeouts.REQUEST_MS),
            elapsedMs,
        )
    }
}

private const val HEADER_USER_AGENT = "User-Agent"
private const val USER_AGENT = "LibsHelper/1.0 (IntelliJ plugin)"
private const val MAX_BODY_BYTES = 2_000_000
private const val SUCCESS_MIN = 200
private const val SUCCESS_MAX = 299
