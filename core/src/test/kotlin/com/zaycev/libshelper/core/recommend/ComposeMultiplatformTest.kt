package com.zaycev.libshelper.core.recommend

import com.zaycev.libshelper.core.metadata.ArtifactFamily
import com.zaycev.libshelper.core.metadata.artifactFamily
import com.zaycev.libshelper.core.model.ConflictType
import com.zaycev.libshelper.core.model.Coordinates
import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.DependencySource
import com.zaycev.libshelper.core.model.LibraryAdvice
import com.zaycev.libshelper.core.model.MetadataOrigin
import com.zaycev.libshelper.core.model.MetadataOriginKind
import com.zaycev.libshelper.core.model.ProjectInventory
import com.zaycev.libshelper.core.model.VersionChannel
import com.zaycev.libshelper.core.model.displayTarget
import com.zaycev.libshelper.core.versioning.classifyVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

class ComposeMultiplatformTest {
    private val origin = MetadataOrigin(MetadataOriginKind.OfficialDirect, "https://repo1.maven.org/maven2/")
    private val composeVersions = listOf("1.7.0", "1.8.0-alpha03", "1.8.0-alpha05")
    private val androidVersions = listOf("1.7.0", "1.8.0", "1.9.0-alpha01")

    @Test
    fun jetbrainsComposeIsNotAndroidx() {
        assertEquals(ArtifactFamily.JetBrainsCompose, artifactFamily(Coordinates("org.jetbrains.compose.ui", "ui"), false))
        assertEquals(ArtifactFamily.AndroidX, artifactFamily(Coordinates("androidx.compose.material3", "material3"), false))
        assertEquals(
            ArtifactFamily.JetBrainsAndroidX,
            artifactFamily(Coordinates("org.jetbrains.androidx.lifecycle", "lifecycle-runtime"), false),
        )
    }

    @Test
    fun alphaOnlyUpdateShowsTheAlphaVersion() {
        val dependency = lib("org.jetbrains.compose.material3", "material3", "1.8.0-alpha03", "compose-multiplatform")
        val advice = buildAdvice(dependency, composeVersions, origin, inventory(dependency))
        val target = advice.displayTarget()
        assertEquals("1.8.0-alpha05", target?.version)
        assertEquals(VersionChannel.Alpha, target?.channel)
        assertTrue(advice.isOutdated)
    }

    @Test
    fun olderStableDoesNotReplaceCurrentPrerelease() {
        val dependency = lib("org.jetbrains.compose.ui", "ui", "1.8.0-alpha03", "compose-multiplatform")
        val advice = buildAdvice(dependency, listOf("1.7.0", "1.8.0-alpha03"), origin, inventory(dependency))
        assertNull(advice.displayTarget())
        assertFalse(advice.isOutdated)
    }

    @Test
    fun sharedRefAcrossFamiliesIsMarked() {
        val compose = lib("org.jetbrains.compose.ui", "ui", "1.7.0", "compose")
        val android = lib("androidx.compose.ui", "ui", "1.7.0", "compose")
        val first = LibraryAdvice(buildAdvice(compose, composeVersions, origin, inventory(compose, android)))
        val second = LibraryAdvice(buildAdvice(android, androidVersions, origin, inventory(compose, android)))
        val aligned = alignSharedVersionRefs(listOf(first, second), inventory(compose, android))
        assertTrue(aligned.any { item -> item.advice.sharedConflicts.any { it.type == ConflictType.MixedFamilySharedRef } })
    }

    @Test
    fun devBuildIsNotRecommended() {
        assertEquals(VersionChannel.Dev, classifyVersion("1.9.0+dev1234"))
        val dependency = lib("org.jetbrains.compose.ui", "ui", "1.9.0+dev1234", null)
        val advice = buildAdvice(dependency, listOf("1.8.0", "1.9.0+dev1234"), origin, inventory(dependency))
        assertEquals(VersionChannel.Dev, advice.currentChannel)
        assertNull(advice.preferredStable)
    }

    @Test
    fun pluginMarkerAndBomStayInTheirFamilies() {
        assertEquals(ArtifactFamily.GradlePlugin, artifactFamily(Coordinates("org.jetbrains.compose", "org.jetbrains.compose.gradle.plugin"), true))
        assertEquals(ArtifactFamily.AndroidX, artifactFamily(Coordinates("androidx.compose", "compose-bom"), false))
        assertEquals(
            ArtifactFamily.GradlePlugin,
            artifactFamily(Coordinates("org.jetbrains.kotlin.plugin.compose", "org.jetbrains.kotlin.plugin.compose.gradle.plugin"), true),
        )
    }

    private fun lib(group: String, artifact: String, version: String, ref: String?) = DeclaredDependency(
        coordinates = Coordinates(group, artifact),
        requestedVersion = version,
        versionRef = ref,
        configuration = "implementation",
        module = ":app",
        source = DependencySource.Toml,
    )

    private fun inventory(vararg deps: DeclaredDependency) = ProjectInventory(
        modules = persistentListOf(":app"),
        dependencies = deps.toList().toPersistentList(),
        repositories = persistentListOf(),
        httpProxy = null,
    )
}
