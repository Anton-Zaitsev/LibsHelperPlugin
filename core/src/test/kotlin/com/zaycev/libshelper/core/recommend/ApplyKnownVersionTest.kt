package com.zaycev.libshelper.core.recommend

import com.zaycev.libshelper.core.model.Coordinates
import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.DependencySource
import com.zaycev.libshelper.core.model.LibraryAdvice
import com.zaycev.libshelper.core.model.MetadataOrigin
import com.zaycev.libshelper.core.model.MetadataOriginKind
import com.zaycev.libshelper.core.model.ProjectInventory
import com.zaycev.libshelper.core.model.ProjectReport
import com.zaycev.libshelper.core.model.ScanPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

class ApplyKnownVersionTest {
    private val origin = MetadataOrigin(MetadataOriginKind.OfficialDirect, "https://repo1.maven.org/maven2/")
    private val versions = listOf("4.12.0", "4.12.2", "5.0.0", "5.1.0-rc02")

    @Test
    fun applyingRecommendedStable_stopsBeingOutdated() {
        val okhttp = okhttp("4.12.0")
        val report = reportOf(listOf(okhttp), listOf(library(okhttp, versions)))
        val patched = applyKnownVersion(report, okhttp, "5.0.0")
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
        val patched = applyKnownVersion(report, okhttp, "5.0.0")
        assertTrue(patched.libraries.all { it.advice.current?.raw == "5.0.0" })
        assertTrue(patched.inventory.dependencies.all { it.requestedVersion == "5.0.0" })
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
        val patched = applyKnownVersion(report, okhttp, "5.0.0")
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
