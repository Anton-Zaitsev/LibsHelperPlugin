package com.zaycev.libshelper.core.versioning

import com.zaycev.libshelper.core.model.VersionChannel

private val DYNAMIC = Regex(""".*[+]|^.*\.\+$|^.*\+$""")
private val DEV_BUILD = Regex("""(?i)(?:^|[.\-_+])dev(?:[.\-_]?\d+)?$|(?:^|[.\-_])eap(?:[.\-_]\d+)?$""")
private val RANGE = Regex("""^[\[(].*[\])]$""")
private val SNAPSHOT = Regex("""(?i)(^|[.\-_])snapshot([.\-_]|$)|-snapshot$""")
private val COMPAT = setOf("compat")

fun classifyVersion(raw: String): VersionChannel? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    if (RANGE.matches(value)) return null
    if (DEV_BUILD.containsMatchIn(value)) return VersionChannel.Dev
    if (DYNAMIC.matches(value) || value.endsWith(".+") || value == "+") return null

    val qualifiers = MavenVersion.parse(value).tokens
        .filterIsInstance<VersionToken.Qualifier>()
        .map { it.normalized }

    if (qualifiers.any { it in EXCLUDED }) return null

    return when {
        SNAPSHOT.containsMatchIn(value) || qualifiers.any { it == "snapshot" } -> VersionChannel.Snapshot
        qualifiers.any { it == "dev" || it == "eap" } -> VersionChannel.Dev
        qualifiers.any { it == "alpha" } -> VersionChannel.Alpha
        qualifiers.any { it == "beta" } -> VersionChannel.Beta
        qualifiers.any { it == "rc" } -> VersionChannel.ReleaseCandidate
        qualifiers.any { it in COMPAT } -> VersionChannel.ReleaseCandidate
        else -> VersionChannel.Stable
    }
}

private val EXCLUDED = setOf(
    "milestone",
    "preview",
    "canary",
    "pre",
)

data class ChannelLatest(
    val stable: MavenVersion?,
    val rc: MavenVersion?,
    val beta: MavenVersion?,
    val alpha: MavenVersion?,
    val snapshot: MavenVersion? = null,
    val dev: MavenVersion? = null,
)

fun latestPerChannel(versions: Collection<String>): ChannelLatest {
    val grouped = versions
        .mapNotNull { raw -> classifyVersion(raw)?.let { it to MavenVersion.parse(raw) } }
        .groupBy({ it.first }, { it.second })
    return ChannelLatest(
        stable = grouped[VersionChannel.Stable]?.maxOrNull(),
        rc = grouped[VersionChannel.ReleaseCandidate]?.maxOrNull(),
        beta = grouped[VersionChannel.Beta]?.maxOrNull(),
        alpha = grouped[VersionChannel.Alpha]?.maxOrNull(),
        snapshot = grouped[VersionChannel.Snapshot]?.maxOrNull(),
        dev = grouped[VersionChannel.Dev]?.maxOrNull(),
    )
}

fun sameMajorStables(current: MavenVersion, stables: Collection<MavenVersion>): List<MavenVersion> {
    val major = current.tokens.firstOrNull() as? VersionToken.Number ?: return emptyList()
    return stables.filter { version ->
        val other = version.tokens.firstOrNull() as? VersionToken.Number
        other != null && other.value == major.value
    }.sorted()
}
