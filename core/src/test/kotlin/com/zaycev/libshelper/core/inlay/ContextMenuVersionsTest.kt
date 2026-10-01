package com.zaycev.libshelper.core.inlay

import com.zaycev.libshelper.core.model.Coordinates
import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.DependencySource
import com.zaycev.libshelper.core.model.MetadataOrigin
import com.zaycev.libshelper.core.model.MetadataOriginKind
import com.zaycev.libshelper.core.model.UpdateAdvice
import com.zaycev.libshelper.core.model.VersionCandidate
import com.zaycev.libshelper.core.model.VersionChannel
import com.zaycev.libshelper.core.versioning.MavenVersion
import kotlin.test.Test
import kotlin.test.assertEquals

class ContextMenuVersionsTest {
    @Test
    fun keepsNewestStablesAndNewerRcBeta() {
        val advice = advice(
            "1.0.0",
            candidate("0.9.0", VersionChannel.Stable),
            candidate("1.0.0", VersionChannel.Stable),
            candidate("1.1.0", VersionChannel.Stable),
            candidate("1.2.0", VersionChannel.Stable),
            candidate("1.3.0", VersionChannel.Stable),
            candidate("1.4.0", VersionChannel.Stable),
            candidate("1.4.0-rc2", VersionChannel.ReleaseCandidate),
            candidate("1.5.0-rc1", VersionChannel.ReleaseCandidate),
            candidate("1.5.0-beta1", VersionChannel.Beta),
            candidate("9.0.0-alpha1", VersionChannel.Alpha),
            candidate("9.0.0-SNAPSHOT", VersionChannel.Snapshot),
        )

        assertEquals(
            listOf("1.4.0", "1.3.0", "1.2.0", "1.5.0-rc1", "1.5.0-beta1"),
            contextMenuVersions(advice).map { it.version.raw },
        )
    }

    @Test
    fun skipsPrereleaseThatIsNotNewer() {
        val advice = advice(
            "2.0.0",
            candidate("2.0.0", VersionChannel.Stable),
            candidate("1.9.0-rc1", VersionChannel.ReleaseCandidate),
            candidate("1.8.0-beta2", VersionChannel.Beta),
        )

        assertEquals(emptyList(), contextMenuVersions(advice).map { it.version.raw })
    }

    private fun advice(current: String, vararg candidates: VersionCandidate) = UpdateAdvice(
        dependency = DeclaredDependency(
            coordinates = Coordinates("com.example", "demo"),
            requestedVersion = current,
            configuration = "implementation",
            module = ":app",
            source = DependencySource.Toml,
        ),
        current = MavenVersion.parse(current),
        currentChannel = VersionChannel.Stable,
        isOutdated = true,
        preferredStable = null,
        latestRc = null,
        latestBeta = null,
        latestAlpha = null,
        candidates = candidates.toList(),
    )

    private fun candidate(raw: String, channel: VersionChannel) = VersionCandidate(
        version = MavenVersion.parse(raw),
        channel = channel,
        origin = MetadataOrigin(MetadataOriginKind.OfficialDirect, "https://example.test"),
    )
}
