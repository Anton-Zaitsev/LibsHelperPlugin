package com.zaycev.libshelper.core.inventory

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

fun projectFingerprint(root: Path): String = projectTree(root).fingerprint

private val FINGERPRINT_SKIP = setOf(".git", ".gradle", ".idea", "build", "node_modules", "out")

internal data class ProjectTree(
    val buildScripts: List<Path>,
    val pluginIds: Set<String>,
    val fingerprint: String,
)

internal fun projectTree(root: Path): ProjectTree {
    val normalized = root.toAbsolutePath().normalize()
    val scripts = mutableListOf<Path>()
    val pluginIds = HashSet<String>()
    val textByPath = HashMap<Path, String?>()
    if (Files.exists(normalized)) {
        Files.walkFileTree(
            normalized,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = dir.fileName?.toString().orEmpty()
                    if (dir != normalized && name in FINGERPRINT_SKIP) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = file.name
                    val gradle = name.endsWith(".gradle.kts") || name.endsWith(".gradle")
                    val buildScript = name == "build.gradle.kts" || name == "build.gradle"
                    if (!gradle && !buildScript && !isNamedRootFile(normalized, file)) {
                        return FileVisitResult.CONTINUE
                    }
                    if (gradle) precompiledPluginId(file)?.let { pluginIds.add(it) }
                    if (attrs.size() > MAX_SOURCE_BYTES) {
                        textByPath[file] = null
                        if (buildScript) scripts.add(file)
                        return FileVisitResult.CONTINUE
                    }
                    val text = file.readCappedText()
                    textByPath[file] = text
                    if (buildScript) scripts.add(file)
                    if (gradle && text != null && definesPluginFile(file, text)) {
                        PLUGIN_ID_ASSIGN.findAll(text).forEach { pluginIds.add(it.groupValues[1]) }
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
    return ProjectTree(
        buildScripts = scripts,
        pluginIds = pluginIds,
        fingerprint = fingerprintOf(normalized, textByPath),
    )
}

private fun isNamedRootFile(root: Path, file: Path): Boolean {
    val relative = root.relativize(file).toString().replace('\\', '/')
    return relative in NAMED_ROOT_FILES
}

private fun fingerprintOf(root: Path, textByPath: Map<Path, String?>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val seen = HashSet<Path>()
    NAMED_ROOT_FILES.map { root.resolve(it) }
        .filter { it.isRegularFile() }
        .sortedBy { it.toString() }
        .forEach { path ->
            seen.add(path)
            digest.update(path.toString().toByteArray())
            digest.updateCapped(path, textByPath[path])
        }
    textByPath.keys
        .filter { it !in seen && (it.name == "build.gradle.kts" || it.name == "build.gradle") }
        .sortedBy { it.toString() }
        .forEach { path ->
            digest.update(path.toString().toByteArray())
            digest.updateCapped(path, textByPath[path])
        }
    return digest.digest().joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
}

private fun MessageDigest.updateCapped(path: Path, cached: String?) {
    if (cached != null) {
        update(cached.toByteArray())
        return
    }
    val size = runCatching { Files.size(path) }.getOrDefault(0L)
    update(size.toString().toByteArray())
}

private fun definesPluginFile(file: Path, text: String): Boolean {
    if ("gradlePlugin" in text) return true
    val path = file.toString().replace('\\', '/')
    return "/buildSrc/" in path || "/build-logic/" in path || path.endsWith("/buildSrc")
}

/** Id of a precompiled script plugin: path under `src/main/kotlin` or `src/main/groovy`, with separators turned into dots. */
internal fun precompiledPluginId(file: Path): String? {
    val name = file.fileName?.toString() ?: return null
    val suffix = when {
        name.endsWith(".gradle.kts") -> ".gradle.kts"
        name.endsWith(".gradle") -> ".gradle"
        else -> return null
    }
    val normalized = file.toString().replace('\\', '/')
    val relative = relativeToPrecompiledRoot(normalized) ?: return null
    val id = relative.removeSuffix(suffix).replace('/', '.')
    if (id.isEmpty() || id.startsWith('.') || id.endsWith('.') || ".." in id) return null
    return id
}

private fun relativeToPrecompiledRoot(normalized: String): String? {
    for (root in PRECOMPILED_SOURCE_ROOTS) {
        val token = "$root/"
        val nested = normalized.lastIndexOf("/$token")
        if (nested >= 0) return normalized.substring(nested + token.length + 1)
        if (normalized.startsWith(token)) return normalized.substring(token.length)
    }
    return null
}

private val PRECOMPILED_SOURCE_ROOTS = listOf("src/main/kotlin", "src/main/groovy")

private val PLUGIN_ID_ASSIGN = Regex("""(?:id\s*=\s*|id\.set\(\s*)["']([^"']+)["']""")

private val NAMED_ROOT_FILES = listOf(
    "settings.gradle.kts",
    "settings.gradle",
    "gradle/libs.versions.toml",
    "gradle.properties",
    "build.gradle.kts",
    "build.gradle",
)
