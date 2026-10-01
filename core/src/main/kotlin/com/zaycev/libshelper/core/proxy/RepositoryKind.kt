package com.zaycev.libshelper.core.proxy

import com.zaycev.libshelper.core.model.HttpProxySettings
import com.zaycev.libshelper.core.model.RepositoryKind
import com.zaycev.libshelper.core.model.RepositoryType
import kotlinx.collections.immutable.toPersistentList

private val OFFICIAL_HOSTS = setOf(
    "repo1.maven.org",
    "repo.maven.apache.org",
    "dl.google.com",
    "maven.google.com",
    "plugins.gradle.org",
    "jitpack.io",
)

private val PROXY_HINTS = listOf(
    "nexus",
    "artifactory",
    "jfrog",
    "pkgs.",
    "mirror",
    "proxy",
    "repository.group",
    "maven.pkg",
)

fun detectRepositoryKind(url: String): RepositoryKind {
    val host = hostOf(url) ?: return RepositoryKind.Private
    if (host in OFFICIAL_HOSTS) {
        return RepositoryKind.Official
    }
    val lowered = url.lowercase()
    if (PROXY_HINTS.any { lowered.contains(it) }) {
        return RepositoryKind.ProxyMirror
    }
    return RepositoryKind.Private
}

fun detectRepositoryType(url: String, declaredName: String? = null): RepositoryType {
    val host = hostOf(url).orEmpty()
    val name = declaredName.orEmpty().lowercase()
    val lowered = url.lowercase()
    return when {
        looksGoogle(name, host, lowered) -> RepositoryType.Google
        looksPluginPortal(name, host, lowered) -> RepositoryType.PluginPortal
        looksJitPack(name, host, lowered) -> RepositoryType.Maven
        looksMavenCentral(name, host, lowered) -> RepositoryType.MavenCentral
        name.contains("local") -> RepositoryType.MavenLocal
        name.contains("flat") -> RepositoryType.FlatDir
        else -> RepositoryType.Maven
    }
}

private fun looksGoogle(name: String, host: String, url: String): Boolean =
    name.contains("google") ||
        host.contains("dl.google.com") ||
        host.contains("maven.google.com") ||
        url.contains("maven-proxy-google") ||
        url.contains("googleapis")

private fun looksPluginPortal(name: String, host: String, url: String): Boolean =
    name.contains("plugin") ||
        host.contains("plugins.gradle.org") ||
        url.contains("gradle-plugins") ||
        url.contains("plugin-portal")

private fun looksJitPack(name: String, host: String, url: String): Boolean =
    name.contains("jitpack") || host.contains("jitpack.io") || url.contains("jitpack")

private fun looksMavenCentral(name: String, host: String, url: String): Boolean =
    name.contains("central") ||
        host.contains("maven.org") ||
        url.contains("gitverse") ||
        url.contains("maven-proxy")

fun officialMavenCentral(): String = "https://repo1.maven.org/maven2/"

fun officialGoogleMaven(): List<String> = listOf(
    "https://dl.google.com/dl/android/maven2/",
    "https://maven.google.com/",
)

fun officialPluginPortal(): String = "https://plugins.gradle.org/m2/"

fun officialJitPack(): String = "https://jitpack.io/"

fun officialJetBrainsCompose(): String = "https://maven.pkg.jetbrains.space/public/p/compose/dev/"

fun isPublicCatalogHost(host: String): Boolean {
    val normalized = host.lowercase()
    return normalized in PUBLIC_CATALOG_HOSTS
}

private val PUBLIC_CATALOG_HOSTS = setOf(
    "repo1.maven.org",
    "repo.maven.apache.org",
    "dl.google.com",
    "maven.google.com",
    "plugins.gradle.org",
    "jitpack.io",
    "maven.pkg.jetbrains.space",
)

fun hostOf(url: String): String? = runCatching {
    val normalized = if (url.contains("://")) url else "https://$url"
    java.net.URI(normalized).host?.lowercase()
}.getOrNull()

fun parseHttpProxy(properties: Map<String, String>): HttpProxySettings? {
    val host = properties["systemProp.https.proxyHost"]
        ?: properties["systemProp.http.proxyHost"]
        ?: return null
    val port = (properties["systemProp.https.proxyPort"]
        ?: properties["systemProp.http.proxyPort"]
        ?: "8080").toIntOrNull() ?: 8080
    val nonProxy = properties["systemProp.http.nonProxyHosts"].orEmpty()
        .split("|")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    return HttpProxySettings(host = host, port = port, nonProxyHosts = nonProxy.toPersistentList())
}

fun parseGradleProperties(text: String): Map<String, String> =
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .associate { line ->
            val idx = line.indexOf('=')
            line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }

fun shouldBypassHttpProxy(url: String, proxy: HttpProxySettings?): Boolean {
    if (proxy == null) return true
    val host = hostOf(url) ?: return false
    return proxy.nonProxyHosts.any { pattern ->
        if (pattern.startsWith("*.")) host.endsWith(pattern.removePrefix("*"))
        else host == pattern
    }
}
