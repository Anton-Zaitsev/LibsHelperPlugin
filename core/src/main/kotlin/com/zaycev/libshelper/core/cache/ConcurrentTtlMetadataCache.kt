package com.zaycev.libshelper.core.cache

import com.zaycev.libshelper.core.di.AppScope
import com.zaycev.libshelper.core.log.LibsHelperLogger
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFileAttributeView
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class ConcurrentTtlMetadataCache(
    private val cacheDirectory: Path,
    private val logger: LibsHelperLogger,
) : MetadataCache {
    private val ttlMs: Long = DEFAULT_TTL_MS
    private val memory = ConcurrentHashMap<CacheKey, CachedMetadata>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        runCatching { loadFromDisk() }
            .onFailure { logger.warn("Не удалось прочитать диск-кеш метаданных", it) }
    }

    override fun get(key: CacheKey, nowEpochMs: Long): CachedMetadata? {
        val cached = memory[key] ?: return null
        val limit = if (cached.negative) NEGATIVE_TTL_MS else ttlMs
        val age = nowEpochMs - cached.storedAtEpochMs
        if (age < 0 || age > limit) {
            memory.remove(key, cached)
            return null
        }
        return cached
    }

    override fun put(key: CacheKey, value: CachedMetadata) {
        memory[key] = value
    }

    override fun clear() {
        memory.clear()
        runCatching {
            val file = cacheFile()
            if (file.exists()) Files.deleteIfExists(file)
        }.onFailure { logger.warn("Не удалось очистить диск-кеш метаданных", it) }
    }

    override fun flush() {
        runCatching { persist() }
            .onFailure { logger.warn("Не удалось записать диск-кеш метаданных", it) }
    }

    private fun cacheFile(): Path {
        Files.createDirectories(cacheDirectory)
        restrictToOwner(cacheDirectory, directory = true)
        return cacheDirectory.resolve("metadata-cache.json")
    }

    private fun loadFromDisk() {
        val file = cacheFile()
        if (!file.exists()) return
        if (Files.size(file) > MAX_CACHE_BYTES) {
            logger.warn("Диск-кеш метаданных больше лимита, пропускаем чтение")
            return
        }
        val snapshot = json.decodeFromString<Map<String, CachedMetadata>>(file.readText())
        snapshot.forEach { (encoded, value) ->
            val parts = encoded.split('\u0001', limit = 2)
            if (parts.size == 2) {
                memory[CacheKey(parts[0], parts[1])] = value
            }
        }
    }

    private fun persist() {
        val snapshot = memory.entries.associate { (key, value) ->
            "${key.coordinates}\u0001${key.url}" to value
        }
        val file = cacheFile()
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        tmp.writeText(json.encodeToString(snapshot))
        try {
            Files.move(
                tmp,
                file,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        restrictToOwner(file, directory = false)
    }

    private fun restrictToOwner(path: Path, directory: Boolean) {
        val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java) ?: return
        val permissions = if (directory) {
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
        } else {
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        }
        runCatching { view.setPermissions(permissions) }
    }

    private companion object {
        const val DEFAULT_TTL_MS: Long = 6 * 60 * 60 * 1000
        const val NEGATIVE_TTL_MS: Long = 15 * 60 * 1000
        const val MAX_CACHE_BYTES: Long = 8L * 1024 * 1024
    }
}
