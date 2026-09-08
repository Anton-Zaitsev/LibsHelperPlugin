package com.zaycev.libshelper.core.proxy

import com.zaycev.libshelper.core.model.DeclaredRepository
import com.zaycev.libshelper.core.model.ProjectInventory
import com.zaycev.libshelper.core.model.RepositoryKind
import com.zaycev.libshelper.core.model.RepositoryScope
import com.zaycev.libshelper.core.model.RepositoryType
import com.zaycev.libshelper.core.model.SearchRole
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanPlanTest {
    @Test
    fun proxyMavenRepo_getsOfficialCentralAndGoogleAlternatives() {
        val repo = DeclaredRepository(
            name = "nexus",
            url = "https://nexus.company.local/repository/maven-public/",
            type = RepositoryType.Maven,
            kind = RepositoryKind.ProxyMirror,
            scope = RepositoryScope.Dependency,
        )
        val alternatives = officialAlternatives(repo)
        assertTrue(alternatives.any { it.type == RepositoryType.MavenCentral })
        assertTrue(alternatives.any { it.type == RepositoryType.Google })
        assertTrue(alternatives.any { it.url.contains("repo1.maven.org") })
        assertEquals(SearchRole.ProjectProxyFallback, searchRoleOf(repo))
    }

    @Test
    fun officialRepo_hasNoAlternatives() {
        val repo = DeclaredRepository(
            name = "mavenCentral",
            url = officialMavenCentral(),
            type = RepositoryType.MavenCentral,
            kind = RepositoryKind.Official,
            scope = RepositoryScope.Dependency,
        )
        assertTrue(officialAlternatives(repo).isEmpty())
        assertEquals(SearchRole.OfficialSource, searchRoleOf(repo))
    }

    @Test
    fun buildScanPlan_countsMissingCatalog() {
        val inventory = ProjectInventory(
            modules = persistentListOf(":", ":app"),
            dependencies = persistentListOf(),
            repositories = persistentListOf(
                DeclaredRepository(
                    name = "google",
                    url = officialGoogleMaven().first(),
                    type = RepositoryType.Google,
                    kind = RepositoryKind.Official,
                    scope = RepositoryScope.Dependency,
                ),
            ),
            httpProxy = null,
            catalogPresent = false,
        )
        val plan = buildScanPlan(inventory)
        assertEquals(false, plan.catalogPresent)
        assertEquals(2, plan.moduleCount)
        assertEquals(1, plan.repositories.size)
        assertTrue(plan.repositories.first().alternatives.isEmpty())
    }
}
