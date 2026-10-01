package com.zaycev.libshelper.core.graph

import kotlin.test.Test
import kotlin.test.assertFailsWith

class SpatialGridTest {
    @Test
    fun rejectsNonPositiveCell() {
        assertFailsWith<IllegalArgumentException> { SpatialGrid(0f, 0f, 0f) }
        assertFailsWith<IllegalArgumentException> { SpatialGrid(-1f, 0f, 0f) }
    }
}
