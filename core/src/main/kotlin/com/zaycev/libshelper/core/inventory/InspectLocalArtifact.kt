package com.zaycev.libshelper.core.inventory

import com.zaycev.libshelper.core.model.Coordinates
import com.zaycev.libshelper.core.model.LocalArtifactKind
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.inputStream
import kotlin.io.path.name

data class LocalArtifactInspect(
    val coordinates: Coordinates,
    val version: String?,
    val fileName: String,
    val kind: LocalArtifactKind,
    val fromPomProperties: Boolean,
)

fun inspectLocalArtifact(file: Path): LocalArtifactInspect {
    val fileName = file.name
    val kind = localArtifactKindOf(fileName)
    val fromPom = readPomProperties(file)
    val parsedName = coordinatesFromFileName(fileName)
    return LocalArtifactInspect(
        coordinates = fromPom?.let { Coordinates(it.groupId, it.artifactId) }
            ?: Coordinates(LOCAL_FILE_GROUP, parsedName.artifact),
        version = fromPom?.version ?: parsedName.version,
        fileName = fileName,
        kind = kind,
        fromPomProperties = fromPom != null,
    )
}

fun inspectFromFileName(fileName: String): LocalArtifactInspect {
    val parsedName = coordinatesFromFileName(fileName)
    return LocalArtifactInspect(
        coordinates = Coordinates(LOCAL_FILE_GROUP, parsedName.artifact),
        version = parsedName.version,
        fileName = fileName,
        kind = localArtifactKindOf(fileName),
        fromPomProperties = false,
    )
}

fun localArtifactKindOf(fileName: String): LocalArtifactKind =
    if (fileName.endsWith(".aar", ignoreCase = true)) LocalArtifactKind.Aar else LocalArtifactKind.Jar

internal data class FileNameCoordinates(
    val artifact: String,
    val version: String?,
)

internal fun coordinatesFromFileName(fileName: String): FileNameCoordinates {
    val base = fileName
        .removeSuffix(".jar")
        .removeSuffix(".JAR")
        .removeSuffix(".aar")
        .removeSuffix(".AAR")
    val match = Regex("""^(.*)-(\d.*)$""").matchEntire(base)
    return if (match != null) {
        FileNameCoordinates(match.groupValues[1], match.groupValues[2])
    } else {
        FileNameCoordinates(base.ifBlank { fileName }, null)
    }
}

private data class PomCoordinates(
    val groupId: String,
    val artifactId: String,
    val version: String?,
)

private fun readPomProperties(file: Path): PomCoordinates? {
    return runCatching {
        file.inputStream().use { input ->
            ZipInputStream(input).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    val name = entry.name.replace('\\', '/')
                    if (name.startsWith("META-INF/maven/") && name.endsWith("pom.properties") && !entry.isDirectory) {
                        val text = zip.readBytes().decodeToString()
                        val props = parsePomProperties(text)
                        val group = props["groupId"]
                        val artifact = props["artifactId"]
                        if (!group.isNullOrBlank() && !artifact.isNullOrBlank()) {
                            return PomCoordinates(group, artifact, props["version"])
                        }
                    }
                }
            }
        }
        null
    }.getOrNull()
}

internal fun parsePomProperties(text: String): Map<String, String> =
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .associate { line ->
            val idx = line.indexOf('=')
            line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }
