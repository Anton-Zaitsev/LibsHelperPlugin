package com.zaycev.libshelper.ide.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.zaycev.libshelper.core.model.ChannelOffer
import com.zaycev.libshelper.core.model.ConflictType
import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.LibraryAdvice
import com.zaycev.libshelper.core.model.LibraryLinks
import com.zaycev.libshelper.core.model.LocalArtifactKind
import com.zaycev.libshelper.core.model.OfferScore
import com.zaycev.libshelper.core.model.ProjectReport
import com.zaycev.libshelper.core.model.UpdateAdvice
import com.zaycev.libshelper.core.model.VersionChannel
import com.zaycev.libshelper.ide.apply.canApplyLibraryVersion
import com.zaycev.libshelper.ide.i18n.msg
import com.zaycev.libshelper.ide.ui.StatusKind
import com.zaycev.libshelper.ide.ui.channelLabel
import com.zaycev.libshelper.ide.ui.conflictText
import com.zaycev.libshelper.ide.ui.consequenceDetail
import com.zaycev.libshelper.ide.ui.lookupErrorText
import com.zaycev.libshelper.ide.ui.openDependencyCatalog
import com.zaycev.libshelper.ide.ui.openDependencyUsage
import com.zaycev.libshelper.ide.ui.originText
import com.zaycev.libshelper.ide.ui.scoreLabel
import com.zaycev.libshelper.ide.ui.statusOf
import java.awt.datatransfer.StringSelection
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.ExternalLink
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList

