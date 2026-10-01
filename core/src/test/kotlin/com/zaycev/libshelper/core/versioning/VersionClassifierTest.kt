package com.zaycev.libshelper.core.versioning

import com.zaycev.libshelper.core.model.VersionChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionClassifierTest {
    @Test
    fun classifiesKnownChannels() {
        val cases = listOf(
            "1.0.0" to VersionChannel.Stable,
            "32.1.0" to VersionChannel.Stable,
            "1.0.0.Final" to VersionChannel.Stable,
            "1.0.0.RELEASE" to VersionChannel.Stable,
            "1.0.0-rc01" to VersionChannel.ReleaseCandidate,
            "1.0.0-RC" to VersionChannel.ReleaseCandidate,
            "0.8.0-0.6.x-compat" to VersionChannel.ReleaseCandidate,
            "0.8.0-rc02-0.6.x-compat" to VersionChannel.ReleaseCandidate,
            "1.0.0-beta02" to VersionChannel.Beta,
            "1.0.0-alpha14" to VersionChannel.Alpha,
            "1.0.0-SNAPSHOT" to VersionChannel.Snapshot,
        )
        cases.forEach { (raw, expected) ->
            assertEquals(expected, classifyVersion(raw), raw)
        }
    }

    @Test
    fun rejectsDynamicsAndMilestones() {
        assertNull(classifyVersion("1.+"))
        assertNull(classifyVersion("+"))
        assertNull(classifyVersion("1.0.0-M1"))
    }

    @Test
    fun comparatorOrdersQualifiers() {
        assertTrue(MavenVersion("1.0.0-alpha14") < MavenVersion("1.0.0-beta02"))
        assertTrue(MavenVersion("1.0.0-beta02") < MavenVersion("1.0.0-rc01"))
        assertTrue(MavenVersion("1.0.0-rc01") < MavenVersion("1.0.0"))
        assertTrue(MavenVersion("4.12.2") < MavenVersion("5.0.0"))
    }

    @Test
    fun latestPerChannelPicksNewestOfEach() {
        val latest = latestPerChannel(
            listOf(
                "4.12.0",
                "4.12.2",
                "5.0.0",
                "5.1.0-rc02",
                "5.1.0-beta04",
                "5.2.0-alpha03",
                "5.2.0-SNAPSHOT",
            ),
        )
        assertEquals("5.0.0", latest.stable?.raw)
        assertEquals("5.1.0-rc02", latest.rc?.raw)
        assertEquals("5.1.0-beta04", latest.beta?.raw)
        assertEquals("5.2.0-alpha03", latest.alpha?.raw)
        assertEquals("5.2.0-SNAPSHOT", latest.snapshot?.raw)
    }

    @Test
    fun latestStableIgnoresCompatForks() {
        val latest = latestPerChannel(
            listOf(
                "0.8.0",
                "0.8.0-RC",
                "0.8.0-0.6.x-compat",
                "0.8.0-rc02-0.6.x-compat",
            ),
        )
        assertEquals("0.8.0", latest.stable?.raw)
        assertNotNull(latest.rc)
        assertEquals(VersionChannel.ReleaseCandidate, classifyVersion(checkNotNull(latest.rc).raw))
    }

    @Test
    fun rangesAreNotStable() {
        assertNull(classifyVersion("[1.0,2.0)"))
        assertNull(classifyVersion("(,2.0]"))
    }

    @Test
    fun devAndEapAreTheirOwnChannel() {
        assertEquals(VersionChannel.Dev, classifyVersion("1.9.0+dev1234"))
        assertEquals(VersionChannel.Dev, classifyVersion("1.9.0-dev-1234"))
        assertEquals(VersionChannel.Dev, classifyVersion("2.0.0-eap"))
    }

    @Test
    fun mavenOrderingMatchesComparableVersion() {
        assertEquals(0, MavenVersion.parse("1").compareTo(MavenVersion.parse("1.0.0")))
        assertEquals(0, MavenVersion.parse("1.0.0.Final").compareTo(MavenVersion.parse("1.0.0")))
        assertEquals(0, MavenVersion.parse("1.0-ga").compareTo(MavenVersion.parse("1.0")))
        assertEquals(0, MavenVersion.parse("1.0-RC1").compareTo(MavenVersion.parse("1.0-rc1")))
        assertTrue(MavenVersion.parse("1.0-SNAPSHOT") < MavenVersion.parse("1.0"))
        assertTrue(MavenVersion.parse("1.0") < MavenVersion.parse("1.0-sp1"))
        val huge = "1." + "9".repeat(20)
        val smaller = "1." + "8".repeat(20)
        assertTrue(MavenVersion.parse(smaller) < MavenVersion.parse(huge))
    }

    @Test
    fun qualifierAliasesCompareEqual() {
        assertEquals(0, MavenVersion.parse("1.0-a1").compareTo(MavenVersion.parse("1.0-alpha1")))
        assertEquals(0, MavenVersion.parse("1.0-b2").compareTo(MavenVersion.parse("1.0-beta2")))
        assertEquals(0, MavenVersion.parse("1.0-cr1").compareTo(MavenVersion.parse("1.0-rc1")))
        assertTrue(MavenVersion.parse("1.0.0-alpha10") > MavenVersion.parse("1.0.0-alpha9"))
    }
}
