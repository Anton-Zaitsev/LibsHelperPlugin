package com.zaycev.libshelper.core.inventory

import java.nio.file.Path
import kotlin.io.path.isRegularFile

fun pathInsideRoot(root: Path, relative: String): Path? {
    val base = root.toAbsolutePath().normalize()
    val resolved = base.resolve(relative).normalize()
    if (!resolved.startsWith(base)) return null
    return resolved
}

fun safeProjectFile(root: Path, relative: String): Path? =
    pathInsideRoot(root, relative)?.takeIf { it.isRegularFile() }

fun safeProjectFile(root: String, relative: String): Path? =
    runCatching { safeProjectFile(Path.of(root), relative) }.getOrNull()
