package com.zaycev.libshelper.ide

import androidx.compose.runtime.Immutable
import com.zaycev.libshelper.core.model.AdvisorErrorKind
import com.zaycev.libshelper.core.model.ProjectReport
import com.zaycev.libshelper.core.model.ScanPlan
import com.zaycev.libshelper.core.progress.AnalysisProgress

@Immutable
sealed interface AdvisorUiState {
    @Immutable
    data object Idle : AdvisorUiState
    @Immutable
    data class Loading(
        val plan: ScanPlan? = null,
        val progress: AnalysisProgress? = null,
    ) : AdvisorUiState
    @Immutable
    data class Ready(val report: ProjectReport) : AdvisorUiState
    @Immutable
    data class Error(val kind: AdvisorErrorKind, val message: String? = null) : AdvisorUiState
}
