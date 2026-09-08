package com.zaycev.libshelper.core.cache

import java.util.concurrent.ConcurrentHashMap

class InMemoryMetadataCache : MetadataCache {
    private val memory = ConcurrentHashMap<CacheKey, CachedMetadata>()

    override fun get(key: CacheKey, nowEpochMs: Long): CachedMetadata? = memory[key]

    override fun put(key: CacheKey, value: CachedMetadata) {
        memory[key] = value
    }

    override fun clear() {
        memory.clear()
    }

    override fun flush() = Unit
}
