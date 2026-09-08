package com.zaycev.libshelper.core.metadata

import com.zaycev.libshelper.core.cache.CacheKey
import com.zaycev.libshelper.core.cache.CachedMetadata
import com.zaycev.libshelper.core.cache.MetadataCache
import com.zaycev.libshelper.core.model.ConnectStatus
import com.zaycev.libshelper.core.model.Coordinates
import com.zaycev.libshelper.core.model.DeclaredRepository
import com.zaycev.libshelper.core.model.HttpProxySettings
import com.zaycev.libshelper.core.model.MetadataOrigin
import com.zaycev.libshelper.core.model.MetadataOriginKind
import com.zaycev.libshelper.core.model.RepositoryKind
import com.zaycev.libshelper.core.network.HttpFailure
import com.zaycev.libshelper.core.network.HttpGetResult
import com.zaycev.libshelper.core.network.MetadataGateway
import kotlinx.coroutines.CancellationException

suspend fun lookupMetadata(
    coordinates: Coordinates,
    isPlugin: Boolean,
    projectRepositories: List<DeclaredRepository>,
    httpProxy: HttpProxySettings?,
    gateway: MetadataGateway,
    cache: MetadataCache? = null,
    forceRefresh: Boolean = false,
    nowEpochMs: Long = System.currentTimeMillis(),
    onAttempt: (url: String, fromCache: Boolean, outcome: String, durationMs: Long) -> Unit = { _, _, _, _ -> },
): MetadataLookup {
    val path = metadataPath(coordinates.group, coordinates.artifact)
    val officialUrls = officialBasesFor(coordinates, isPlugin).map { it.trimEnd('/') + "/" + path }
    val statuses = mutableListOf<DeclaredRepository>()
    var officialError: String? = null

    for (url in officialUrls) {
        val cached = cachedHit(cache, coordinates, url, forceRefresh, nowEpochMs)
        if (cached != null) {
            onAttempt(url, true, "cache", 0)
            return MetadataLookup(
                resolved = cached.toResolved(),
                officialError = null,
                proxyError = null,
                repositoryStatuses = statuses,
                lastUrl = url,
            )
        }
        val result = getSafely(gateway, url, httpProxy = null)
        when (result) {
            is HttpGetResult.Success -> {
                val parsed = parseMavenMetadata(result.body)
                statuses += repositoryStatusOf(url, ConnectStatus.Online, null, official = true)
                val resolved = ResolvedMetadata(
                    metadata = parsed,
                    origin = MetadataOrigin(MetadataOriginKind.OfficialDirect, url),
                    usedProxyFallback = false,
                    durationMs = result.durationMs,
                    fromCache = false,
                )
                cache?.put(CacheKey(coordinates.key, url), resolved.toCached(nowEpochMs))
                onAttempt(url, false, "ok ${result.status}", result.durationMs)
                return MetadataLookup(resolved, null, null, statuses, url)
            }
            is HttpGetResult.Failure -> {
                officialError = result.error.userMessage
                statuses += repositoryStatusOf(
                    url,
                    connectStatusOf(result.error),
                    result.error.userMessage,
                    official = true,
                )
                onAttempt(url, false, result.error.userMessage, result.durationMs)
            }
        }

        if (httpProxy != null) {
            val viaProxy = getSafely(gateway, url, httpProxy)
            if (viaProxy is HttpGetResult.Success) {
                val parsed = parseMavenMetadata(viaProxy.body)
                val resolved = ResolvedMetadata(
                    metadata = parsed,
                    origin = MetadataOrigin(MetadataOriginKind.OfficialViaHttpProxy, url),
                    usedProxyFallback = false,
                    durationMs = viaProxy.durationMs,
                    fromCache = false,
                )
                cache?.put(CacheKey(coordinates.key, url), resolved.toCached(nowEpochMs))
                onAttempt(url, false, "ok_http_proxy", viaProxy.durationMs)
                return MetadataLookup(resolved, officialError, null, statuses, url)
            }
            if (viaProxy is HttpGetResult.Failure) {
                officialError = viaProxy.error.userMessage
            }
        }
    }

    val proxyRepos = projectRepositoriesFor(coordinates, isPlugin, projectRepositories)
        .filter { it.kind != RepositoryKind.Official }
    var proxyError: String? = null
    for (repo in proxyRepos) {
        val url = repo.url.trimEnd('/') + "/" + path
        val cached = cachedHit(cache, coordinates, url, forceRefresh, nowEpochMs)
        if (cached != null) {
            onAttempt(url, true, "cache_proxy", 0)
            return MetadataLookup(cached.toResolved(), officialError, null, statuses, url)
        }
        when (val result = getSafely(gateway, url, httpProxy = null)) {
            is HttpGetResult.Success -> {
                val parsed = parseMavenMetadata(result.body)
                statuses += repo.copy(connectStatus = ConnectStatus.Online, message = "project_mirror")
                val resolved = ResolvedMetadata(
                    metadata = parsed,
                    origin = MetadataOrigin(MetadataOriginKind.ProjectProxy, url),
                    usedProxyFallback = true,
                    durationMs = result.durationMs,
                    fromCache = false,
                )
                cache?.put(CacheKey(coordinates.key, url), resolved.toCached(nowEpochMs))
                onAttempt(url, false, "ok_proxy", result.durationMs)
                return MetadataLookup(resolved, officialError, null, statuses, url)
            }
            is HttpGetResult.Failure -> {
                proxyError = result.error.userMessage
                statuses += repo.copy(
                    connectStatus = connectStatusOf(result.error),
                    message = result.error.userMessage,
                )
                onAttempt(url, false, result.error.userMessage, result.durationMs)
            }
        }
    }

    return MetadataLookup(
        resolved = null,
        officialError = officialError,
        proxyError = proxyError,
        repositoryStatuses = statuses,
        lastUrl = officialUrls.firstOrNull() ?: proxyRepos.firstOrNull()?.url,
    )
}

private fun cachedHit(
    cache: MetadataCache?,
    coordinates: Coordinates,
    url: String,
    forceRefresh: Boolean,
    nowEpochMs: Long,
): CachedMetadata? {
    if (forceRefresh || cache == null) return null
    return cache.get(CacheKey(coordinates.key, url), nowEpochMs)
}

private fun CachedMetadata.toResolved(): ResolvedMetadata = ResolvedMetadata(
    metadata = MavenMetadata(null, null, versions, latestTag, releaseTag),
    origin = MetadataOrigin(originKind, originUrl),
    usedProxyFallback = usedProxyFallback,
    fromCache = true,
)

private fun ResolvedMetadata.toCached(nowEpochMs: Long): CachedMetadata = CachedMetadata(
    versions = metadata.versions,
    latestTag = metadata.latestTag,
    releaseTag = metadata.releaseTag,
    originKind = origin.kind,
    originUrl = origin.url,
    storedAtEpochMs = nowEpochMs,
    usedProxyFallback = usedProxyFallback,
)

private suspend fun getSafely(
    gateway: MetadataGateway,
    url: String,
    httpProxy: HttpProxySettings?,
): HttpGetResult {
    return try {
        gateway.get(url, httpProxy)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        HttpGetResult.Failure(
            HttpFailure.Unreachable(
                url,
                error.message ?: "Сетевая ошибка",
            ),
            durationMs = 0,
        )
    }
}
