package com.zaycev.libshelper.core.utils

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CancellationTest {
    @Test
    fun cancellationLeavesTheResultPath() {
        val cancelled = Result.failure<String>(CancellationException("stopped"))
        val thrown = assertFailsWith<CancellationException> { cancelled.rethrowCancellation() }
        assertEquals("stopped", thrown.message)
        val failure = IllegalStateException("broken")
        assertEquals(failure, failure.rethrowIfCancelled())
        assertFailsWith<CancellationException> { CancellationException("halt").rethrowIfCancelled() }
        assertEquals("ok", Result.success("ok").rethrowCancellation().getOrThrow())
    }
}
