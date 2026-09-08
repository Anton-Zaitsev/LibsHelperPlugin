package com.zaycev.libshelper.core.inlay

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BadgeLayoutTest {
    @Test
    fun centersDotAndTextOnTheSameAxis() {
        val ascent = 10
        val descent = 3
        val dot = 8
        val padH = 6
        val leading = 6
        val textWidth = 40
        val layout = layoutBadge(
            textWidth = textWidth,
            ascent = ascent,
            descent = descent,
            dot = dot,
            padH = padH,
            padV = 4,
            gap = 4,
            leading = leading,
        )
        val mid = layout.height / 2.0
        val dotCenter = layout.dotY + dot / 2.0
        val textCenter = layout.textBaseline - (ascent - descent) / 2.0
        assertTrue(abs(mid - dotCenter) <= 1)
        assertTrue(abs(mid - textCenter) <= 1)
        assertEquals(padH, layout.dotX - leading)
        assertEquals(padH, layout.width - (layout.textX + textWidth))
        assertTrue(layout.dotY >= 0)
        assertTrue(layout.dotY + dot <= layout.height)
        assertTrue(layout.textBaseline - ascent >= 0)
        assertTrue(layout.textBaseline + descent <= layout.height)
    }

    @Test
    fun keepsEqualHorizontalPadding() {
        val layout = layoutBadge(
            textWidth = 30,
            ascent = 10,
            descent = 2,
            dot = 8,
            padH = 5,
            padV = 2,
            gap = 4,
            leading = 4,
        )
        assertEquals(4 + 5, layout.dotX)
        assertEquals(layout.dotX + 8 + 4, layout.textX)
        assertEquals(layout.textX + 30 + 5, layout.width)
    }
}
