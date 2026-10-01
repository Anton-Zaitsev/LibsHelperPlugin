package com.zaycev.libshelper.core.recommend

import com.zaycev.libshelper.core.analytics.AnalyticsComputer
import com.zaycev.libshelper.core.analytics.ProjectAnalytics
import com.zaycev.libshelper.core.inlay.DependencyHintKind
import com.zaycev.libshelper.core.inlay.dependencyHints
import com.zaycev.libshelper.core.model.Coordinates
import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.DependencySource
import com.zaycev.libshelper.core.model.LibraryAdvice
import com.zaycev.libshelper.core.model.MetadataOrigin
import com.zaycev.libshelper.core.model.MetadataOriginKind
import com.zaycev.libshelper.core.model.ProjectInventory
import com.zaycev.libshelper.core.model.ProjectReport
import com.zaycev.libshelper.core.model.ScanPlan
import com.zaycev.libshelper.core.model.VersionChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

class ApplyKnownVersionTest {
    private val counting = AnalyticsComputer { input ->
        ProjectAnalytics.Empty.copy(
            outdatedCount = input.dependencies.distinctBy { it.key }.count { it.outdated },
        )
    }
    private val origin = MetadataOrigin(MetadataOriginKind.OfficialDirect, "https://repo1.maven.org/maven2/")
    private val versions = listOf("4.12.0", "4.12.2", "5.0.0", "5.1.0-rc02")

    @Test
    fun applyingRecommendedStable_stopsBeingOutdated() {
        val okhttp = okhttp("4.12.0")
        val report = reportOf(listOf(okhttp), listOf(library(okhttp, versions)))
        val patched = applyKnownVersion(report, okhttp, "5.0.0", counting)
        val advice = patched.libraries.single().advice
        assertEquals("5.0.0", advice.current?.raw)
        assertFalse(advice.isOutdated)
        assertNull(advice.preferredStable)
        assertEquals("5.0.0", patched.inventory.dependencies.single().requestedVersion)
        assertEquals(0, patched.analytics.outdatedCount)
    }

    @Test
    fun sharedVersionRef_updatesEveryLibraryOnThatRef() {
        val okhttp = okhttp("4.12.0").copy(versionRef = "okhttp")
        val logging = okhttp("4.12.0").copy(
            coordinates = Coordinates("com.squareup.okhttp3", "logging-interceptor"),
            catalogAlias = "okhttp-logging",
            versionRef = "okhttp",
        )
        val report = reportOf(
            listOf(okhttp, logging),
            listOf(library(okhttp, versions), library(logging, versions)),
        )
        val patched = applyKnownVersion(report, okhttp, "5.0.0", counting)
        assertTrue(patched.libraries.all { it.advice.current?.raw == "5.0.0" })
        assertTrue(patched.inventory.dependencies.all { it.requestedVersion == "5.0.0" })
    }

    @Test
    fun undoToOlderStable_restoresOutdatedBadge() {
        val okhttp = catalog("4.12.0", "okhttp")
        val report = reportOf(listOf(okhttp), listOf(library(okhttp, versions)))
        val applied = applyKnownVersion(report, okhttp, "5.0.0", counting)
        assertEquals(DependencyHintKind.Current, hintKind(applied))
        assertEquals(VersionChannel.Stable, applied.libraries.single().advice.currentChannel)

        val reverted = syncRequestedVersions(
            applied,
            "gradle/libs.versions.toml",
            "[versions]\nokhttp = \"4.12.0\"\n",
            counting,
        )
        assertEquals("4.12.0", reverted.libraries.single().advice.current?.raw)
        assertEquals(DependencyHintKind.Outdated, hintKind(reverted))
        assertEquals("5.0.0", reverted.libraries.single().advice.preferredStable?.version?.raw)
    }

    @Test
    fun undoToReleaseCandidate_restoresRcBadge() {
        val kotlin = prepared("2.1.0-rc1", "kotlin", listOf("2.0.0", "2.1.0-rc1"))
        val applied = applyKnownVersion(kotlin.report, kotlin.dependency, "2.0.0", counting)
        assertEquals(DependencyHintKind.Current, hintKind(applied))

        val reverted = syncRequestedVersions(
            applied,
            "gradle/libs.versions.toml",
            "[versions]\nkotlin = \"2.1.0-rc1\"\n",
            counting,
        )
        val advice = reverted.libraries.single().advice
        assertEquals("2.1.0-rc1", advice.current?.raw)
        assertEquals(VersionChannel.ReleaseCandidate, advice.currentChannel)
        assertEquals(DependencyHintKind.Rc, hintKind(reverted))
    }

