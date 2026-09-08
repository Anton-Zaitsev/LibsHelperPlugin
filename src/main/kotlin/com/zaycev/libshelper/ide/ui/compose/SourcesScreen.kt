package com.zaycev.libshelper.ide.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import com.zaycev.libshelper.core.model.ProjectReport
import com.zaycev.libshelper.core.model.RepositorySearchEntry
import com.zaycev.libshelper.core.model.ScanPlan
import com.zaycev.libshelper.core.model.SearchRole
import com.zaycev.libshelper.ide.auth.editRepositoryAuth
import com.zaycev.libshelper.ide.i18n.msg
import com.zaycev.libshelper.ide.ui.StatusKind
import com.zaycev.libshelper.ide.ui.connectMessage
import com.zaycev.libshelper.ide.ui.kindLabel
import com.zaycev.libshelper.ide.ui.roleLabel
import com.zaycev.libshelper.ide.ui.scopeLabel
import com.zaycev.libshelper.ide.ui.statusLabel
import com.zaycev.libshelper.ide.ui.typeLabel
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text
import kotlinx.collections.immutable.toPersistentList

@Composable
fun SourcesScreen(
    project: Project,
    report: ProjectReport,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val plan = report.scanPlan
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScanSummary(plan)
        if (report.metadataFromProxyOnly) {
            AccentCard(ComposePalette.status(StatusKind.Outdated)) {
                TitleText(msg("banner.proxyOnly"))
            }
        }
        plan.httpProxy?.let { proxy ->
            MutedText(msg("scan.httpProxy", proxy.host, proxy.port))
        }
        if (plan.repositories.isEmpty()) {
            MutedText(msg("empty.noRepos"))
        } else {
            val entries = plan.repositories
                .map { entry ->
                    val withStatus = report.inventory.repositories
                        .firstOrNull { it.url == entry.repository.url && it.scope == entry.repository.scope }
                    if (withStatus == null) entry else entry.copy(repository = withStatus)
                }
                .distinctBy { it.repository.url to it.repository.scope }
            ResponsiveGrid(items = entries.toPersistentList(), wide = wide) { entry ->
                RepositoryCard(project, entry)
            }
        }
    }
}

@Composable
private fun ScanSummary(plan: ScanPlan, modifier: Modifier = Modifier) {
    val modules = msg("scan.modules", plan.moduleCount, plan.dependencyCount) +
        if (plan.unresolvedAliasCount > 0) msg("scan.unresolved", plan.unresolvedAliasCount) else ""
    val catalog = if (plan.catalogPresent) {
        msg("scan.catalogFound", plan.catalogFileName)
    } else {
        msg("scan.catalogMissing", plan.catalogFileName)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MutedText(modules)
        MutedText(catalog)
    }
}

@Composable
private fun RepositoryCard(
    project: Project,
    entry: RepositorySearchEntry,
    modifier: Modifier = Modifier,
) {
    val repo = entry.repository
    val accent: Color = when (entry.role) {
        SearchRole.OfficialSource -> ComposePalette.status(StatusKind.Current)
        SearchRole.ProjectProxyFallback -> ComposePalette.status(StatusKind.Rc)
        SearchRole.SkippedLocal -> ComposePalette.muted()
    }
    val roleTone = accent
    val roleTitle = when (entry.role) {
        SearchRole.OfficialSource -> msg("source.badge.official")
        SearchRole.ProjectProxyFallback -> msg("source.badge.backup")
        SearchRole.SkippedLocal -> msg("source.badge.skip")
    }
    AccentCard(accent, modifier) {
        PillRow {
            Pill(roleTitle, roleTone)
            Pill(scopeLabel(repo.scope), ComposePalette.current())
            Pill(statusLabel(repo.connectStatus), ComposePalette.muted())
        }
        TitleText(repo.name.ifBlank { repo.url })
        MutedText(repo.url)
        MutedText(roleLabel(entry.role))
        connectMessage(repo.message)?.let { MutedText(it) }
        if (repo.exclusive && repo.includeGroups.isNotEmpty()) {
            MutedText(msg("exclusive", repo.includeGroups.joinToString()))
        }
        PillRow {
            Pill(kindLabel(repo.kind), ComposePalette.muted())
            Pill(typeLabel(repo.type), ComposePalette.muted())
        }
        if (entry.alternatives.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ComposePalette.noteFill())
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TitleText(msg("alts.title"))
                MutedText(msg("source.alts.lead"))
                entry.alternatives.forEach { alt ->
                    MutedText("${typeLabel(alt.type)}  ${alt.url}")
                }
            }
        }
        if (entry.role == SearchRole.ProjectProxyFallback) {
            MutedText(msg("source.signIn.hint"))
            DefaultButton(onClick = { editRepositoryAuth(project, repo.url) }) {
                Text(msg("source.signIn"))
            }
        }
    }
}
