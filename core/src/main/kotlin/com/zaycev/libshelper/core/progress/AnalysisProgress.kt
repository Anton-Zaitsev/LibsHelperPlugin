package com.zaycev.libshelper.core.progress

data class AnalysisProgress(
    val currentCoordinates: String?,
    val currentHost: String?,
    val completed: Int,
    val total: Int,
    val lastOutcome: String?,
    val lastDurationMs: Long?,
    val usingCache: Boolean,
)
