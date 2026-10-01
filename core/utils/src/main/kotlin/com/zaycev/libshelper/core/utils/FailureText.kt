package com.zaycev.libshelper.core.utils

import kotlin.concurrent.atomics.AtomicInt

class FailureText {
    private val seen = AtomicInt(0)

    fun describe(error: Throwable): String {
        error.rethrowIfCancelled()
        seen.fetchAndAdd(1)
        val message = error.message?.trim().orEmpty()
        return message.ifEmpty { error::class.simpleName.orEmpty() }
    }

    fun seenCount(): Int = seen.load()
}
