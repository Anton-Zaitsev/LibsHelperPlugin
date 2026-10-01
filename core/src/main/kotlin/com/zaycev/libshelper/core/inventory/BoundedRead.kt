package com.zaycev.libshelper.core.inventory

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.readText

internal const val MAX_SOURCE_BYTES = 2_000_000L
internal const val MAX_ZIP_ENTRY_BYTES = 1_000_000

internal fun Path.readCappedText(maxBytes: Long = MAX_SOURCE_BYTES): String? {
    val size = runCatching { Files.size(this) }.getOrNull() ?: return null
    if (size > maxBytes) return null
    return readText()
}

internal fun ZipInputStream.readCappedBytes(maxBytes: Int = MAX_ZIP_ENTRY_BYTES): ByteArray? {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(chunk)
        if (read < 0) break
        total += read
        if (total > maxBytes) return null
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}
