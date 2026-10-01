package com.zaycev.libshelper.ide.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.zaycev.libshelper.core.model.ProjectReport
import com.zaycev.libshelper.ide.AdvisorListener
import com.zaycev.libshelper.ide.AdvisorUiState
import com.zaycev.libshelper.ide.LibsHelperService
import com.zaycev.libshelper.ide.auth.LibsHelperConfigurable
import com.zaycev.libshelper.ide.i18n.AppLanguage
import com.zaycev.libshelper.ide.i18n.LibsHelperSettings
import com.zaycev.libshelper.ide.i18n.msg
import com.zaycev.libshelper.ide.ui.StatusKind
import com.zaycev.libshelper.ide.ui.catalogParseBody
import com.zaycev.libshelper.ide.ui.errorByKind
import com.zaycev.libshelper.ide.ui.openModuleGradle
import com.zaycev.libshelper.ide.ui.progressOutcome
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

private enum class AppTab { Updates, Libraries, Analytics, Sources }

private val CompactWidth = 420.dp
private val WideWidth = 800.dp

@Composable
fun LibsHelperApp(project: Project, modifier: Modifier = Modifier) {
    val service = remember(project) { project.service<LibsHelperService>() }
    var state by remember { mutableStateOf(service.state) }
    DisposableEffect(service) {
        val listener = AdvisorListener { next ->
            service.launchEdt { state = next }
        }
        service.addListener(listener)
        onDispose { service.removeListener(listener) }
    }
    val background = JewelTheme.globalColors.panelBackground
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(background),
    ) {
        val compact = maxWidth < CompactWidth
        val wide = maxWidth >= WideWidth
        Column(Modifier.fillMaxSize()) {
            HeaderBar(
                compact = compact,
                onRefresh = { service.refresh(forceRefresh = false) },
                onRescan = { service.refresh(forceRefresh = true) },
                onSettings = {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, LibsHelperConfigurable::class.java)
                },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val current = state) {
                    AdvisorUiState.Idle, is AdvisorUiState.Loading -> LoadingPane(current as? AdvisorUiState.Loading)
                    is AdvisorUiState.Error -> ErrorPane(current, onRetry = { service.refresh(forceRefresh = true) })
                    is AdvisorUiState.Ready -> ReadyPane(
                        project = project,
                        service = service,
                        report = current.report,
                        compact = compact,
                        wide = wide,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderBar(
    compact: Boolean,
    onRefresh: () -> Unit,
    onRescan: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = LibsHelperSettings.getInstance().language
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ToolbarAction(compact, msg("toolbar.refresh"), AllIconsKeys.Actions.Refresh, onClick = onRefresh)
            ToolbarAction(compact, msg("toolbar.rescan"), AllIconsKeys.Actions.Restart, onClick = onRescan)
            org.jetbrains.jewel.ui.component.OutlinedButton(
                onClick = {
                    val next = if (language == AppLanguage.Russian) AppLanguage.English else AppLanguage.Russian
                    LibsHelperSettings.getInstance().setLanguage(next)
                },
            ) {
                Text(language.nativeName)
            }
            ToolbarAction(compact, msg("toolbar.settings"), AllIconsKeys.General.Settings, onClick = onSettings)
        }
    }
}

@Composable
private fun LoadingPane(
    loading: AdvisorUiState.Loading?,
    modifier: Modifier = Modifier,
) {
    val plan = loading?.plan
    val progress = loading?.progress
    Column(
        modifier.padding(24.dp, 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MutedText(if (plan == null) msg("scan.reading") else msg("scan.fetching"))
        HeroVersion(
            when {
                progress != null && progress.total > 0 -> "${progress.completed} / ${progress.total}"
                else -> "…"
            },
        )
        TitleText(
            progress?.currentCoordinates
                ?.substringAfter(':')
                ?.ifBlank { progress.currentCoordinates }
                ?: if (plan == null) msg("scan.inventory") else msg("scan.fetching"),
        )
        if (progress != null && progress.total > 0) {
            ProgressTrack(progress.completed, progress.total)
        } else {
            IndeterminateTrack()
        }
        val detail = buildList {
            progress?.let { item ->
                progressOutcome(item.lastOutcome).takeIf { it.isNotBlank() }?.let(::add)
                item.currentHost?.let(::add)
            } ?: plan?.let { item ->
                add(msg("scan.libraries", item.dependencyCount))
                item.httpProxy?.let { add(it.host) }
            }
        }.joinToString(" · ")
        if (detail.isNotBlank()) {
            MutedText(detail)
        }
    }
}

@Composable
private fun ErrorPane(
    state: AdvisorUiState.Error,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.padding(24.dp, 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TitleText(msg("error.title"))
        Text(errorByKind(state.kind, state.message), color = ComposePalette.danger())
        DefaultButton(onClick = onRetry) {
            Text(msg("error.retry"))
        }
    }
}

@Composable
private fun ReadyPane(
    project: Project,
    service: LibsHelperService,
    report: ProjectReport,
    compact: Boolean,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(AppTab.Updates) }
    val searchState = rememberTextFieldState()
    var outdatedOnly by remember { mutableStateOf(false) }
    var selectedKey by remember { mutableStateOf(service.selectedKey) }
    var graphFullscreen by remember { mutableStateOf(false) }
    DisposableEffect(service) {
        val listener = AdvisorListener {
            service.launchEdt {
                service.consumeListQuery()?.let { query ->
                    searchState.edit { replace(0, length, query) }
                    outdatedOnly = false
                }
                selectedKey = service.selectedKey
                if (service.consumeUpdatesTabRequest()) {
                    tab = AppTab.Updates
                }
            }
        }
        service.addListener(listener)
        onDispose { service.removeListener(listener) }
    }
    Box(modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.One -> {
                        tab = AppTab.Updates
                        true
                    }
                    Key.Two -> {
                        tab = AppTab.Libraries
                        true
                    }
                    Key.Three -> {
                        tab = AppTab.Analytics
                        true
                    }
                    Key.Four -> {
                        tab = AppTab.Sources
                        true
                    }
                    else -> false
                }
            },
    ) {
        StatusBanners(report, compact)
        NavigationBar(tab, onTab = { tab = it })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                AppTab.Updates -> UpdatesScreen(
                    project = project,
                    report = report,
                    compact = compact,
                    wide = wide,
                    searchState = searchState,
                    outdatedOnly = outdatedOnly,
                    onStaleOnly = { outdatedOnly = it },
                    selectedKey = selectedKey,
                    onSelect = { key ->
                        selectedKey = key
                        service.selectedKey = key
                    },
                    onApply = { dependency, version -> service.enqueueApply(dependency, version) },
                )
                AppTab.Libraries -> LibrariesScreen(report, wide, onOpenLibrary = { key ->
                    service.selectLibrary(key)
                    tab = AppTab.Updates
                })
                AppTab.Analytics -> AnalyticsScreen(
                    project = project,
                    report = report,
                    wide = wide,
                    onOpenLibrary = { key ->
                        service.selectLibrary(key)
                        tab = AppTab.Updates
                    },
                    onOpenFullscreenGraph = { graphFullscreen = true },
                    onOpenModule = { moduleId -> openModuleGradle(project, moduleId) },
                )
                AppTab.Sources -> SourcesScreen(project, report, wide)
            }
        }
    }
        if (graphFullscreen && report.moduleMap.nodes.isNotEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(JewelTheme.globalColors.panelBackground)
                    .padding(8.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || event.key != Key.Escape) {
                            return@onPreviewKeyEvent false
                        }
                        graphFullscreen = false
                        true
                    },
            ) {
                ModuleGraphView(
                    project = project,
                    map = report.moduleMap,
                    fullscreen = true,
                    onToggleFullscreen = { graphFullscreen = false },
                    onOpenModule = { moduleId -> openModuleGradle(project, moduleId) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun NavigationBar(
    tab: AppTab,
    onTab: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ComposePalette.cardFill())
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NavTab(msg("tab.updates"), tab == AppTab.Updates, ComposePalette.tabUpdates(), onClick = {
            onTab(AppTab.Updates)
        })
        NavTab(msg("tab.libraries"), tab == AppTab.Libraries, ComposePalette.tabLibraries(), onClick = {
            onTab(AppTab.Libraries)
        })
        NavTab(msg("tab.analytics"), tab == AppTab.Analytics, ComposePalette.tabAnalytics(), onClick = {
            onTab(AppTab.Analytics)
        })
        NavTab(msg("tab.sources"), tab == AppTab.Sources, ComposePalette.tabSources(), onClick = {
            onTab(AppTab.Sources)
        })
    }
}

@Composable
private fun StatusBanners(
    report: ProjectReport,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!report.inventory.catalogPresent) {
            AccentCard(ComposePalette.status(StatusKind.Outdated)) {
                TitleText(msg("banner.noCatalog.title"))
                if (!compact) {
                    MutedText(noCatalogDetails(report))
                }
            }
        } else if (report.inventory.catalogError != null) {
            AccentCard(ComposePalette.danger()) {
                TitleText(msg("banner.catalogParse.title"))
                if (!compact) {
                    MutedText(catalogParseBody(report.inventory.catalogError.orEmpty()))
                }
            }
        }
        if (report.servedFromCache) {
            AccentCard(ComposePalette.muted()) {
                TitleText(msg("banner.cached.title"))
                if (!compact) {
                    MutedText(msg("banner.cached.body"))
                }
            }
        }
        if (report.metadataFromProxyOnly) {
            AccentCard(ComposePalette.status(StatusKind.Rc)) {
                TitleText(msg("banner.proxyOnly"))
            }
        }
    }
}

private fun noCatalogDetails(report: ProjectReport): String {
    val aliases = report.inventory.unresolvedCatalogAliases
    val extra = if (aliases.isEmpty()) {
        msg("banner.scriptsOnly")
    } else {
        msg("banner.unresolved", aliases.joinToString { "${it.alias} (${it.module})" })
    }
    return "${msg("banner.noCatalog.body")}\n$extra"
}
