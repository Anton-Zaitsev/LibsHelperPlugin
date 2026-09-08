package com.zaycev.libshelper.core.cache

interface MetadataCache {
    fun get(key: CacheKey, nowEpochMs: Long): CachedMetadata?

    fun put(key: CacheKey, value: CachedMetadata)

    fun clear()

    fun flush()
}
