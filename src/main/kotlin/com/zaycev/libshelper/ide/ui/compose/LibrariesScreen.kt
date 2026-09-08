package com.zaycev.libshelper.ide.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zaycev.libshelper.core.model.LibraryAdvice
import com.zaycev.libshelper.core.model.ProjectReport
import com.zaycev.libshelper.ide.i18n.msg
import com.zaycev.libshelper.ide.ui.StatusKind
import com.zaycev.libshelper.ide.ui.statusOf

@Composable
fun LibrariesScreen(
    report: ProjectReport,
    wide: Boolean,
    onOpenLibrary: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (report.libraries.isEmpty()) {
            AccentCard(ComposePalette.muted()) {
                TitleText(msg("empty.noLibraries"))
                if (!report.inventory.catalogPresent) {
                    MutedText(msg("banner.noCatalog.body"))
                } else {
                    MutedText(msg("banner.scriptsOnly"))
                }
            }
        } else {
            ResponsiveGrid(items = report.libraries, wide = wide) { item ->
                LibraryOverviewCard(item, onOpenLibrary)
            }
        }
    }
}

@Composable
private fun LibraryOverviewCard(
    item: LibraryAdvice,
    onOpenLibrary: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = statusOf(item.advice)
    val accent = if (item.advice.isOutdated) {
        ComposePalette.status(StatusKind.Outdated)
    } else {
        ComposePalette.status(StatusKind.Current)
    }
    val alias = item.advice.dependency.catalogAlias
    val source = if (alias != null) msg("source.catalog", alias) else item.advice.dependency.source.name
    val current = item.advice.current?.raw ?: "—"
    val key = item.advice.dependency.coordinates.key
    AccentCard(accent, modifier = modifier.clickable { onOpenLibrary(key) }) {
        TitleText(key)
        MutedText("$current · ${status.first} · $source")
    }
}
