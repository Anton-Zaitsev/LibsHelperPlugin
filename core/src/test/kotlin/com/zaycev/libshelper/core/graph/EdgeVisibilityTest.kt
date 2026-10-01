package com.zaycev.libshelper.core.graph

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EdgeVisibilityTest {
    @Test
    fun edgeCrossingTheViewportIsVisibleWhenBothEndsAreOutside() {
        val hits = connectorIntersects(
            fromX = 0f,
            fromY = -400f,
            toX = 0f,
            toY = 400f,
            left = -100f,
            top = -20f,
            right = 100f,
            bottom = 20f,
        )
        assertTrue(hits)
    }

    @Test
    fun sidewaysConnectorBelowTheCardsStillCounts() {
        val hits = connectorIntersects(
            fromX = 0f,
            fromY = 0f,
            toX = 400f,
            toY = 10f,
            left = 100f,
            top = 40f,
            right = 300f,
            bottom = 120f,
        )
        assertTrue(hits)
    }

    @Test
    fun farAwayEdgeIsHidden() {
        assertFalse(
            connectorIntersects(
                fromX = 0f,
                fromY = 0f,
                toX = 10f,
                toY = 200f,
                left = 5000f,
                top = 5000f,
                right = 5200f,
                bottom = 5200f,
            ),
        )
    }
}
