package com.zaycev.libshelper.core.inventory

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectScannerTest {
    @Test
    fun missingCatalog_keepsScriptDependenciesAndUnresolvedAliases() {
        val root = Files.createTempDirectory("libupdater-nolibs")
        try {
            root.resolve("settings.gradle.kts").writeText(
                """
                pluginManagement {
                    repositories { gradlePluginPortal() }
                }
                dependencyResolutionManagement {
                    repositories {
                        google()
                        mavenCentral()
                        maven { url = uri("https://nexus.company.local/repository/maven-public") }
                    }
                }
                rootProject.name = "demo"
                include(":app")
                """.trimIndent(),
            )
            val app = Files.createDirectories(root.resolve("app"))
            app.resolve("build.gradle.kts").writeText(
                """
                dependencies {
                    implementation("com.squareup.okhttp3:okhttp:4.12.0")
                    implementation(libs.retrofit)
                }
                """.trimIndent(),
            )
            val inventory = scanProject(root)
            assertFalse(inventory.catalogPresent)
            assertEquals(null, inventory.catalogError)
            assertTrue(inventory.dependencies.any { it.coordinates.artifact == "okhttp" })
            assertTrue(inventory.unresolvedCatalogAliases.any { it.alias == "retrofit" })
            assertTrue(inventory.repositories.any { it.url.contains("maven.org") })
            assertTrue(inventory.repositories.any { it.url.contains("nexus.company.local") })
            assertTrue(inventory.moduleGraph.nodes.any { it.id == ":app" })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun kmpModule_exposesTargetsAndProjectEdges() {
        val root = Files.createTempDirectory("libupdater-kmp-graph")
        try {
            root.resolve("settings.gradle.kts").writeText(
                """
                rootProject.name = "demo"
                include(":app", ":shared")
                """.trimIndent(),
            )
            Files.createDirectories(root.resolve("app")).resolve("build.gradle.kts").writeText(
                """
                plugins { id("com.android.application") }
                dependencies { implementation(project(":shared")) }
                """.trimIndent(),
            )
            Files.createDirectories(root.resolve("shared")).resolve("build.gradle.kts").writeText(
                """
                plugins { kotlin("multiplatform") }
                kotlin {
                    androidTarget()
                    jvm()
                    iosArm64()
                }
                """.trimIndent(),
            )
            val inventory = scanProject(root)
            val ids = inventory.moduleGraph.nodes.map { it.id }.toSet()
            assertTrue(ids.containsAll(setOf(":", ":app", ":shared", ":shared#android", ":shared#jvm", ":shared#ios")))
            assertTrue(
                inventory.moduleGraph.links.any { it.fromId == ":app" && it.toId == ":shared" },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun presentCatalog_isDetected() {
        val root = Files.createTempDirectory("libupdater-withlibs")
        try {
            root.resolve("settings.gradle.kts").writeText("rootProject.name = \"demo\"")
            Files.createDirectories(root.resolve("gradle"))
            root.resolve("gradle/libs.versions.toml").writeText(
                """
                [versions]
                okhttp = "4.12.0"
                [libraries]
                okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
                """.trimIndent(),
            )
            val inventory = scanProject(root)
            assertTrue(inventory.catalogPresent)
            val okhttp = inventory.dependencies.first { it.catalogAlias == "okhttp" }
            assertEquals(2, okhttp.catalogVersionLine)
            assertEquals("okhttp", okhttp.versionRef)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
