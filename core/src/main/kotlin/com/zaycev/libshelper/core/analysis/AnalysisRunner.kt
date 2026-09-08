package com.zaycev.libshelper.core.analysis

import com.zaycev.libshelper.core.model.ProjectReport
import com.zaycev.libshelper.core.model.ScanPlan
import com.zaycev.libshelper.core.progress.AnalysisProgress
import java.nio.file.Path

interface AnalysisRunner {
    fun invalidate()

    fun rememberReport(report: ProjectReport)

    suspend fun analyze(
        root: Path,
        forceRefresh: Boolean,
        onPlan: (ScanPlan) -> Unit = {},
        onProgress: (AnalysisProgress) -> Unit = {},
    ): ProjectReport
}
