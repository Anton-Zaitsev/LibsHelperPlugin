package com.zaycev.libshelper.core.settings

import com.zaycev.libshelper.core.versioning.MavenVersion

data class ApiLevelOffer(
    val api: Int,
    val name: String,
)

data class BuildSettingAdvice(
    val key: String,
    val current: String,
    val role: BuildSettingRole,
    val suggestions: List<String>,
    val note: String?,
    val trackedVersion: String? = null,
)

data class PlatformFacts(
    val stableApis: List<ApiLevelOffer>,
    val playTargetApi: Int?,
    val ndkStable: List<String>,
    val ndkLts: List<String>,
    val jdkLts: List<Int>,
    val jdkCurrent: Int?,
)

fun adviseBuildSetting(
    key: String,
    current: String,
    role: BuildSettingRole,
    facts: PlatformFacts,
): BuildSettingAdvice {
    val tracked = trackedVersion(role, facts)
    val suggestions = when (role) {
        BuildSettingRole.CompileSdk,
        BuildSettingRole.TargetSdk,
        BuildSettingRole.Ndk,
        -> listOfNotNull(tracked).filter { newerVersion(it, current) }
        BuildSettingRole.MinSdk -> emptyList()
        BuildSettingRole.JvmToolchain -> {
            val values = facts.jdkLts.map { it.toString() }.toMutableList()
            facts.jdkCurrent?.toString()?.let { if (it !in values) values += it }
            values.filter { newerNumber(it, current) || it == facts.jdkLts.maxOrNull()?.toString() && it != current }
                .distinct()
        }
        BuildSettingRole.Library, BuildSettingRole.Unknown -> emptyList()
    }
    val note = when (role) {
        BuildSettingRole.MinSdk -> facts.stableApis.firstOrNull { it.api.toString() == current }?.let { level ->
            "API ${level.api} is ${level.name}. Raising minSdk drops older devices."
        }
        BuildSettingRole.TargetSdk -> targetNote(tracked, facts.playTargetApi)
        BuildSettingRole.JvmToolchain -> "Prefer an LTS JDK unless the project already tracks the current release."
        else -> null
    }
    return BuildSettingAdvice(key, current, role, suggestions, note, trackedVersion = tracked)
}

fun adviseProjectSettings(
    usages: List<VersionUsage>,
    versions: Map<String, String>,
    facts: PlatformFacts,
): List<BuildSettingAdvice> {
    val grouped = usages.groupBy { it.key }
    val keys = LinkedHashSet<String>()
    keys += grouped.keys
    versions.keys.filterTo(keys) { roleFromKey(it) != null }
    return keys.mapNotNull { key ->
        val role = roleFor(key, grouped[key].orEmpty())
        if (role != BuildSettingRole.CompileSdk &&
            role != BuildSettingRole.TargetSdk &&
            role != BuildSettingRole.Ndk &&
            role != BuildSettingRole.JvmToolchain
        ) {
            return@mapNotNull null
        }
        adviseBuildSetting(key, versions[key].orEmpty(), role, facts)
    }
}

private fun trackedVersion(role: BuildSettingRole, facts: PlatformFacts): String? = when (role) {
    BuildSettingRole.CompileSdk, BuildSettingRole.TargetSdk ->
        facts.stableApis.maxOfOrNull { it.api }?.toString()
    BuildSettingRole.Ndk -> facts.ndkStable.maxWithOrNull { left, right ->
        MavenVersion.parse(left).compareTo(MavenVersion.parse(right))
    }
    else -> null
}

private fun targetNote(tracked: String?, playTargetApi: Int?): String? {
    val parts = buildList {
        if (tracked != null) add("Latest stable API is $tracked.")
        if (playTargetApi != null) add("Google Play currently expects targetSdk $playTargetApi.")
    }
    return parts.joinToString(" ").ifEmpty { null }
}

private fun roleFor(key: String, group: List<VersionUsage>): BuildSettingRole {
    val fromCall = group.firstOrNull { it.role != BuildSettingRole.Unknown && it.role != BuildSettingRole.Library }?.role
    if (fromCall != null) return fromCall
    if (group.any { it.role == BuildSettingRole.Library }) return BuildSettingRole.Library
    return roleFromKey(key) ?: BuildSettingRole.Unknown
}

private fun roleFromKey(key: String): BuildSettingRole? {
    val separator = key.lastIndexOfAny(charArrayOf('-', '.'))
    if (separator <= 0) return null
    return when (key.substring(separator + 1).lowercase()) {
        "compilesdk" -> BuildSettingRole.CompileSdk
        "targetsdk" -> BuildSettingRole.TargetSdk
        "minsdk" -> BuildSettingRole.MinSdk
        "ndk", "ndkversion" -> BuildSettingRole.Ndk
        else -> null
    }
}

private fun newerVersion(candidate: String, current: String): Boolean =
    MavenVersion.parse(candidate) > MavenVersion.parse(current)

fun parseAndroidRepository(xml: String): List<ApiLevelOffer> {
    val pattern = Regex(
        """<remotePackage path="platforms;android-(\d+)">([\s\S]*?)</remotePackage>""",
    )
    return pattern.findAll(xml).mapNotNull { match ->
        val api = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        val body = match.groupValues[2]
        val codename = Regex("""<codename>([^<]*)</codename>""").find(body)?.groupValues?.get(1)?.trim()
        if (!codename.isNullOrEmpty()) return@mapNotNull null
        if (Regex("""<extension-level>\s*\d+\s*</extension-level>""").containsMatchIn(body) &&
            !body.contains("<api-level>$api</api-level>")
        ) {
            return@mapNotNull null
        }
        val name = Regex("""<description>([^<]*)</description>""").find(body)?.groupValues?.get(1)?.trim()
            ?: "API $api"
        ApiLevelOffer(api, name)
    }.distinctBy { it.api }.sortedBy { it.api }.toList()
}

fun parseNdkVersions(xml: String): List<String> {
    return Regex("""path="ndk;([^"]+)"""").findAll(xml).map { it.groupValues[1] }.distinct().toList()
}

fun parseFoojayMajors(json: String): List<Int> {
    val versions = Regex(""""version"\s*:\s*"(\d+)\.""").findAll(json).mapNotNull {
        it.groupValues[1].toIntOrNull()
    }
    return versions.distinct().sorted().toList()
}

private fun newerNumber(candidate: String, current: String): Boolean {
    val next = candidate.substringBefore('.').toIntOrNull() ?: return candidate != current
    val now = current.substringBefore('.').toIntOrNull() ?: return true
    return next > now
}
