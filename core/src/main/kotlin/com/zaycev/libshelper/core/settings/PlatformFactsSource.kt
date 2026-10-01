package com.zaycev.libshelper.core.settings

import com.zaycev.libshelper.core.di.AppScope
import com.zaycev.libshelper.core.model.HttpProxySettings
import com.zaycev.libshelper.core.network.HttpGetResult
import com.zaycev.libshelper.core.network.MetadataGateway
import com.zaycev.libshelper.core.utils.rethrowIfCancelled
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.concurrent.atomics.AtomicReference
import kotlinx.coroutines.withTimeout

fun interface PlatformFactsSource {
    suspend fun load(httpProxy: HttpProxySettings?): PlatformFacts
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class NetworkPlatformFactsSource(
    private val gateway: MetadataGateway,
) : PlatformFactsSource {
    private val cached = AtomicReference<CachedFacts?>(null)

    override suspend fun load(httpProxy: HttpProxySettings?): PlatformFacts {
        val now = System.currentTimeMillis()
        val proxyKey = httpProxy?.let { "${it.host}:${it.port}" } ?: DIRECT_PROXY
        val hit = cached.load()
        if (hit != null && hit.proxyKey == proxyKey && now - hit.atMs < CACHE_TTL_MS) return hit.facts
        val facts = fetch(httpProxy)
        cached.store(CachedFacts(proxyKey, facts, now))
        return facts
    }

    private suspend fun fetch(httpProxy: HttpProxySettings?): PlatformFacts {
        val android = readBody(ANDROID_REPOSITORY, httpProxy)
        val jdk = readBody(FOOJAY_PACKAGES, httpProxy)
        if (android == null && jdk == null) return emptyFacts()
        return platformFactsOf(android.orEmpty(), jdk.orEmpty())
    }

    private suspend fun readBody(url: String, httpProxy: HttpProxySettings?): String? = try {
        withTimeout(FETCH_TIMEOUT_MS) {
            when (val result = gateway.get(url, httpProxy)) {
                is HttpGetResult.Success -> result.body
                is HttpGetResult.Failure -> null
            }
        }
    } catch (error: Throwable) {
        error.rethrowIfCancelled()
        null
    }

    private data class CachedFacts(val proxyKey: String, val facts: PlatformFacts, val atMs: Long)

    private companion object {
        const val DIRECT_PROXY = "direct"
        const val CACHE_TTL_MS = 43_200_000L
        const val FETCH_TIMEOUT_MS = 4_000L
        const val ANDROID_REPOSITORY = "https://dl.google.com/android/repository/repository2-3.xml"
        const val FOOJAY_PACKAGES =
            "https://api.foojay.io/disco/v3.0/packages?package_type=jdk&release_status=ga&latest=per_version&directly_downloadable=true"
    }
}

fun platformFactsOf(androidXml: String, foojayJson: String): PlatformFacts {
    val apis = parseAndroidRepository(androidXml)
    val ndk = parseNdkVersions(androidXml).sortedWith(::compareNdk)
    val jdks = parseFoojayMajors(foojayJson)
    return PlatformFacts(
        stableApis = apis,
        playTargetApi = apis.maxOfOrNull { it.api },
        ndkStable = ndk.take(2),
        ndkLts = ndk.drop(1).take(2),
        jdkLts = jdks,
        jdkCurrent = jdks.maxOrNull(),
    )
}

fun emptyFacts(): PlatformFacts = PlatformFacts(
    stableApis = emptyList(),
    playTargetApi = null,
    ndkStable = emptyList(),
    ndkLts = emptyList(),
    jdkLts = emptyList(),
    jdkCurrent = null,
)

private fun compareNdk(left: String, right: String): Int {
    val leftParts = left.split('.').map { it.toIntOrNull() ?: 0 }
    val rightParts = right.split('.').map { it.toIntOrNull() ?: 0 }
    val size = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until size) {
        val delta = (rightParts.getOrElse(index) { 0 }) - (leftParts.getOrElse(index) { 0 })
        if (delta != 0) return delta
    }
    return 0
}
