package com.zaycev.libshelper.core.auth

import java.util.Base64

fun RepositoryAuth.requestHeaders(): Map<String, String> {
    if (isBlank) return emptyMap()
    return when (scheme) {
        RepositoryAuthScheme.None -> emptyMap()
        RepositoryAuthScheme.Basic -> {
            val token = Base64.getEncoder().encodeToString(
                "$username:$secret".toByteArray(Charsets.UTF_8),
            )
            mapOf("Authorization" to "Basic $token")
        }
        RepositoryAuthScheme.Bearer -> mapOf("Authorization" to "Bearer $secret")
        RepositoryAuthScheme.Header -> {
            val name = headerName.trim()
            if (!isAllowedHeaderName(name)) return emptyMap()
            mapOf(name to secret)
        }
    }
}

internal fun isAllowedHeaderName(name: String): Boolean {
    if (name.length !in 1..MAX_HEADER_NAME) return false
    if (!HEADER_TOKEN.matches(name)) return false
    return name.lowercase() !in HOP_BY_HOP
}

private const val MAX_HEADER_NAME = 64

private val HEADER_TOKEN = Regex("""[A-Za-z0-9!#$%&'*+.^_`|~-]+""")

private val HOP_BY_HOP = setOf(
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
    "host",
    "content-length",
)
