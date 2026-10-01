package com.zaycev.libshelper.mcp

import com.zaycev.libshelper.core.utils.rethrowIfCancelled
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicInt
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class BearerCredentialCheck(
    private val token: String,
) : McpCredentialCheck {
    override fun accepts(authorization: String?): Boolean {
        if (token.isEmpty() || authorization == null) return false
        val expected = "Bearer $token".toByteArray(Charsets.UTF_8)
        val actual = authorization.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(expected, actual)
    }
}

class WindowRequestGate(
    private val limits: McpLimits,
) : McpRequestGate {
    private val inFlight = AtomicInt(0)
    private val windows = ConcurrentHashMap<String, Window>()

    override fun admit(remote: String, nowMs: Long): GateDecision {
        if (!reserveFlight()) return GateDecision.Busy
        prune(nowMs)
        val window = windows.compute(remote) { _, existing ->
            if (existing == null || nowMs - existing.start >= limits.windowMs) {
                Window(nowMs, AtomicInt(1))
            } else {
                existing.count.fetchAndAdd(1)
                existing
            }
        }
        if (window == null || window.count.load() > limits.maxRequestsPerWindow) {
            release()
            return GateDecision.RateLimited
        }
        return GateDecision.Allow
    }

    override fun release() {
        while (true) {
            val current = inFlight.load()
            if (current <= 0) return
            if (inFlight.compareAndSet(current, current - 1)) return
        }
    }

    private fun reserveFlight(): Boolean {
        while (true) {
            val current = inFlight.load()
            if (current >= limits.maxInFlight) return false
            if (inFlight.compareAndSet(current, current + 1)) return true
        }
    }

    private fun prune(nowMs: Long) {
        if (windows.size <= MAX_REMOTES) return
        val stale = windows.entries.filter { nowMs - it.value.start >= limits.windowMs }.map { it.key }
        stale.forEach { windows.remove(it) }
    }

    private class Window(val start: Long, val count: AtomicInt)
}

class BudgetConfig {
    var gate: McpRequestGate = WindowRequestGate(McpLimits())
    var limits: McpLimits = McpLimits()
}

class BearerAuthConfig {
    var auth: McpCredentialCheck = BearerCredentialCheck("")
}

private const val RETRY_AFTER_SECONDS = "1"
private const val MAX_REMOTES = 64

private fun bodyTooLarge(call: io.ktor.server.application.ApplicationCall, maxBodyBytes: Int): Boolean {
    val raw = call.request.headers[HttpHeaders.ContentLength] ?: return true
    val length = raw.toLongOrNull() ?: return true
    return length > maxBodyBytes
}

val RequestBudget = createApplicationPlugin("RequestBudget", ::BudgetConfig) {
    val gate = pluginConfig.gate
    val limits = pluginConfig.limits
    application.intercept(ApplicationCallPipeline.Plugins) {
        val decision = gate.admit(call.request.local.remoteHost, System.currentTimeMillis())
        if (decision != GateDecision.Allow) {
            val status = if (decision == GateDecision.RateLimited) {
                call.response.headers.append(HttpHeaders.RetryAfter, RETRY_AFTER_SECONDS)
                HttpStatusCode.TooManyRequests
            } else {
                HttpStatusCode.ServiceUnavailable
            }
            val code = if (decision == GateDecision.RateLimited) "rate_limited" else "busy"
            call.respondFailure(status, McpFailureBody(code, code))
            finish()
            return@intercept
        }
        try {
            if (call.request.local.method == HttpMethod.Post && bodyTooLarge(call, limits.maxBodyBytes)) {
                call.respondFailure(HttpStatusCode.PayloadTooLarge, McpFailureBody("too_large", "body"))
                finish()
                return@intercept
            }
            try {
                withTimeout(limits.requestTimeoutMs) { proceed() }
            } catch (_: TimeoutCancellationException) {
                if (!call.response.isCommitted) {
                    call.respondFailure(HttpStatusCode.GatewayTimeout, McpFailureBody("timeout", "deadline"))
                }
            }
        } finally {
            gate.release()
        }
    }
}

val BearerAuth = createApplicationPlugin("BearerAuth", ::BearerAuthConfig) {
    val auth = pluginConfig.auth
    application.intercept(ApplicationCallPipeline.Plugins) {
        if (auth.accepts(call.request.headers[HttpHeaders.Authorization])) return@intercept
        call.respondFailure(HttpStatusCode.Unauthorized, McpFailureBody("unauthorized", "bearer"))
        finish()
    }
}

fun Application.installMcpErrors() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            if (cause is TimeoutCancellationException) return@exception
            cause.rethrowIfCancelled()
            if (call.response.isCommitted) return@exception
            call.respondText(
                McpFailureBody("internal", "request failed").toJson(),
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondFailure(
    status: HttpStatusCode,
    body: McpFailureBody,
) {
    respondText(body.toJson(), ContentType.Application.Json, status)
}

private fun McpFailureBody.toJson(): String = """{"code":"$code","message":"$message"}"""