@Composable
fun UpdatesScreen(
    project: Project,
    report: ProjectReport,
    compact: Boolean,
    wide: Boolean,
    searchState: TextFieldState,
    outdatedOnly: Boolean,
    onStaleOnly: (Boolean) -> Unit,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    onApply: (DeclaredDependency, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val query = searchState.text.toString()
    val items = remember(report, query, outdatedOnly) {
        report.libraries.filter { item ->
            val outdatedOk = !outdatedOnly || item.advice.isOutdated
            val needle = query.trim().lowercase()
            val matches = needle.isEmpty() ||
                item.advice.dependency.coordinates.key.lowercase().contains(needle) ||
                item.advice.dependency.catalogAlias.orEmpty().lowercase().contains(needle)
            outdatedOk && matches
        }.toPersistentList()
    }
    val selected = items.firstOrNull { it.advice.dependency.coordinates.key == selectedKey }
        ?: items.firstOrNull()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(selected?.advice?.dependency?.coordinates?.key) {
        val index = items.indexOfFirst {
            it.advice.dependency.coordinates.key == selected?.advice?.dependency?.coordinates?.key
        }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    fun moveSelection(delta: Int) {
        if (items.isEmpty()) return
        val current = items.indexOfFirst {
            it.advice.dependency.coordinates.key == selected?.advice?.dependency?.coordinates?.key
        }.coerceAtLeast(0)
        val next = (current + delta).coerceIn(0, items.lastIndex)
        onSelect(items[next].advice.dependency.coordinates.key)
    }
    fun applyRecommended() {
        val item = selected ?: return
        val version = heroOffer(item.advice)?.first?.version?.raw ?: return
        onApply(item.advice.dependency, version)
    }
    if (compact) {
        Column(modifier.fillMaxSize()) {
            UpdatesLibraryList(
                items = items,
                report = report,
                searchState = searchState,
                outdatedOnly = outdatedOnly,
                selected = selected,
                listState = listState,
                focusRequester = focusRequester,
                modifier = Modifier.fillMaxWidth().weight(0.42f),
                onStaleOnly = onStaleOnly,
                onMove = ::moveSelection,
                onApplyHero = ::applyRecommended,
                onSelect = onSelect,
            )
            UpdatesDetailPane(
                project = project,
                selected = selected,
                report = report,
                compact = compact,
                wide = wide,
                modifier = Modifier.fillMaxWidth().weight(0.58f),
                onApply = onApply,
            )
        }
    } else {
        Row(modifier.fillMaxSize()) {
            UpdatesLibraryList(
                items = items,
                report = report,
                searchState = searchState,
                outdatedOnly = outdatedOnly,
                selected = selected,
                listState = listState,
                focusRequester = focusRequester,
                modifier = Modifier
                    .width(if (wide) 320.dp else 280.dp)
                    .fillMaxHeight(),
                onStaleOnly = onStaleOnly,
                onMove = ::moveSelection,
                onApplyHero = ::applyRecommended,
                onSelect = onSelect,
            )
            UpdatesDetailPane(
                project = project,
                selected = selected,
                report = report,
                compact = compact,
                wide = wide,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onApply = onApply,
            )
        }
    }
}

@Composable
private fun UpdatesLibraryList(
    items: ImmutableList<LibraryAdvice>,
    report: ProjectReport,
    searchState: TextFieldState,
    outdatedOnly: Boolean,
    selected: LibraryAdvice?,
    listState: LazyListState,
    focusRequester: FocusRequester,
    onStaleOnly: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onApplyHero: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .padding(12.dp)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        onMove(1)
                        true
                    }
                    Key.DirectionUp -> {
                        onMove(-1)
                        true
                    }
                    Key.Enter -> {
                        onApplyHero()
                        true
                    }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextField(
            state = searchState,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(msg("filter.search")) },
        )
        CheckboxRow(
            text = msg("filter.outdated"),
            checked = outdatedOnly,
            onCheckedChange = onStaleOnly,
        )
        MutedText(msg("keys.hint"))
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (items.isEmpty()) {
                item {
                    MutedText(
                        when {
                            report.libraries.isEmpty() && !report.inventory.catalogPresent ->
                                msg("banner.noCatalog.body")
                            report.libraries.isEmpty() -> msg("empty.noLibraries")
                            else -> msg("filter.empty")
                        },
                    )
                }
            } else {
                items(items, key = { it.advice.dependency.coordinates.key }) { item ->
                    LibraryRow(
                        item = item,
                        selected = item.advice.dependency.coordinates.key ==
                            selected?.advice?.dependency?.coordinates?.key,
                        onClick = {
                            onSelect(item.advice.dependency.coordinates.key)
                            focusRequester.requestFocus()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdatesDetailPane(
    project: Project,
    selected: LibraryAdvice?,
    report: ProjectReport,
    compact: Boolean,
    wide: Boolean,
    onApply: (DeclaredDependency, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected == null) {
        Column(modifier.padding(24.dp)) {
            MutedText(msg("filter.empty"))
        }
        return
    }
    val scroll = rememberScrollState()
    LibraryDetail(
        project = project,
        item = selected,
        report = report,
        compact = compact,
        wide = wide,
        onApply = onApply,
        modifier = modifier
            .verticalScroll(scroll)
            .padding(16.dp)
            .fillMaxWidth(),
    )
}

@Composable
private fun LibraryRow(
    item: LibraryAdvice,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = statusOf(item.advice)
    val tone = ComposePalette.status(status.second)
    val bg = if (selected) ComposePalette.tabUpdates().copy(alpha = 0.18f) else ComposePalette.cardFill()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .height(IntrinsicSize.Min),
    ) {
        Spacer(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(tone),
        )
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            TitleText("${item.advice.dependency.coordinates.artifact}  ${item.advice.current?.raw ?: "—"}")
            MutedText(
                "${item.advice.dependency.coordinates.group} · ${status.first}",
                Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun LibraryDetail(
    project: Project,
    item: LibraryAdvice,
    report: ProjectReport,
    compact: Boolean,
    wide: Boolean,
    onApply: (DeclaredDependency, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val advice = item.advice
    val dep = advice.dependency
    val status = statusOf(advice)
    var detailsOpen by remember(dep.coordinates.key) { mutableStateOf(!compact) }
    val siblings = remember(report.inventory.dependencies, dep.coordinates.key) {
        report.inventory.dependencies.filter { it.coordinates.key == dep.coordinates.key }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TitleText(dep.coordinates.key, Modifier.padding(end = 8.dp))
        PillRow {
            val moduleLabel = if (dep.module == ":") msg("badge.root") else dep.module
            Link(moduleLabel, onClick = { openDependencyUsage(project, dep) })
            MutedText(dep.configuration)
            dep.catalogAlias?.let { alias ->
                Link(msg("source.catalog", alias), onClick = { openDependencyCatalog(project, dep) })
            }
            dep.localFileName?.let { MutedText(it) }
        }
        PillRow {
            Pill(status.first, ComposePalette.status(status.second))
            Pill(advice.current?.raw ?: msg("badge.noVersion"), ComposePalette.current())
            if (report.metadataFromProxyOnly) {
                Pill(msg("badge.backup"), ComposePalette.status(StatusKind.Rc))
            } else {
                Pill(msg("badge.official"), ComposePalette.status(StatusKind.Current))
            }
            if (dep.isLocalArtifact) {
                Pill(
                    if (dep.localKind == LocalArtifactKind.Aar) msg("badge.local.aar") else msg("badge.local.jar"),
                    ComposePalette.status(StatusKind.Outdated),
                )
            }
        }
        val conflicts = (advice.preferredStable?.conflicts
            ?: listOfNotNull(advice.latestRc, advice.latestBeta, advice.latestAlpha).flatMap { it.conflicts })
            .distinctBy { it.type to it.args }
            .filter { it.type != ConflictType.RepositoryGap || !report.metadataFromProxyOnly }
        conflicts.forEach { conflict ->
            Text(
                text = conflictText(conflict),
                color = if (conflict.type == ConflictType.SharedVersionRef) {
                    ComposePalette.status(StatusKind.Outdated)
                } else {
                    ComposePalette.danger()
                },
            )
        }
        val hero = heroOffer(advice)
        if (hero != null) {
            VersionCard(
                item = item,
                offer = hero.first,
                title = hero.second,
                showScore = false,
                applyLabel = msg("action.apply"),
                onApply = onApply,
            )
        } else {
            CurrentCard(advice)
        }
        val variants = listOfNotNull(advice.latestRc, advice.latestBeta, advice.latestAlpha)
            .filter { it.version.raw != hero?.first?.version?.raw }
            .toPersistentList()
        if (variants.isNotEmpty()) {
            OtherVersions(item, variants, compact, wide, onApply)
        }
        Link(
            text = if (detailsOpen) msg("details.less") else msg("details.more"),
            onClick = { detailsOpen = !detailsOpen },
        )
        if (detailsOpen) {
            if (!report.metadataFromProxyOnly) {
                MutedText(originText(hero?.first?.origin?.kind ?: advice.latestRc?.origin?.kind))
            }
            lookupErrorText(item.lookupError)?.let { MutedText(it) }
            TitleText(msg("analytics.modules.title"))
            MutedText(
                siblings.map { entry ->
                    val module = if (entry.module == ":") msg("badge.root") else entry.module
                    "$module · ${entry.configuration}"
                }.distinct().joinToString("\n").ifBlank { dep.module },
            )
            LinksRow(item.links)
        }
    }
}

internal fun heroOffer(advice: UpdateAdvice): Pair<ChannelOffer, String>? {
    if (!advice.isOutdated) return null
    advice.preferredStable?.let { return it to msg("offer.recommended") }
    val onChannel = when (advice.currentChannel) {
        VersionChannel.ReleaseCandidate -> advice.latestRc
        VersionChannel.Beta -> advice.latestBeta
        VersionChannel.Alpha, VersionChannel.Snapshot -> advice.latestAlpha
        else -> null
    }
    return onChannel?.let { it to msg("offer.newer") }
}

@Composable
private fun CurrentCard(advice: UpdateAdvice, modifier: Modifier = Modifier) {
    val channel = advice.currentChannel
    val tone = channel?.let { ComposePalette.channel(it) } ?: ComposePalette.status(StatusKind.Current)
    val title = when (channel) {
        VersionChannel.Alpha -> msg("offer.current.alpha")
        VersionChannel.Beta -> msg("offer.current.beta")
        VersionChannel.ReleaseCandidate -> msg("offer.current.rc")
        VersionChannel.Snapshot -> msg("offer.current.snapshot")
        else -> msg("offer.current.stable")
    }
    val hint = when (channel) {
        VersionChannel.Alpha, VersionChannel.Beta, VersionChannel.ReleaseCandidate, VersionChannel.Snapshot ->
            msg("offer.current.prerelease.hint")
        else -> msg("consequence.stable.detail")
    }
    AccentCard(tone, modifier) {
        MutedText(title)
        Pill(channelLabel(channel ?: VersionChannel.Stable), tone)
        HeroVersion(advice.current?.raw ?: "—")
        HintBlock(hint)
    }
}

@Composable
private fun VersionCard(
    item: LibraryAdvice,
    offer: ChannelOffer,
    title: String,
    showScore: Boolean,
    applyLabel: String,
    onApply: (DeclaredDependency, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val consequence = offer.consequences.firstOrNull {
        it.id.name != "DataFromProxy" && it.id.name != "LocalArtifact"
    }
    val shared = offer.conflicts.any { it.type == ConflictType.SharedVersionRef }
    val current = item.advice.current?.raw
    AccentCard(ComposePalette.channel(offer.channel), modifier) {
        MutedText(title)
        PillRow {
            Pill(channelLabel(offer.channel), ComposePalette.channel(offer.channel))
            if (showScore && offer.score != OfferScore.Recommended) {
                Pill(scoreLabel(offer.score), ComposePalette.score(offer.score))
            }
        }
        HeroVersion(offer.version.raw)
        if (current != null && current != offer.version.raw) {
            MutedText(msg("offer.fromTo", current, offer.version.raw))
        }
        if (consequence != null) {
            HintBlock(consequenceDetail(consequence))
        }
        if (shared) {
            HintBlock(msg("action.apply.shared"))
        }
        if (canApplyLibraryVersion(item.advice.dependency)) {
            DefaultButton(
                onClick = {
                    onApply(item.advice.dependency, offer.version.raw)
                },
            ) {
                Text(applyLabel)
            }
        }
    }
}

@Composable
private fun HintBlock(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ComposePalette.noteFill())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = ComposePalette.muted(),
            style = JewelTheme.defaultTextStyle.copy(
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun OtherVersions(
    item: LibraryAdvice,
    variants: ImmutableList<ChannelOffer>,
    compact: Boolean,
    wide: Boolean,
    onApply: (DeclaredDependency, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(!compact) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TitleText(msg("offer.prereleases"))
        MutedText(msg("offer.prereleases.hint"))
        if (compact) {
            Link(
                text = if (expanded) msg("offer.collapse") else msg("offer.expand"),
                onClick = { expanded = !expanded },
            )
        }
        if (expanded) {
            ResponsiveGrid(items = variants, wide = wide) { offer ->
                VersionCard(
                    item = item,
                    offer = offer,
                    title = channelLabel(offer.channel),
                    showScore = true,
                    applyLabel = msg("action.apply.preview"),
                    onApply = onApply,
                )
            }
        }
    }
}

@Composable
private fun LinksRow(links: LibraryLinks, modifier: Modifier = Modifier) {
    PillRow(modifier) {
        ExternalLink(text = msg("link.mavenCentral"), uri = links.mavenCentral)
        ExternalLink(text = msg("link.mvnrepository"), uri = links.mvnRepository)
        links.googleMaven?.let { url ->
            ExternalLink(text = msg("link.googleMaven"), uri = url)
        }
        links.github?.let { url ->
            ExternalLink(text = msg("link.github"), uri = url)
        }
    }
}
