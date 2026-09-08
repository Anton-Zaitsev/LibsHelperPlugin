package com.zaycev.libshelper.core.inventory

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

fun projectFingerprint(root: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val names = listOf(
        "settings.gradle.kts",
        "settings.gradle",
        "gradle/libs.versions.toml",
        "gradle.properties",
        "build.gradle.kts",
        "build.gradle",
    )
    names.map { root.resolve(it) }
        .filter { it.isRegularFile() }
        .sortedBy { it.toString() }
        .forEach { path ->
            digest.update(path.toString().toByteArray())
            digest.update(path.readBytes())
        }
    Files.walk(root).use { stream ->
        stream.filter { it.isRegularFile() }
            .filter { file ->
                val name = file.fileName.toString()
                (name == "build.gradle.kts" || name == "build.gradle") &&
                    !root.relativize(file).toString().contains("/build/")
            }
            .sorted()
            .forEach { path ->
                digest.update(path.toString().toByteArray())
                digest.update(path.readBytes())
            }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
}
