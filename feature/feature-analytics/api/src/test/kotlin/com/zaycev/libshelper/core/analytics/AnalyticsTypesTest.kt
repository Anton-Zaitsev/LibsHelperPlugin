package com.zaycev.libshelper.core.analytics

import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AnalyticsTypesTest {
    @Test
    fun emptyAnalyticsAndChartTypesRoundTrip() {
        val stat = LibraryStat(
            key = "g:a",
            group = "g",
            artifact = "a",
            modules = persistentListOf(":app"),
            configurations = persistentListOf("implementation"),
            catalogAlias = null,
            versionRef = null,
            current = "1",
            recommended = "2",
            isOutdated = true,
            channel = "Stable",
            isPlugin = false,
            isLocal = false,
            isBom = false,
            isFirstParty = false,
            github = null,
            mavenCentral = null,
            weight = 1,
        )
        val dependency = AnalyticsDependency(
            key = "g:a",
            group = "g",
            artifact = "a",
            module = ":app",
            configuration = "implementation",
            catalogAlias = null,
            versionRef = null,
            requestedVersion = "1",
            isPlugin = false,
            isLocal = false,
            isBom = false,
            fromCatalog = true,
            current = "1",
            recommended = "2",
            outdated = true,
            channel = "Stable",
            github = null,
            mavenCentral = null,
        )
        val input = AnalyticsInput(listOf(dependency), moduleCount = 0)
        val group = SharedVersionGroup("kotlin", persistentListOf("g:a"))
        val slice = SunburstSlice("id", "g", 1, 0f, 10f, 0.2f, 1f, 0, "g:a")
        val chart = SunburstChartData(persistentListOf(slice), totalWeight = 1, libraryCount = 1)
        assertEquals(0, input.moduleCount)
        assertEquals(stat, stat.copy())
        assertEquals(group, group.copy())
        assertEquals(chart, chart.copy())
        assertSame(ProjectAnalytics.Empty, ProjectAnalytics.Empty)
        assertTrue(ProjectAnalytics.Empty.libraries.isEmpty())
        val computer = AnalyticsComputer { ProjectAnalytics.Empty }
        assertEquals(0, computer.compute(input).moduleCount)
    }
}
