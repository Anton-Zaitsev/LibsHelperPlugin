package com.zaycev.libshelper.core.analytics

import com.zaycev.libshelper.core.model.Coordinates
import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.DependencySource
import com.zaycev.libshelper.core.model.LibraryAdvice
import com.zaycev.libshelper.core.model.ProjectInventory
import com.zaycev.libshelper.core.model.ProjectReport
import com.zaycev.libshelper.core.model.ScanPlan
import com.zaycev.libshelper.core.model.UpdateAdvice
import com.zaycev.libshelper.core.versioning.MavenVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

class LibraryAnalyticsTest {
    @Test
    fun ranksLibrariesByModuleUsage() {
        val okhttp = dep("com.squareup.okhttp3", "okhttp", ":app")
        val report = reportOf(
            modules = listOf(":app", ":lib"),
            dependencies = listOf(
                okhttp,
                okhttp.copy(module = ":lib"),
                dep("com.squareup.retrofit2", "retrofit", ":app"),
            ),
            libraries = listOf(
                advice(okhttp, outdated = true),
                advice(dep("com.squareup.retrofit2", "retrofit", ":app"), outdated = false),
            ),
        )
        val analytics = projectAnalytics(report)
        assertEquals("com.squareup.okhttp3:okhttp", analytics.topByWeight.first().key)
        assertEquals(2, analytics.topByWeight.first().weight)
        assertEquals(1, analytics.outdatedCount)
        assertEquals(0, analytics.skippedFirstParty)
        assertEquals(3, analytics.totalWeight)
    }

    @Test
    fun skipsOwnConventionPluginsAndKeepsThirdParty() {
        val okhttp = dep("com.squareup.okhttp3", "okhttp", ":app")
        val convention = dep(
            group = "com.acme.convention.kmp-library",
            artifact = "com.acme.convention.kmp-library.gradle.plugin",
            module = ":app",
            plugin = true,
        )
        val report = reportOf(
            modules = listOf(":app", ":lib"),
            dependencies = listOf(
                okhttp,
                okhttp.copy(module = ":lib"),
                convention,
                convention.copy(module = ":lib"),
                dep(
                    group = "com.acme.convention.kmp-compose",
                    artifact = "com.acme.convention.kmp-compose.gradle.plugin",
                    module = ":app",
                    plugin = true,
                ),
                dep("org.jetbrains.kotlinx", "kotlinx-coroutines-test", ":app"),
            ),
        )
        val analytics = projectAnalytics(report)
        assertEquals(
            listOf("com.squareup.okhttp3:okhttp", "org.jetbrains.kotlinx:kotlinx-coroutines-test"),
            analytics.thirdParty.map { it.key },
        )
        assertTrue(analytics.skippedFirstParty >= 2)
        assertTrue(analytics.libraries.any { it.group.startsWith("com.acme") && it.isFirstParty })
        assertTrue(analytics.thirdParty.none { it.group.startsWith("com.acme") })
    }

    @Test
    fun sunburstGivesBiggerSliceToHeavierLibrary() {
        val okhttp = dep("com.squareup.okhttp3", "okhttp", ":app")
        val report = reportOf(
            modules = listOf(":app", ":lib"),
            dependencies = listOf(
                okhttp,
                okhttp.copy(module = ":lib"),
                dep("org.jetbrains.kotlinx", "kotlinx-coroutines-core", ":app"),
            ),
        )
        val chart = sunburstOf(projectAnalytics(report).thirdParty, "Other")
        assertEquals(3, chart.totalWeight)
        val okhttpSlice = chart.slices.first { it.libraryKey == "com.squareup.okhttp3:okhttp" }
        val coroutinesSlice = chart.slices.first { it.libraryKey == "org.jetbrains.kotlinx:kotlinx-coroutines-core" }
        assertTrue(okhttpSlice.sweepDeg > coroutinesSlice.sweepDeg)
        assertTrue(chart.slices.any { it.label == "com.squareup" && it.libraryKey == null })
    }

    @Test
    fun sunburstOfAllLibrariesIncludesFirstParty() {
        val okhttp = dep("com.squareup.okhttp3", "okhttp", ":app")
        val convention = dep(
            group = "com.acme.convention.kmp-library",
            artifact = "com.acme.convention.kmp-library.gradle.plugin",
            module = ":app",
            plugin = true,
        )
        val analytics = projectAnalytics(
            reportOf(
                modules = listOf(":app"),
                dependencies = listOf(okhttp, convention),
            ),
        )
        val thirdParty = sunburstOf(analytics.thirdParty, "Other")
        val all = sunburstOf(analytics.libraries, "Other")
        assertTrue(thirdParty.slices.none { it.libraryKey?.startsWith("com.acme") == true })
        assertTrue(all.slices.any { it.libraryKey?.startsWith("com.acme") == true })
        assertTrue(all.libraryCount > thirdParty.libraryCount)
    }

    private fun reportOf(
        modules: List<String>,
        dependencies: List<DeclaredDependency>,
        libraries: List<LibraryAdvice> = emptyList(),
    ) = ProjectReport(
        inventory = ProjectInventory(
            modules = modules.toPersistentList(),
            dependencies = dependencies.toPersistentList(),
            repositories = persistentListOf(),
            httpProxy = null,
        ),
        libraries = libraries.toPersistentList(),
        metadataFromProxyOnly = false,
        scanPlan = ScanPlan(
            catalogPresent = true,
            moduleCount = modules.size,
            dependencyCount = dependencies.size,
            unresolvedAliasCount = 0,
            httpProxy = null,
            repositories = persistentListOf(),
        ),
    )

    private fun dep(
        group: String,
        artifact: String,
        module: String,
        plugin: Boolean = false,
    ) = DeclaredDependency(
        coordinates = Coordinates(group, artifact),
        requestedVersion = "1.0.0",
        configuration = if (plugin) "classpath" else "implementation",
        module = module,
        source = DependencySource.KotlinDsl,
        catalogAlias = artifact,
        isPlugin = plugin,
    )

    private fun advice(dep: DeclaredDependency, outdated: Boolean) = LibraryAdvice(
        advice = UpdateAdvice(
            dependency = dep,
            current = MavenVersion.parse(dep.requestedVersion ?: "1.0.0"),
            currentChannel = null,
            isOutdated = outdated,
            preferredStable = null,
            latestRc = null,
            latestBeta = null,
            latestAlpha = null,
        ),
    )
}
