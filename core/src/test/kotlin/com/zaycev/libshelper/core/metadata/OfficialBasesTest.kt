package com.zaycev.libshelper.core.metadata

import com.zaycev.libshelper.core.model.ConnectStatus
import com.zaycev.libshelper.core.model.Coordinates
import com.zaycev.libshelper.core.model.DeclaredRepository
import com.zaycev.libshelper.core.model.RepositoryKind
import com.zaycev.libshelper.core.model.RepositoryScope
import com.zaycev.libshelper.core.model.RepositoryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfficialBasesTest {
    @Test
    fun jetbrainsComposeDoesNotQueryGoogle() {
        val bases = officialBasesFor(Coordinates("org.jetbrains.compose.ui", "ui"), isPlugin = false)
        assertTrue(bases.any { it.contains("repo1.maven.org") })
        assertFalse(bases.any { it.contains("google") })
        assertFalse(bases.any { it.contains("jitpack") })
    }

    @Test
    fun jetbrainsComposeUsesSpaceRepoWhenTheProjectDeclaresIt() {
        val bases = officialBasesFor(
            Coordinates("org.jetbrains.compose.material3", "material3"),
            isPlugin = false,
            projectRepositories = listOf(repo("https://maven.pkg.jetbrains.space/public/p/compose/dev/")),
        )
        assertTrue(bases.any { it.contains("compose/dev") })
        assertFalse(bases.any { it.contains("dl.google.com") })
    }

    @Test
    fun jitpackIsSkippedUnlessDeclared() {
        val coords = Coordinates("com.github.user", "lib")
        val plain = officialBasesFor(coords, isPlugin = false)
        assertFalse(plain.any { it.contains("jitpack.io") })
        val declared = officialBasesFor(coords, isPlugin = false, projectRepositories = listOf(repo("https://jitpack.io/")))
        assertEquals("https://jitpack.io/", declared.first())
    }

    @Test
    fun androidxStillPrefersGoogle() {
        val bases = officialBasesFor(Coordinates("androidx.compose.ui", "ui"), isPlugin = false)
        assertTrue(bases.first().contains("dl.google.com"))
    }

    @Test
    fun familiesDoNotMix() {
        val compose = artifactFamily(Coordinates("org.jetbrains.compose.ui", "ui"), false)
        val android = artifactFamily(Coordinates("androidx.compose.ui", "ui"), false)
        assertEquals(ArtifactFamily.JetBrainsCompose, compose)
        assertEquals(ArtifactFamily.AndroidX, android)
        assertFalse(familiesCompatible(compose, android))
    }

    private fun repo(url: String) = DeclaredRepository(
        name = "repo",
        url = url,
        type = RepositoryType.Maven,
        kind = RepositoryKind.ProxyMirror,
        scope = RepositoryScope.Dependency,
        connectStatus = ConnectStatus.Unknown,
    )
}
