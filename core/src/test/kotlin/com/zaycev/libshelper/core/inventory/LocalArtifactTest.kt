package com.zaycev.libshelper.core.inventory

import com.zaycev.libshelper.core.model.DependencySource
import com.zaycev.libshelper.core.model.LocalArtifactKind
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalArtifactTest {
    @Test
    fun readsPomPropertiesFromJar() {
        val dir = Files.createTempDirectory("libupdater-jar")
        val jar = dir.resolve("okhttp-4.12.0.jar")
        ZipOutputStream(jar.toFile().outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/maven/com.squareup.okhttp3/okhttp/pom.properties"))
            zip.write(
                """
                groupId=com.squareup.okhttp3
                artifactId=okhttp
                version=4.12.0
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
        }
        val inspect = inspectLocalArtifact(jar)
        assertEquals("com.squareup.okhttp3", inspect.coordinates.group)
        assertEquals("okhttp", inspect.coordinates.artifact)
        assertEquals("4.12.0", inspect.version)
        assertEquals(LocalArtifactKind.Jar, inspect.kind)
        assertTrue(inspect.fromPomProperties)
    }

    @Test
    fun filenameFallbackWithoutPom() {
        val inspect = inspectFromFileName("mylib-1.2.3.aar")
        assertEquals(LOCAL_FILE_GROUP, inspect.coordinates.group)
        assertEquals("mylib", inspect.coordinates.artifact)
        assertEquals("1.2.3", inspect.version)
        assertEquals(LocalArtifactKind.Aar, inspect.kind)
    }

    @Test
    fun parseFilesCallInGradleScript() {
        val dir = Files.createTempDirectory("libupdater-mod")
        dir.resolve("legacy.jar").writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0, 0, 0, 8, 0))
        val script = """
            dependencies {
                implementation(files("legacy.jar"))
            }
        """.trimIndent()
        dir.resolve("build.gradle.kts").writeText(script)
        val parsed = parseGradleScript(script, ":app", DependencySource.KotlinDsl, moduleDir = dir)
        assertTrue(parsed.dependencies.any { it.isLocalArtifact && it.localFileName == "legacy.jar" })
    }
}
