package com.zaycev.libshelper.core.inlay

import kotlin.math.max
import kotlin.math.roundToInt

data class BadgeLayout(
    val width: Int,
    val height: Int,
    val dotX: Int,
    val dotY: Int,
    val textX: Int,
    val textBaseline: Int,
)

fun layoutBadge(
    textWidth: Int,
    ascent: Int,
    descent: Int,
    dot: Int,
    padH: Int,
    padV: Int,
    gap: Int,
    leading: Int,
): BadgeLayout {
    val inner = max(dot, ascent + descent)
    val height = inner + padV * 2
    val mid = height / 2.0
    val textX = leading + padH + dot + gap
    return BadgeLayout(
        width = textX + textWidth + padH,
        height = height,
        dotX = leading + padH,
        dotY = (mid - dot / 2.0).roundToInt(),
        textX = textX,
        textBaseline = (mid + (ascent - descent) / 2.0).roundToInt(),
    )
}
