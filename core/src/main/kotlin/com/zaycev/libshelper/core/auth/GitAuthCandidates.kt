package com.zaycev.libshelper.core.auth

import com.zaycev.libshelper.core.model.RepositoryKind
import com.zaycev.libshelper.core.proxy.detectRepositoryKind
import com.zaycev.libshelper.core.proxy.hostOf
import java.net.URI

private val SCP_GIT = Regex("""^(?:[^@/\s]+@)?([^:/\s]+):(.+)$""")
private val MULTI_PART_PUBLIC_SUFFIXES = setOf("co.uk", "com.au", "com.br", "co.jp", "com.tr", "co.nz")

internal const val GIT_AUTH_CANDIDATE_LIMIT = 8

fun gitAuthCandidateUrls(
    mavenHost: String,
    projectGitRemoteUrls: List<String>,
    limit: Int = GIT_AUTH_CANDIDATE_LIMIT,
): List<String> {
    val host = mavenHost.trim().lowercase()
    if (host.isEmpty() || isOfficialCatalogHost(host)) return emptyList()
    val domain = registrableDomain(host)
    val remotes = projectGitRemoteUrls.mapNotNull(::httpUrlForGitAuth)
    val exact = remotes.filter { hostOf(it) == host }
    val sameDomain = remotes.filter { remote ->
        val remoteHost = hostOf(remote) ?: return@filter false
        remoteHost != host &&
            registrableDomain(remoteHost) == domain &&
            !isOfficialCatalogHost(remoteHost)
    }
    val synthetic = "https://$host/"
    return (exact + sameDomain + synthetic)
        .distinctBy { hostOf(it) ?: it }
        .take(limit.coerceAtLeast(0))
}

fun httpUrlForGitAuth(remote: String): String? {
    val raw = remote.trim()
    if (raw.isEmpty()) return null
    if (raw.contains("://")) return httpsUrlFromUri(raw)
    val scp = SCP_GIT.matchEntire(raw) ?: return null
    val host = scp.groupValues[1].trim().lowercase()
    val path = scp.groupValues[2].trim().removePrefix("/")
    if (host.isEmpty() || path.isEmpty()) return null
    return "https://$host/$path"
}

fun studioGitAuthOf(login: String?, password: String?): RepositoryAuth? {
    val secret = password?.takeIf { it.isNotBlank() } ?: return null
    val user = login?.trim().orEmpty()
    return if (user.isBlank()) {
        RepositoryAuth(RepositoryAuthScheme.Bearer, secret = secret)
    } else {
        RepositoryAuth(RepositoryAuthScheme.Basic, username = user, secret = secret)
    }
}

internal fun registrableDomain(host: String): String {
    val parts = host.lowercase().split('.').filter { it.isNotEmpty() }
    if (parts.size < 2) return host.lowercase()
    val lastTwo = parts.takeLast(2).joinToString(".")
    if (parts.size >= 3 && lastTwo in MULTI_PART_PUBLIC_SUFFIXES) {
        return parts.takeLast(3).joinToString(".")
    }
    return lastTwo
}

private fun isOfficialCatalogHost(host: String): Boolean =
    detectRepositoryKind("https://$host/") == RepositoryKind.Official

private fun httpsUrlFromUri(raw: String): String? {
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme !in HTTPS_SCHEMES) return null
    val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val path = uri.path.orEmpty().ifBlank { "/" }
    val port = uri.port
    val portPart = if (port > 0 && port !in DEFAULT_PORTS) ":$port" else ""
    return "https://$host$portPart$path"
}

private val HTTPS_SCHEMES = setOf("https", "http", "ssh", "git")
private val DEFAULT_PORTS = setOf(80, 443, 22)
