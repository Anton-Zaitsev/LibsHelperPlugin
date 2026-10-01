package com.zaycev.libshelper.core.inventory

import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.DependencySource
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

private val FILES_BLOCK = Regex("""files\s*\(([^)]*)\)""", RegexOption.IGNORE_CASE)
private val ARCHIVE_IN_QUOTES = Regex("""["']([^"']+\.(?:jar|aar))["']""", RegexOption.IGNORE_CASE)
private val FILE_TREE_DIR = Regex(
    """fileTree\s*\(\s*(?:dir\s*[:=]\s*)?["']([^"']+)["']""",
    RegexOption.IGNORE_CASE,
)
private val FLAT_DIR_DIRS = Regex(
    """dirs\s*(?:[=(]\s*|\(\s*)["']([^"']+)["']""",
    RegexOption.IGNORE_CASE,
)

fun parseLocalFileDependencies(
    text: String,
    moduleDir: Path?,
    module: String,
    source: DependencySource,
): List<DeclaredDependency> {
    val paths = linkedSetOf<Path>()
    FILES_BLOCK.findAll(text).forEach { block ->
        ARCHIVE_IN_QUOTES.findAll(block.groupValues[1]).forEach { match ->
            resolveLocal(moduleDir, match.groupValues[1])?.let { paths.add(it) }
        }
    }
    FILE_TREE_DIR.findAll(text).forEach { match ->
        resolveLocal(moduleDir, match.groupValues[1])?.let { paths.addAll(archivesInDir(it)) }
    }
    if (text.contains("flatDir", ignoreCase = true)) {
        FLAT_DIR_DIRS.findAll(text).forEach { match ->
            resolveLocal(moduleDir, match.groupValues[1])?.let { paths.addAll(archivesInDir(it)) }
        }
    }
    return paths.map { path ->
        val inspect = if (path.isRegularFile()) inspectLocalArtifact(path) else inspectFromFileName(path.name)
        DeclaredDependency(
            coordinates = inspect.coordinates,
            requestedVersion = inspect.version,
            configuration = "implementation",
            module = module,
            source = source,
            isLocalArtifact = true,
            localFileName = inspect.fileName,
            localKind = inspect.kind,
        )
    }
}

private fun resolveLocal(moduleDir: Path?, relative: String): Path? {
    val cleaned = relative.trim().removePrefix("./")
    val base = moduleDir?.toAbsolutePath()?.normalize() ?: return Path.of(cleaned).normalize().takeUnless { it.isAbsolute }
    return pathInsideRoot(base, cleaned)
}

private fun archivesInDir(dir: Path): List<Path> {
    if (!dir.isDirectory()) return emptyList()
    return dir.listDirectoryEntries().filter { entry ->
        val name = entry.name
        name.endsWith(".jar", ignoreCase = true) || name.endsWith(".aar", ignoreCase = true)
    }
}
