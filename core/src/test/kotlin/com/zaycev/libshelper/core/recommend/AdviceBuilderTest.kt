package com.zaycev.libshelper.core.recommend

import com.zaycev.libshelper.core.model.Coordinates
import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.DependencySource
import com.zaycev.libshelper.core.model.MetadataOrigin
import com.zaycev.libshelper.core.model.MetadataOriginKind
import com.zaycev.libshelper.core.model.OfferScore
import com.zaycev.libshelper.core.model.ProjectInventory
import com.zaycev.libshelper.core.versioning.classifyVersion
import com.zaycev.libshelper.core.model.VersionChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

class AdviceBuilderTest {
    private val versions = listOf(
        "4.12.0",
        "4.12.2",
        "5.0.0",
        "5.1.0-rc02",
        "5.1.0-beta04",
        "5.2.0-alpha03",
    )
    private val origin = MetadataOrigin(MetadataOriginKind.OfficialDirect, "https://repo1.maven.org/maven2/")

    @Test
    fun outdatedPrefersLatestStableNotAlpha() {
        val advice = buildAdvice(okhttp("4.12.0"), versions, origin, inventory(okhttp("4.12.0")))
        assertTrue(advice.isOutdated)
        val preferred = requireNotNull(advice.preferredStable)
        assertEquals("5.0.0", preferred.version.raw)
        assertEquals(VersionChannel.Stable, preferred.channel)
        assertEquals("5.1.0-rc02", advice.latestRc?.version?.raw)
        assertEquals("5.1.0-beta04", advice.latestBeta?.version?.raw)
        assertEquals("5.2.0-alpha03", advice.latestAlpha?.version?.raw)
        assertTrue(preferred.consequences.any { it.id.name == "SameLinePatch" && it.args.contains("4.12.2") })
        assertTrue(preferred.conflicts.any { it.type.name == "MajorBump" })
    }

    @Test
    fun currentStableKeepsPrereleaseVariants() {
        val advice = buildAdvice(okhttp("5.0.0"), versions, origin, inventory(okhttp("5.0.0")))
        assertFalse(advice.isOutdated)
        assertNull(advice.preferredStable)
        assertEquals("5.2.0-alpha03", advice.latestAlpha?.version?.raw)
        assertEquals(OfferScore.DoNot, advice.latestAlpha?.score)
    }

    @Test
    fun bomMemberGetsAlignmentConflict() {
        val ui = DeclaredDependency(
            coordinates = Coordinates("androidx.compose.ui", "ui"),
            requestedVersion = "1.7.0",
            versionRef = "compose-bom",
            configuration = "implementation",
            module = ":app",
            source = DependencySource.Toml,
        )
        val bom = DeclaredDependency(
            coordinates = Coordinates("androidx.compose", "compose-bom"),
            requestedVersion = "2025.01.00",
            versionRef = "compose-bom",
            configuration = "implementation",
            module = ":app",
            source = DependencySource.Toml,
            isBom = true,
        )
        val advice = buildAdvice(ui, listOf("1.7.0", "1.8.0"), origin, inventory(ui, bom))
        assertTrue(advice.preferredStable?.conflicts?.any { it.type.name == "BomAlignment" } == true)
    }

    @Test
    fun sharedVersionRefIsCaution() {
        val a = okhttp("4.12.0").copy(versionRef = "okhttp")
        val b = okhttp("4.12.0").copy(
            coordinates = Coordinates("com.squareup.retrofit2", "retrofit"),
            versionRef = "okhttp",
        )
        val advice = buildAdvice(a, versions, origin, inventory(a, b))
        assertNotNull(advice.preferredStable?.conflicts?.firstOrNull { it.type.name == "SharedVersionRef" && it.args.contains("okhttp") })
    }

    @Test
    fun prereleaseAheadOfStable_isNotOutdated() {
        val versions = listOf("1.2.1", "1.3.0-alpha10")
        val advice = buildAdvice(okhttp("1.3.0-alpha10"), versions, origin, inventory(okhttp("1.3.0-alpha10")))
        assertFalse(advice.isOutdated)
        assertEquals(VersionChannel.Alpha, advice.currentChannel)
        assertNull(advice.preferredStable)
    }

    @Test
    fun rcBehindReleasedGa_isOutdated() {
        val versions = listOf("1.2.0-rc01", "1.2.0")
        val advice = buildAdvice(okhttp("1.2.0-rc01"), versions, origin, inventory(okhttp("1.2.0-rc01")))
        assertTrue(advice.isOutdated)
        assertEquals("1.2.0", advice.preferredStable?.version?.raw)
    }

    @Test
    fun stableIsNotOutdatedByCompatFork() {
        val versions = listOf(
            "0.8.0",
            "0.8.0-RC",
            "0.8.0-0.6.x-compat",
            "0.8.0-rc02-0.6.x-compat",
        )
        val advice = buildAdvice(okhttp("0.8.0"), versions, origin, inventory(okhttp("0.8.0")))
        assertFalse(advice.isOutdated)
        assertEquals(VersionChannel.Stable, advice.currentChannel)
        assertNull(advice.preferredStable)
        assertEquals(VersionChannel.ReleaseCandidate, classifyVersion("0.8.0-0.6.x-compat"))
    }

    @Test
    fun compatCurrent_isRcNotStable() {
        val advice = buildAdvice(
            okhttp("0.8.0-0.6.x-compat"),
            listOf("0.8.0", "0.8.0-0.6.x-compat"),
            origin,
            inventory(okhttp("0.8.0-0.6.x-compat")),
        )
        assertEquals(VersionChannel.ReleaseCandidate, advice.currentChannel)
        assertFalse(advice.isOutdated)
        assertNull(advice.preferredStable)
    }

    private fun okhttp(version: String) = DeclaredDependency(
        coordinates = Coordinates("com.squareup.okhttp3", "okhttp"),
        requestedVersion = version,
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
