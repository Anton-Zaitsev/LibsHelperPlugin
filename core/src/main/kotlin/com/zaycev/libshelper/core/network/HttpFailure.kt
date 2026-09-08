package com.zaycev.libshelper.core.network

sealed interface HttpFailure {
    val url: String
    val userMessage: String

    data class Timeout(
        override val url: String,
        val timeoutMs: Long,
        override val userMessage: String = "timeout:${timeoutMs / NetworkTimeouts.MILLIS_PER_SECOND}",
    ) : HttpFailure

    data class Unauthorized(
        override val url: String,
        val host: String,
        override val userMessage: String = "unauthorized:$host",
    ) : HttpFailure

    data class Forbidden(
        override val url: String,
        val host: String = "",
        override val userMessage: String = "forbidden",
    ) : HttpFailure

    data class NotFound(
        override val url: String,
        override val userMessage: String = "not_found",
    ) : HttpFailure

    data class Unreachable(
        override val url: String,
        override val userMessage: String,
    ) : HttpFailure

    data class HttpStatus(
        override val url: String,
        val status: Int,
        override val userMessage: String = "http:$status",
    ) : HttpFailure
}
