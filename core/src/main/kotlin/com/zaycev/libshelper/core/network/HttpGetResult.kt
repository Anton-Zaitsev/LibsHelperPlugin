package com.zaycev.libshelper.core.network

sealed interface HttpGetResult {
    val durationMs: Long

    data class Success(
        val body: String,
        val status: Int,
        override val durationMs: Long,
        val fromCache: Boolean = false,
    ) : HttpGetResult

    data class Failure(
        val error: HttpFailure,
        override val durationMs: Long,
    ) : HttpGetResult
}
