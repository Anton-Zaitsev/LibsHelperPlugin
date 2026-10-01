package com.zaycev.libshelper.core.analytics

import kotlinx.collections.immutable.ImmutableList

data class SunburstSlice(
    val id: String,
    val label: String,
    val weight: Int,
    val startDeg: Float,
    val sweepDeg: Float,
    val inner: Float,
    val outer: Float,
    val colorIndex: Int,
    val libraryKey: String?,
)

data class SunburstChartData(
    val slices: ImmutableList<SunburstSlice>,
    val totalWeight: Int,
    val libraryCount: Int,
)
