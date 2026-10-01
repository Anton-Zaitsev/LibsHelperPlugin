package com.zaycev.libshelper.core.network

import com.zaycev.libshelper.core.PLUGIN_VERSION
import com.zaycev.libshelper.core.auth.RepositoryAuth
import com.zaycev.libshelper.core.auth.RepositoryAuthenticator
import com.zaycev.libshelper.core.auth.requestHeaders
import com.zaycev.libshelper.core.concurrency.DispatcherProvider
import com.zaycev.libshelper.core.di.AppScope
import com.zaycev.libshelper.core.log.LibsHelperLogger
import com.zaycev.libshelper.core.model.HttpProxySettings
import com.zaycev.libshelper.core.proxy.isPublicCatalogHost
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
import java.net.http.HttpClient
import java.util.concurrent.ConcurrentHashMap
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
    private val authLocks = ConcurrentHashMap<String, Mutex>()
    private val declinedHosts = ConcurrentHashMap.newKeySet<String>()

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
        if (!allowAuthPrompt || host in declinedHosts || !needsAuthFix(first, stored)) return first
        return lockFor(host).withLock {
            if (host in declinedHosts) return@withLock first
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
                if (prompted == null) {
                    declinedHosts += host
                    first
                } else {
                    execute(url, proxy, prompted)
                }
            }
        }
    }

    override fun resetAuthPrompts() {
        declinedHosts.clear()
    }

    private fun lockFor(host: String): Mutex = authLocks.computeIfAbsent(host) { Mutex() }

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
                sendFollowingRedirects(client, uri, credentials, url, ::elapsedMs)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException()
        } catch (_: HttpTimeoutException) {
            timeoutResult(url, elapsedMs())
        } catch (_: UnknownHostException) {
            HttpGetResult.Failure(
                HttpFailure.Unreachable(url, "Не удалось разрешить хост: ${uri.host}"),
                elapsedMs(),
            )
        } catch (error: Exception) {
            logger.warn("Сетевая ошибка ${uri.host}: ${error.javaClass.simpleName}")
            HttpGetResult.Failure(
                HttpFailure.Unreachable(url, error.javaClass.simpleName),
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

    private suspend fun sendFollowingRedirects(
        client: HttpClient,
        start: URI,
        credentials: RepositoryAuth?,
        originalUrl: String,
        elapsedMs: () -> Long,
    ): HttpGetResult {
        var current = stripUserInfo(start)
        var creds = credentials
        var hops = 0
        while (hops <= MAX_REDIRECTS) {
            currentCoroutineContext().ensureActive()
            logger.debug("GET ${current.host}")
            val response = client.send(
                buildRequest(current, credentialsFor(current, creds)),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            val status = response.statusCode()
            if (status in REDIRECT_MIN..REDIRECT_MAX && hops < MAX_REDIRECTS) {
                val location = response.headers().firstValue("location").orElse(null)
                response.body().close()
                if (location == null) {
                    return HttpGetResult.Failure(HttpFailure.HttpStatus(originalUrl, status), elapsedMs())
                }
                val next = stripUserInfo(current.resolve(location))
                if (!shouldKeepCredentials(current, next)) creds = null
                current = next
                hops++
                continue
            }
            val bodyBytes = readLimited(response) ?: return HttpGetResult.Failure(
                HttpFailure.Unreachable(current.toString(), "body_too_large"),
                elapsedMs(),
            )
            return mapResponse(current.toString(), status, bodyBytes, elapsedMs())
        }
        return HttpGetResult.Failure(HttpFailure.Unreachable(originalUrl, "too_many_redirects"), elapsedMs())
    }

    private fun mapResponse(
        url: String,
        status: Int,
        bodyBytes: ByteArray,
        elapsedMs: Long,
    ): HttpGetResult {
        val body = String(bodyBytes, StandardCharsets.UTF_8)
        return when {
            status in SUCCESS_MIN..SUCCESS_MAX -> HttpGetResult.Success(body, status, elapsedMs)
            status == 401 && isPublicCatalogHost(hostFromUrl(url)) ->
                HttpGetResult.Failure(HttpFailure.NotFound(url), elapsedMs)
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
private const val USER_AGENT = "LibsHelper/$PLUGIN_VERSION (IntelliJ plugin)"
private const val MAX_BODY_BYTES = 8_000_000
private const val SUCCESS_MIN = 200
private const val SUCCESS_MAX = 299
private const val REDIRECT_MIN = 300
private const val REDIRECT_MAX = 399
private const val MAX_REDIRECTS = 5

internal fun credentialsFor(uri: URI, credentials: RepositoryAuth?): RepositoryAuth? =
    if (uri.scheme.equals("https", ignoreCase = true)) credentials else null

internal fun shouldKeepCredentials(current: URI, next: URI): Boolean =
    next.scheme.equals("https", ignoreCase = true) && next.host.equals(current.host, ignoreCase = true)

internal fun stripUserInfo(uri: URI): URI {
    if (uri.userInfo == null) return uri
    return URI(uri.scheme, null, uri.host, uri.port, uri.path, uri.query, uri.fragment)
}

private fun readLimited(response: HttpResponse<java.io.InputStream>): ByteArray? {
    val advertised = response.headers().firstValueAsLong("Content-Length")
    if (advertised.isPresent && advertised.asLong > MAX_BODY_BYTES) {
        response.body().close()
        return null
    }
    return response.body().use { input ->
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > MAX_BODY_BYTES) return@use null
            out.write(chunk, 0, read)
        }
        out.toByteArray()
    }
}

private const val READ_CHUNK = 8 * 1024
