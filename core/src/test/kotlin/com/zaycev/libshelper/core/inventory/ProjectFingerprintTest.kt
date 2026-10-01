package com.zaycev.libshelper.core.inventory

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectFingerprintTest {
    @Test
    fun fingerprintIsStableAndMatchesTheScan() {
        val root = Files.createTempDirectory("libshelper-fp")
        try {
            write(root)
            val fingerprint = projectFingerprint(root)
            assertEquals(fingerprint, projectFingerprint(root))
            assertEquals(fingerprint, scanProject(root).fingerprint)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun precompiledPluginIdFollowsTheScriptPath() {
        val packaged = Path.of("build-logic/convention/src/main/kotlin/ru/zhiraff/convention/kmp-library.gradle.kts")
        assertEquals("ru.zhiraff.convention.kmp-library", precompiledPluginId(packaged))
        val flat = Path.of("buildSrc/src/main/kotlin/com.example.flat.gradle.kts")
        assertEquals("com.example.flat", precompiledPluginId(flat))
        val groovy = Path.of("build-logic/src/main/groovy/com/example/legacy.gradle")
        assertEquals("com.example.legacy", precompiledPluginId(groovy))
        assertNull(precompiledPluginId(Path.of("scripts/com.example.kept.gradle.kts")))
        assertNull(precompiledPluginId(Path.of("app/src/main/kotlin/com/example/App.kt")))
    }

    private fun write(root: java.nio.file.Path) {
        root.resolve("settings.gradle.kts").writeText("rootProject.name = \"demo\"\n")
        Files.createDirectories(root.resolve("feature"))
        root.resolve("build.gradle.kts").writeText("dependencies {}\n")
        root.resolve("feature/build.gradle.kts").writeText("dependencies {}\n")
    }
}
