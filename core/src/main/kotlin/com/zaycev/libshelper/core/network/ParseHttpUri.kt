package com.zaycev.libshelper.core.network

import java.net.URI

internal fun parseHttpUri(url: String): URI? {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != HTTPS && scheme != HTTP) return null
    if (uri.host.isNullOrBlank()) return null
    if (uri.userInfo == null) return uri
    return runCatching {
        URI(scheme, null, uri.host, uri.port, uri.path, uri.query, uri.fragment)
    }.getOrNull()
}

private const val HTTPS = "https"
private const val HTTP = "http"
