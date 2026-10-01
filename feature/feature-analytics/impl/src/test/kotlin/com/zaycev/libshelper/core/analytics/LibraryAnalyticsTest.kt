package com.zaycev.libshelper.core.analytics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryAnalyticsTest {
    private val computer = DefaultAnalyticsComputer()

    @Test
    fun ranksLibrariesByModuleUsage() {
        val analytics = computer.compute(
            AnalyticsInput(
                moduleCount = 2,
                dependencies = listOf(
                    sample("com.squareup.okhttp3", "okhttp", ":app", outdated = true),
                    sample("com.squareup.okhttp3", "okhttp", ":lib", outdated = true),
                    sample("com.squareup.retrofit2", "retrofit", ":app", outdated = false),
                ),
            ),
        )
        assertEquals("com.squareup.okhttp3:okhttp", analytics.topByWeight.first().key)
        assertEquals(2, analytics.topByWeight.first().weight)
        assertEquals(1, analytics.outdatedCount)
        assertEquals(0, analytics.skippedFirstParty)
        assertEquals(3, analytics.totalWeight)
    }

    @Test
    fun skipsOwnConventionPluginsAndKeepsThirdParty() {
        val analytics = computer.compute(
            AnalyticsInput(
                moduleCount = 2,
                dependencies = listOf(
                    sample("com.squareup.okhttp3", "okhttp", ":app"),
                    sample("com.squareup.okhttp3", "okhttp", ":lib"),
                    sample("com.example.convention.kmp-library", "com.example.convention.kmp-library.gradle.plugin", ":app", plugin = true),
                    sample("com.example.convention.kmp-library", "com.example.convention.kmp-library.gradle.plugin", ":lib", plugin = true),
                    sample("com.example.convention.kmp-compose", "com.example.convention.kmp-compose.gradle.plugin", ":app", plugin = true),
                    sample("org.jetbrains.kotlinx", "kotlinx-coroutines-test", ":app"),
                ),
            ),
        )
        assertEquals(
            listOf("com.squareup.okhttp3:okhttp", "org.jetbrains.kotlinx:kotlinx-coroutines-test"),
            analytics.thirdParty.map { it.key },
        )
        assertTrue(analytics.skippedFirstParty >= 2)
        assertTrue(analytics.libraries.any { it.group.startsWith("com.example") && it.isFirstParty })
        assertTrue(analytics.thirdParty.none { it.group.startsWith("com.example") })
    }

    @Test
    fun sunburstGivesBiggerSliceToHeavierLibrary() {
        val chart = DefaultSunburstLayout().layout(
            computer.compute(
                AnalyticsInput(
                    moduleCount = 2,
                    dependencies = listOf(
                        sample("com.squareup.okhttp3", "okhttp", ":app"),
                        sample("com.squareup.okhttp3", "okhttp", ":lib"),
                        sample("org.jetbrains.kotlinx", "kotlinx-coroutines-core", ":app"),
                    ),
                ),
            ).thirdParty,
            "Other",
        )
        assertEquals(3, chart.totalWeight)
        val okhttpSlice = chart.slices.first { it.libraryKey == "com.squareup.okhttp3:okhttp" }
        val coroutinesSlice = chart.slices.first { it.libraryKey == "org.jetbrains.kotlinx:kotlinx-coroutines-core" }
        assertTrue(okhttpSlice.sweepDeg > coroutinesSlice.sweepDeg)
        assertTrue(chart.slices.any { it.label == "com.squareup" && it.libraryKey == null })
    }

    @Test
    fun sunburstOfAllLibrariesIncludesFirstParty() {
        val analytics = computer.compute(
            AnalyticsInput(
                moduleCount = 1,
                dependencies = listOf(
                    sample("com.squareup.okhttp3", "okhttp", ":app"),
                    sample("com.example.convention.kmp-library", "com.example.convention.kmp-library.gradle.plugin", ":app", plugin = true),
                ),
            ),
        )
        val layout = DefaultSunburstLayout()
        val thirdParty = layout.layout(analytics.thirdParty, "Other")
        val all = layout.layout(analytics.libraries, "Other")
        assertTrue(thirdParty.slices.none { it.libraryKey?.startsWith("com.example") == true })
        assertTrue(all.slices.any { it.libraryKey?.startsWith("com.example") == true })
        assertTrue(all.libraryCount > thirdParty.libraryCount)
    }

    @Test
    fun emptyInputStaysEmpty() {
        val analytics = computer.compute(AnalyticsInput(emptyList(), moduleCount = 0))
        assertEquals(ProjectAnalytics.Empty.outdatedCount, analytics.outdatedCount)
        assertEquals(0, DefaultSunburstLayout().layout(analytics.libraries, "Other").libraryCount)
    }

    private fun sample(
        group: String,
        artifact: String,
        module: String,
        plugin: Boolean = false,
        outdated: Boolean = false,
    ) = AnalyticsDependency(
        key = "$group:$artifact",
        group = group,
        artifact = artifact,
        module = module,
        configuration = if (plugin) "classpath" else "implementation",
        catalogAlias = artifact,
        versionRef = null,
        requestedVersion = "1.0.0",
        isPlugin = plugin,
        isLocal = false,
        isBom = false,
        fromCatalog = false,
        current = "1.0.0",
        recommended = if (outdated) "2.0.0" else null,
        outdated = outdated,
        channel = null,
        github = null,
        mavenCentral = null,
    )
}