    @Test
    fun undoToBeta_restoresBetaBadge() {
        val agp = prepared("9.1.0-beta02", "agp", listOf("9.0.0", "9.1.0-beta02"))
        val applied = applyKnownVersion(agp.report, agp.dependency, "9.0.0", counting)
        assertEquals(VersionChannel.Stable, applied.libraries.single().advice.currentChannel)
        assertEquals(DependencyHintKind.Current, hintKind(applied))

        val reverted = syncRequestedVersions(
            applied,
            "gradle/libs.versions.toml",
            "[versions]\nagp = \"9.1.0-beta02\"\n",
            counting,
        )
        assertEquals(VersionChannel.Beta, reverted.libraries.single().advice.currentChannel)
        assertEquals(DependencyHintKind.Beta, hintKind(reverted))
    }

    @Test
    fun sameText_keepsReport() {
        val okhttp = prepared("4.12.0", "okhttp", versions)
        val applied = applyKnownVersion(okhttp.report, okhttp.dependency, "5.0.0", counting)
        val same = syncRequestedVersions(
            applied,
            "gradle/libs.versions.toml",
            "[versions]\nokhttp = \"5.0.0\"\n",
            counting,
        )
        assertSame(applied, same)
    }

    @Test
    fun unrelatedLibrary_staysUnchanged() {
        val okhttp = okhttp("4.12.0")
        val guava = DeclaredDependency(
            coordinates = Coordinates("com.google.guava", "guava"),
            requestedVersion = "32.1.0",
            configuration = "implementation",
            module = ":app",
            source = DependencySource.Toml,
        )
        val report = reportOf(
            listOf(okhttp, guava),
            listOf(library(okhttp, versions), library(guava, listOf("32.1.0"))),
        )
        val patched = applyKnownVersion(report, okhttp, "5.0.0", counting)
        val guavaAdvice = patched.libraries.first { it.advice.dependency.coordinates.artifact == "guava" }.advice
        assertEquals("32.1.0", guavaAdvice.current?.raw)
        assertEquals("4.12.0", report.libraries.first { it.advice.dependency.coordinates.artifact == "okhttp" }.advice.current?.raw)
        assertEquals("5.0.0", patched.libraries.first { it.advice.dependency.coordinates.artifact == "okhttp" }.advice.current?.raw)
    }

    private fun library(dependency: DeclaredDependency, versions: List<String>) = LibraryAdvice(
        advice = buildAdvice(dependency, versions, origin, inventory(dependency)),
    )

    private fun reportOf(
        dependencies: List<DeclaredDependency>,
        libraries: List<LibraryAdvice>,
    ) = ProjectReport(
        inventory = inventory(*dependencies.toTypedArray()),
        libraries = libraries.toPersistentList(),
        metadataFromProxyOnly = false,
        scanPlan = ScanPlan(
            catalogPresent = true,
            moduleCount = 1,
            dependencyCount = dependencies.size,
            unresolvedAliasCount = 0,
            httpProxy = null,
            repositories = persistentListOf(),
        ),
    )

    private fun hintKind(report: ProjectReport): DependencyHintKind =
        dependencyHints(report).single().kind

    private fun catalog(version: String, ref: String) = okhttp(version).copy(
        versionRef = ref,
        catalogAlias = ref,
        catalogPath = "gradle/libs.versions.toml",
        catalogVersionLine = 2,
    )

    private fun prepared(version: String, ref: String, known: List<String>): PreparedCatalog {
        val dependency = catalog(version, ref)
        return PreparedCatalog(
            dependency = dependency,
            report = reportOf(listOf(dependency), listOf(library(dependency, known))),
        )
    }

    private data class PreparedCatalog(
        val dependency: DeclaredDependency,
        val report: ProjectReport,
    )

    private fun okhttp(version: String) = DeclaredDependency(
        coordinates = Coordinates("com.squareup.okhttp3", "okhttp"),
        requestedVersion = version,
        catalogAlias = "okhttp",
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
