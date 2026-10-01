package com.zaycev.libshelper.core.inventory

import com.zaycev.libshelper.core.model.DependencySource
import com.zaycev.libshelper.core.model.RepositoryKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GradleScriptParserTest {
    @Test
    fun multilineLiteralIsReadAndCommentsAreIgnored() {
        val script = """
            dependencies {
                implementation(
                    "com.example:demo:1.2.3"
                )
                // implementation("com.example:commented:9.9.9")
                /* implementation(libs.hidden) */
            }
        """.trimIndent()
        val parsed = parseGradleScript(script, ":app", DependencySource.KotlinDsl, null)
        assertEquals(listOf("demo"), parsed.dependencies.map { it.coordinates.artifact })
        assertEquals(2, parsed.dependencies.single().usageLine)
    }

    @Test
    fun parsesCoordinatesReposAndSdk() {
        val script = """
            repositories {
                google()
                mavenCentral()
                maven { url = uri("https://nexus.company.local/repository/maven-public") }
            }
            dependencies {
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                implementation(platform("androidx.compose:compose-bom:2025.01.00"))
                implementation(libs.okhttp)
            }
            android {
                compileSdk = 35
                defaultConfig { minSdk = 26 }
            }
        """.trimIndent()
        val catalog = parseCatalog(
            """
            [libraries]
            okhttp = { group = "com.squareup.okhttp3", name = "logging-interceptor", version = "4.12.0" }
            """.trimIndent(),
        )
        val parsed = parseGradleScript(script, ":app", DependencySource.KotlinDsl, catalog)
        assertEquals(26, parsed.minSdk)
        assertEquals(35, parsed.compileSdk)
        assertTrue(parsed.dependencies.any { it.coordinates.artifact == "okhttp" })
        assertTrue(parsed.dependencies.any { it.isBom })
        val literal = parsed.dependencies.first { it.coordinates.artifact == "okhttp" && it.catalogAlias == null }
        assertEquals("com.squareup.okhttp3:okhttp:4.12.0", "${literal.coordinates.group}:${literal.coordinates.artifact}:${literal.requestedVersion}")
        assertTrue((literal.usageLine ?: 0) > 0)
        val alias = parsed.dependencies.first { it.catalogAlias == "okhttp" }
        assertTrue((alias.usageLine ?: 0) > 0)
        assertTrue(parsed.repositories.any { it.kind == RepositoryKind.Official })
        assertTrue(parsed.repositories.any { it.kind == RepositoryKind.ProxyMirror })
    }

    @Test
    fun missingCatalogAlias_isReported() {
        val script = """
            dependencies {
                implementation(libs.retrofit)
                implementation(libs.okhttp.logging)
            }
        """.trimIndent()
        val parsed = parseGradleScript(script, ":app", DependencySource.KotlinDsl, catalog = null)
        assertEquals(0, parsed.dependencies.size)
        assertEquals(2, parsed.unresolvedCatalogAliases.size)
        assertTrue(parsed.unresolvedCatalogAliases.any { it.alias == "retrofit" })
        assertTrue(parsed.unresolvedCatalogAliases.any { it.alias == "okhttp.logging" })
    }

    @Test
    fun findsCatalogAliasesInSourceSetsAndCompileOnly() {
        val catalog = parseCatalog(
            """
            [libraries]
            kotlinx-browser = { module = "org.jetbrains.kotlinx:kotlinx-browser", version = "0.3.0" }
            kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version = "2.0.0" }
            """.trimIndent(),
        )
        val parsed = parseGradleScript(
            """
            kotlin {
                sourceSets {
                    commonMain.dependencies {
                        implementation(libs.kotlinx.browser)
                    }
                    commonTest.dependencies {
                        compileOnly(libs.kotlin.test)
                    }
                }
            }
            """.trimIndent(),
            ":shared",
            DependencySource.KotlinDsl,
            catalog,
            relativeScriptPath = "shared/build.gradle.kts",
        )
        assertTrue(parsed.dependencies.any { it.catalogAlias == "kotlinx-browser" })
        assertTrue(parsed.dependencies.any { it.catalogAlias == "kotlin-test" })
        assertEquals("compileOnly", parsed.dependencies.first { it.catalogAlias == "kotlin-test" }.configuration)
        assertEquals(2, parsed.dependencies.count { it.usagePath == "shared/build.gradle.kts" })
    }

    @Test
    fun interpolatedCatalogVersion_resolvesRequestedVersionAndHintLine() {
        val catalog = parseCatalog(
            """
            [versions]
            androidx-activity = "1.13.0"
            """.trimIndent(),
        )
        val parsed = parseGradleScript(
            """
            dependencies {
                implementation("androidx.activity:activity-compose:${'$'}{libs.versions.androidx.activity.get()}")
            }
            """.trimIndent(),
            ":app",
            DependencySource.KotlinDsl,
            catalog,
            relativeScriptPath = "app/build.gradle.kts",
        )
        val activity = parsed.dependencies.single { it.coordinates.artifact == "activity-compose" }
        assertEquals("1.13.0", activity.requestedVersion)
        assertEquals("androidx-activity", activity.versionRef)
        assertEquals(2, activity.catalogVersionLine)
    }
}
