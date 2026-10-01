package com.zaycev.libshelper.core.analytics

private val ShortTlds = setOf("com", "org", "io", "net", "ru", "co", "dev", "me", "ai", "app")

private val PublicOrgPrefixes = listOf(
    "androidx",
    "org.jetbrains",
    "org.gradle",
    "com.android",
    "com.google",
    "com.squareup",
    "com.github",
    "io.github",
    "io.ktor",
    "io.insert-koin",
    "org.koin",
    "org.junit",
    "org.mockito",
    "junit",
    "javax",
    "jakarta",
    "org.apache",
    "org.slf4j",
    "ch.qos",
    "com.fasterxml",
    "com.jakewharton",
    "app.cash",
    "io.coil-kt",
    "io.arrow-kt",
    "io.grpc",
    "org.ow2",
    "org.hamcrest",
    "org.robolectric",
    "org.bouncycastle",
    "org.json",
    "org.jetbrains.kotlinx",
    "com.russhwolf",
)

internal fun orgPrefix(group: String): String {
    val parts = group.split('.').filter { it.isNotBlank() }
    if (parts.isEmpty()) return group
    return if (parts.first().lowercase() in ShortTlds && parts.size >= 2) {
        "${parts[0]}.${parts[1]}"
    } else {
        parts[0]
    }
}

internal fun isPublicMavenGroup(group: String): Boolean =
    PublicOrgPrefixes.any { prefix -> group == prefix || group.startsWith("$prefix.") }

internal fun looksLikeConventionPlugin(group: String, artifact: String, isPlugin: Boolean): Boolean {
    if (!isPlugin || isPublicMavenGroup(group)) return false
    val blob = "$group:$artifact".lowercase()
    return "convention" in blob || "build-logic" in blob || "buildlogic" in blob
}

internal fun firstPartyPrefixes(dependencies: List<AnalyticsDependency>): Set<String> {
    val fromConvention = dependencies
        .filter { looksLikeConventionPlugin(it.group, it.artifact, it.isPlugin) }
        .map { orgPrefix(it.group) }
    val fromPrivatePlugins = dependencies
        .filter { it.isPlugin && !isPublicMavenGroup(it.group) }
        .groupBy { orgPrefix(it.group) }
        .filterValues { plugins -> plugins.map { it.group }.distinct().size >= 2 }
        .keys
    return (fromConvention + fromPrivatePlugins).toSet()
}

internal fun isFirstParty(
    group: String,
    isLocal: Boolean,
    firstPartyPrefixes: Set<String>,
): Boolean = isLocal || orgPrefix(group) in firstPartyPrefixes
