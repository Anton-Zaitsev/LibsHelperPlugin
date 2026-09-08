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
        RepositoryAuthScheme.Header -> mapOf(headerName.trim() to secret)
    }
}
