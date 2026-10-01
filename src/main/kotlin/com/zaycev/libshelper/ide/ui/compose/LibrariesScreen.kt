package com.zaycev.libshelper.ide.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zaycev.libshelper.core.model.LibraryAdvice
import com.zaycev.libshelper.core.model.ProjectReport
import com.zaycev.libshelper.core.settings.settingLibraries
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
    val items = remember(report) { report.libraries + settingLibraries(report) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(if (wide) 16.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (items.isEmpty()) {
            item {
                AccentCard(ComposePalette.muted()) {
                    TitleText(msg("empty.noLibraries"))
                    if (!report.inventory.catalogPresent) {
                        MutedText(msg("banner.noCatalog.body"))
                    } else {
                        MutedText(msg("banner.scriptsOnly"))
                    }
                }
            }
        } else {
            items(
                count = items.size,
                key = { index -> items[index].advice.dependency.coordinates.key + index },
            ) { index ->
                LibraryOverviewCard(items[index], onOpenLibrary)
            }
        }
    }
}

private val settingConfigurations = setOf("CompileSdk", "TargetSdk", "Ndk")

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
    val dependency = item.advice.dependency
    val alias = dependency.catalogAlias
    val source = if (alias != null) msg("source.catalog", alias) else dependency.source.name
    val current = item.advice.current?.raw ?: "—"
    val key = if (dependency.configuration in settingConfigurations) {
        alias ?: dependency.coordinates.artifact
    } else {
        dependency.coordinates.key
    }
    AccentCard(accent, modifier = modifier.clickable { onOpenLibrary(key) }) {
        TitleText(key)
        MutedText("$current · ${status.text} · $source")
    }
}
