package com.zaycev.libshelper.core.utils

import kotlin.coroutines.cancellation.CancellationException

@Suppress("NOTHING_TO_INLINE")
inline fun Throwable.rethrowIfCancelled(): Throwable {
    if (this is CancellationException) throw this
    return this
}

@Suppress("NOTHING_TO_INLINE")
inline fun <T> Result<T>.rethrowCancellation(): Result<T> {
    val error = exceptionOrNull() ?: return this
    if (error is CancellationException) throw error
    return this
}
