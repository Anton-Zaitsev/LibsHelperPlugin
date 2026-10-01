package com.zaycev.libshelper.core.cache

import com.zaycev.libshelper.core.log.NoOpLibsHelperLogger
import com.zaycev.libshelper.core.model.MetadataOriginKind
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiskMetadataCacheTest {
    @Test
    fun flushSurvivesANewCacheAndClearRemovesIt() {
        val directory = Files.createTempDirectory("libshelper-cache")
        try {
            val first = ConcurrentTtlMetadataCache(directory, NoOpLibsHelperLogger())
            val key = CacheKey("com.squareup.okhttp3:okhttp", "https://repo1.maven.org/maven2/")
            first.put(
                key,
                CachedMetadata(
                    versions = listOf("4.12.0"),
                    originKind = MetadataOriginKind.OfficialDirect,
                    originUrl = "https://repo1.maven.org/maven2/",
                    storedAtEpochMs = System.currentTimeMillis(),
                    usedProxyFallback = false,
                ),
            )
            first.flush()
            val second = ConcurrentTtlMetadataCache(directory, NoOpLibsHelperLogger())
            assertEquals(listOf("4.12.0"), second.get(key, System.currentTimeMillis())?.versions)
            second.clear()
            assertNull(ConcurrentTtlMetadataCache(directory, NoOpLibsHelperLogger()).get(key, System.currentTimeMillis()))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun negativeEntriesExpireQuickly() {
        val cache = ConcurrentTtlMetadataCache(Files.createTempDirectory("libshelper-neg"), NoOpLibsHelperLogger())
        val key = CacheKey("g:a", "https://example.test/")
        val stored = System.currentTimeMillis() - 16 * 60 * 1000
        cache.put(
            key,
            CachedMetadata(
                versions = emptyList(),
                originKind = MetadataOriginKind.OfficialDirect,
                originUrl = "https://example.test/",
                storedAtEpochMs = stored,
                usedProxyFallback = false,
                negative = true,
            ),
        )
        assertNull(cache.get(key, System.currentTimeMillis()))
    }

    @Test
    fun clockSkewMakesAFutureEntryExpired() {
        val cache = ConcurrentTtlMetadataCache(Files.createTempDirectory("libshelper-skew"), NoOpLibsHelperLogger())
        val key = CacheKey("g:a", "https://example.test/")
        val now = System.currentTimeMillis()
        cache.put(
            key,
            CachedMetadata(
                versions = listOf("1.0.0"),
                originKind = MetadataOriginKind.OfficialDirect,
                originUrl = "https://example.test/",
                storedAtEpochMs = now + 60_000,
                usedProxyFallback = false,
            ),
        )
        assertNull(cache.get(key, now))
    }
}
