package com.zaycev.libshelper.core.proxy

import com.zaycev.libshelper.core.model.DeclaredRepository
import com.zaycev.libshelper.core.model.OfficialAlternative
import com.zaycev.libshelper.core.model.ProjectInventory
import com.zaycev.libshelper.core.model.RepositoryKind
import com.zaycev.libshelper.core.model.RepositorySearchEntry
import com.zaycev.libshelper.core.model.RepositoryType
import com.zaycev.libshelper.core.model.ScanPlan
import com.zaycev.libshelper.core.model.SearchRole
import kotlinx.collections.immutable.toPersistentList

fun officialAlternatives(repo: DeclaredRepository): List<OfficialAlternative> {
    if (repo.kind == RepositoryKind.Official) return emptyList()
    if (repo.type == RepositoryType.FlatDir || repo.type == RepositoryType.MavenLocal) return emptyList()
    val items = when (repo.type) {
        RepositoryType.Google ->
            officialGoogleMaven().map { OfficialAlternative(RepositoryType.Google, it) }
        RepositoryType.MavenCentral ->
            listOf(OfficialAlternative(RepositoryType.MavenCentral, officialMavenCentral()))
        RepositoryType.PluginPortal ->
            listOf(OfficialAlternative(RepositoryType.PluginPortal, officialPluginPortal()))
        RepositoryType.Maven -> buildList {
            if (repo.url.lowercase().contains("jitpack")) {
                add(OfficialAlternative(RepositoryType.Maven, officialJitPack()))
            }
            add(OfficialAlternative(RepositoryType.MavenCentral, officialMavenCentral()))
            officialGoogleMaven().forEach { add(OfficialAlternative(RepositoryType.Google, it)) }
            add(OfficialAlternative(RepositoryType.PluginPortal, officialPluginPortal()))
        }
        RepositoryType.FlatDir, RepositoryType.MavenLocal -> emptyList()
    }
    return items.distinctBy { it.url }
}

fun searchRoleOf(repo: DeclaredRepository): SearchRole = when (repo.type) {
    RepositoryType.FlatDir, RepositoryType.MavenLocal -> SearchRole.SkippedLocal
    else -> when (repo.kind) {
        RepositoryKind.Official -> SearchRole.OfficialSource
        RepositoryKind.ProxyMirror, RepositoryKind.Private -> SearchRole.ProjectProxyFallback
    }
}

fun buildScanPlan(inventory: ProjectInventory): ScanPlan = ScanPlan(
    catalogPresent = inventory.catalogPresent,
    moduleCount = inventory.modules.size,
    dependencyCount = inventory.dependencies.size,
    unresolvedAliasCount = inventory.unresolvedCatalogAliases.size,
    httpProxy = inventory.httpProxy,
    repositories = inventory.repositories.map { repo ->
        RepositorySearchEntry(
            repository = repo,
            role = searchRoleOf(repo),
            alternatives = officialAlternatives(repo).toPersistentList(),
        )
    }.toPersistentList(),
)
