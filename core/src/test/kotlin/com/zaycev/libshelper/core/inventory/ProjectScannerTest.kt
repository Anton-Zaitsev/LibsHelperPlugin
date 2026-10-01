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
            root.resolve("app").let { Files.createDirectories(it) }
            root.resolve("app/build.gradle.kts").writeText(
                """
                dependencies {
                    implementation(libs.okhttp)
                }
                """.trimIndent(),
            )
            val inventory = scanProject(root)
            assertTrue(inventory.catalogPresent)
            val okhttp = inventory.dependencies.first { it.catalogAlias == "okhttp" }
            assertEquals(2, okhttp.catalogVersionLine)
            assertEquals("okhttp", okhttp.versionRef)
            val usage = inventory.dependencies.first { it.usagePath == "app/build.gradle.kts" }
            assertEquals(2, usage.catalogVersionLine)
            assertEquals("okhttp", usage.versionRef)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun groovyScriptBundlesAndExtraCatalogAreMerged() {
        val root = Files.createTempDirectory("libupdater-groovy")
        val included = Files.createTempDirectory("libupdater-included")
        try {
            included.resolve("settings.gradle").writeText("rootProject.name = \"included\"\n")
            included.resolve("build.gradle").writeText("dependencies { implementation 'org.example:included:1.0.0' }\n")
            root.resolve("settings.gradle.kts").writeText(
                """
                rootProject.name = "demo"
                includeBuild("${included.toAbsolutePath()}")
                """.trimIndent(),
            )
            Files.createDirectories(root.resolve("gradle"))
            root.resolve("gradle/libs.versions.toml").writeText(
                """
                [versions]
                okhttp = "4.12.0"
                [libraries]
                okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
                retrofit = { module = "com.squareup.retrofit2:retrofit", version.ref = "okhttp" }
                [bundles]
                net = ["okhttp", "retrofit"]
                """.trimIndent(),
            )
            root.resolve("gradle/extra.versions.toml").writeText(
                """
                [versions]
                coil = "2.7.0"
                [libraries]
                coil = { module = "io.coil-kt:coil", version.ref = "coil" }
                """.trimIndent(),
            )
            root.resolve("build.gradle").writeText(
                """
                dependencies {
                    implementation libs.bundles.net
                }
                """.trimIndent(),
            )
            val inventory = scanProject(root)
            assertTrue(inventory.dependencies.any { it.catalogAlias == "coil" })
            assertTrue(inventory.dependencies.any { it.coordinates.artifact == "included" })
            assertTrue(inventory.catalogVersions.containsKey("okhttp"))
        } finally {
            root.toFile().deleteRecursively()
            included.toFile().deleteRecursively()
        }
    }

    @Test
    fun projectDefinedPluginsStayOutOfTheInventory() {
        val root = Files.createTempDirectory("libshelper-local-plugin")
        try {
            root.resolve("settings.gradle.kts").writeText("rootProject.name = \"demo\"\n")
            Files.createDirectories(root.resolve("gradle"))
            root.resolve("gradle/libs.versions.toml").writeText(
                """
                [versions]
                okhttp = "4.12.0"
                local = "1.0.0"
                [libraries]
                okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
                [plugins]
                local = { id = "com.example.local.convention", version.ref = "local" }
                kotlin = { id = "org.jetbrains.kotlin.jvm", version = "2.0.0" }
                """.trimIndent(),
            )
            root.resolve("build.gradle.kts").writeText(
                """
                plugins {
                    alias(libs.plugins.local)
                    alias(libs.plugins.kotlin)
                }
                gradlePlugin {
                    plugins {
                        create("local") {
                            id = "com.example.local.convention"
                        }
                    }
                }
                dependencies {
                    implementation(libs.okhttp)
                }
                """.trimIndent(),
            )
            val inventory = scanProject(root)
            assertTrue(inventory.dependencies.any { it.coordinates.artifact == "okhttp" })
            assertTrue(inventory.dependencies.any { it.isPlugin && it.coordinates.group == "org.jetbrains.kotlin.jvm" })
            assertTrue(inventory.dependencies.none { it.coordinates.group == "com.example.local.convention" })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun precompiledScriptPluginsStayOutOfTheInventory() {
        val root = Files.createTempDirectory("libshelper-precompiled-plugin")
        try {
            root.resolve("settings.gradle.kts").writeText("rootProject.name = \"demo\"\n")
            val packaged = root.resolve("build-logic/convention/src/main/kotlin/ru/zhiraff/convention")
            Files.createDirectories(packaged)
            packaged.resolve("kmp-library.gradle.kts").writeText("plugins {}\n")
            Files.createDirectories(root.resolve("buildSrc/src/main/kotlin"))
            root.resolve("buildSrc/src/main/kotlin/com.example.flat.gradle.kts").writeText("plugins {}\n")
            Files.createDirectories(root.resolve("scripts"))
            root.resolve("scripts/com.example.kept.gradle.kts").writeText("plugins {}\n")
            Files.createDirectories(root.resolve("gradle"))
            root.resolve("gradle/libs.versions.toml").writeText(
                """
                [versions]
                okhttp = "4.12.0"
                [libraries]
                okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
                [plugins]
                local = { id = "ru.zhiraff.convention.kmp-library" }
                flat = { id = "com.example.flat" }
                kept = { id = "com.example.kept" }
                kotlin = { id = "org.jetbrains.kotlin.jvm", version = "2.0.0" }
                """.trimIndent(),
            )
            root.resolve("build.gradle.kts").writeText(
                """
                plugins {
                    alias(libs.plugins.local)
                    alias(libs.plugins.kotlin)
                }
                dependencies {
                    implementation(libs.okhttp)
                }
                """.trimIndent(),
            )
            val inventory = scanProject(root)
            assertTrue(inventory.dependencies.any { it.coordinates.artifact == "okhttp" })
            assertTrue(inventory.dependencies.any { it.isPlugin && it.coordinates.group == "org.jetbrains.kotlin.jvm" })
            assertTrue(inventory.dependencies.any { it.isPlugin && it.coordinates.group == "com.example.kept" })
            assertTrue(inventory.dependencies.none { it.coordinates.group == "ru.zhiraff.convention.kmp-library" })
            assertTrue(inventory.dependencies.none { it.coordinates.group == "com.example.flat" })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun includeBuildInsideTheRootIsNotScannedTwice() {
        val root = Files.createTempDirectory("libshelper-inner-build")
        try {
            Files.createDirectories(root.resolve("nested"))
            root.resolve("settings.gradle.kts").writeText(
                """
                rootProject.name = "demo"
                includeBuild("nested")
                """.trimIndent(),
            )
            root.resolve("nested/settings.gradle.kts").writeText("rootProject.name = \"nested\"\n")
            root.resolve("nested/build.gradle.kts").writeText(
                "dependencies { implementation(\"com.example:inner:1.0.0\") }\n",
            )
            val inventory = scanProject(root)
            assertEquals(1, inventory.dependencies.count { it.coordinates.artifact == "inner" })
            assertTrue(inventory.versionSources.keys.any { it.endsWith("nested/build.gradle.kts") })
            assertTrue(inventory.versionSources.keys.none { it.contains("nested/nested/") })
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
