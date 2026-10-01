package com.zaycev.libshelper.core.utils

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FailureTextTest {
    @Test
    fun describesTheMessageAndCountsIt() {
        val text = FailureText()
        assertEquals("missing", text.describe(IllegalStateException("missing")))
        assertEquals("IllegalArgumentException", text.describe(IllegalArgumentException()))
        assertEquals(2, text.seenCount())
    }

    @Test
    fun cancellationIsNotDescribed() {
        val text = FailureText()
        assertFailsWith<CancellationException> {
            text.describe(CancellationException("stopped"))
        }
        assertEquals(0, text.seenCount())
    }
}
