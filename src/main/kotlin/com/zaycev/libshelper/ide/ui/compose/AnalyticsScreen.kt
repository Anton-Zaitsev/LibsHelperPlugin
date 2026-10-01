package com.zaycev.libshelper.ide.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import com.zaycev.libshelper.core.analytics.DefaultAnalyticsComputer
import com.zaycev.libshelper.core.analytics.DefaultSunburstLayout
import com.zaycev.libshelper.core.analytics.LibraryStat
import com.zaycev.libshelper.core.analytics.ProjectAnalytics
import com.zaycev.libshelper.core.analytics.analyticsInput
import com.zaycev.libshelper.core.model.ProjectReport
import com.zaycev.libshelper.ide.i18n.msg
import com.zaycev.libshelper.ide.ui.StatusKind
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.jetbrains.jewel.ui.component.ExternalLink
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

@Composable
fun AnalyticsScreen(
    project: Project,
    report: ProjectReport,
    wide: Boolean,
    onOpenLibrary: (String) -> Unit,
    onOpenFullscreenGraph: () -> Unit,
    onOpenModule: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val analytics = remember(report) {
        if (report.analytics === ProjectAnalytics.Empty) {
            DefaultAnalyticsComputer().compute(report.analyticsInput())
        } else {
            report.analytics
        }
    }
    var expanded by remember(report.fingerprint) { mutableStateOf(false) }
    val chartLibraries = if (expanded) analytics.libraries else analytics.thirdParty
    val sunburst = remember(chartLibraries, expanded) {
        DefaultSunburstLayout().layout(chartLibraries, msg("analytics.sunburst.other"))
    }
    val ranked = remember(chartLibraries) { chartLibraries.take(WEIGHT_LIMIT).toPersistentList() }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(if (wide) 16.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (analytics.libraries.isEmpty()) {
            item(key = "empty") { MutedText(msg("analytics.empty")) }
        } else {
            item(key = "third-party") { MutedText(msg("analytics.thirdParty", analytics.skippedFirstParty)) }
            item(key = "metrics") { MetricRow(analytics, wide) }
            item(key = "weight") {
                AccentCard(ComposePalette.tabAnalytics()) {
                    TitleText(msg("analytics.weight.title"))
                    MutedText(msg("analytics.weight.hint"))
                    if (wide) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SunburstChart(
                                data = sunburst,
                                onOpenLibrary = onOpenLibrary,
                                modifier = Modifier.weight(1f),
                            )
                            WeightList(
                                items = ranked,
                                onOpenLibrary = onOpenLibrary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        SunburstChart(data = sunburst, onOpenLibrary = onOpenLibrary)
                        WeightList(items = ranked, onOpenLibrary = onOpenLibrary)
                    }
                    OutlinedButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) msg("analytics.collapse") else msg("analytics.expand"))
                    }
                }
            }
            if (expanded) {
                item(key = "modules-title") { TitleText(msg("analytics.modules.title")) }
                items(items = analytics.libraries, key = { item -> "lib:${item.key}" }) { item ->
                    LibraryInsightCard(item, onOpenLibrary)
                }
                if (analytics.sharedGroups.isNotEmpty()) {
                    item(key = "shared-title") { TitleText(msg("analytics.shared.title")) }
                    items(items = analytics.sharedGroups, key = { group -> "shared:${group.ref}" }) { group ->
                        AccentCard(ComposePalette.status(StatusKind.Rc)) {
                            TitleText(group.ref)
                            MutedText(group.keys.joinToString())
                        }
                    }
                }
            }
        }
        item(key = "graph") {
            AccentCard(ComposePalette.tabLibraries()) {
                TitleText(msg("analytics.graph.title"))
                MutedText(msg("analytics.graph.hint"))
                if (report.moduleMap.nodes.isEmpty()) {
                    MutedText(msg("analytics.graph.empty"))
                } else {
                    Box(Modifier.fillMaxWidth().height(420.dp)) {
                        ModuleGraphView(
                            project = project,
                            map = report.moduleMap,
                            fullscreen = false,
                            onToggleFullscreen = onOpenFullscreenGraph,
                            onOpenModule = onOpenModule,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricRow(analytics: ProjectAnalytics, wide: Boolean, modifier: Modifier = Modifier) {
    val items = persistentListOf(
        MetricTile(msg("analytics.metric.libraries"), analytics.thirdParty.size.toString(), ComposePalette.tabAnalytics()),
        MetricTile(msg("analytics.metric.weight"), analytics.totalWeight.toString(), ComposePalette.tabUpdates()),
        MetricTile(msg("analytics.metric.outdated"), analytics.outdatedCount.toString(), ComposePalette.status(StatusKind.Outdated)),
        MetricTile(msg("analytics.metric.modules"), analytics.moduleCount.toString(), ComposePalette.current()),
    )
    ResponsiveGrid(items = items, wide = wide, modifier = modifier) { item ->
        AccentCard(item.color) {
            MutedText(item.label)
            TitleText(item.value)
        }
    }
}

private data class MetricTile(
    val label: String,
    val value: String,
    val color: Color,
)

@Composable
private fun WeightList(
    items: ImmutableList<LibraryStat>,
    onOpenLibrary: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val maxWeight = items.maxOf { it.weight }.coerceAtLeast(1)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = { onOpenLibrary(item.key) })
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Link(item.artifact, onClick = { onOpenLibrary(item.key) })
                MutedText(msg("analytics.weight.modules", item.weight))
            }
            ProgressTrack(item.weight, maxWeight)
        }
    }
}

@Composable
private fun LibraryInsightCard(
    item: LibraryStat,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when {
        item.isOutdated -> ComposePalette.status(StatusKind.Outdated)
        item.isFirstParty || item.isLocal -> ComposePalette.status(StatusKind.Rc)
        else -> ComposePalette.status(StatusKind.Current)
    }
    AccentCard(accent, modifier) {
        Link(item.key, onClick = { onOpen(item.key) })
        MutedText(
            buildString {
                append(item.current ?: "—")
                append(" · ")
                append(msg("analytics.weight.modules", item.weight))
                val alias = item.catalogAlias
                if (alias != null) append(" · ").append(msg("source.catalog", alias))
                if (item.isFirstParty) append(" · ").append(msg("analytics.firstParty"))
            },
        )
        MutedText(item.modules.joinToString { if (it == ":") msg("badge.root") else it })
        PillRow {
            item.github?.let { url ->
                ExternalLink(text = msg("link.github"), uri = url)
            }
            item.mavenCentral?.let { url ->
                ExternalLink(text = msg("link.mavenCentral"), uri = url)
            }
        }
    }
}

private const val WEIGHT_LIMIT = 8
