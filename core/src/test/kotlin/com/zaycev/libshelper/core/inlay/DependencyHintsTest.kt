package com.zaycev.libshelper.core.inlay

import com.zaycev.libshelper.core.inventory.catalogToDependencies
import com.zaycev.libshelper.core.inventory.mergeUniqueDependencies
import com.zaycev.libshelper.core.inventory.parseCatalog
import com.zaycev.libshelper.core.inventory.parseGradleScript
import com.zaycev.libshelper.core.model.ChannelOffer
import com.zaycev.libshelper.core.model.Coordinates
import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.DependencySource
import com.zaycev.libshelper.core.model.LibraryAdvice
import com.zaycev.libshelper.core.model.MetadataOrigin
import com.zaycev.libshelper.core.model.MetadataOriginKind
import com.zaycev.libshelper.core.model.OfferScore
import com.zaycev.libshelper.core.model.ProjectInventory
import com.zaycev.libshelper.core.model.ProjectReport
import com.zaycev.libshelper.core.model.ScanPlan
import com.zaycev.libshelper.core.model.UpdateAdvice
import com.zaycev.libshelper.core.model.VersionChannel
import com.zaycev.libshelper.core.model.resolvedUsageLocations
import com.zaycev.libshelper.core.versioning.MavenVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

class DependencyHintsTest {
    @Test
    fun versionRef_putsHintOnVersionsSectionNotLibraryLine() {
        val catalog = parseCatalog(
            """
            [versions]
            okhttp = "4.12.0"
            [libraries]
            okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
            """.trimIndent(),
        )
        val declared = DeclaredDependency(
            coordinates = Coordinates("com.squareup.okhttp3", "okhttp"),
            requestedVersion = "4.12.0",
            catalogAlias = "okhttp",
            versionRef = "okhttp",
            configuration = "implementation",
            module = ":",
            source = DependencySource.Toml,
            catalogPath = "gradle/libs.versions.toml",
            catalogLine = 4,
            catalogVersionLine = catalog.versionLines["okhttp"],
        )
        val hints = dependencyHints(reportOf(listOf(declared), listOf(advice(declared, outdated = true, recommended = "5.0.0"))))
        assertEquals(1, hints.size)
        assertEquals("gradle/libs.versions.toml", hints.single().relativePath)
        assertEquals(catalog.versionLines["okhttp"], hints.single().line)
        assertEquals("okhttp", hints.single().needle)
        assertEquals(DependencyHintKind.Outdated, hints.single().kind)
        assertEquals("5.0.0", hints.single().recommendedVersion)
    }

    @Test
    fun inlineCatalogVersion_putsHintOnLibraryLine() {
        val declared = DeclaredDependency(
            coordinates = Coordinates("com.google.guava", "guava"),
            requestedVersion = "32.1.0-android",
            catalogAlias = "guava",
            configuration = "implementation",
            module = ":",
            source = DependencySource.Toml,
            catalogPath = "gradle/libs.versions.toml",
            catalogLine = 12,
        )
        val hints = dependencyHints(reportOf(listOf(declared), listOf(advice(declared, outdated = false))))
        assertEquals(12, hints.single().line)
        assertEquals("guava", hints.single().needle)
        assertEquals(DependencyHintKind.Current, hints.single().kind)
    }

    @Test
    fun catalogAliasUsage_emitsTomlAndGradleHints() {
        val catalog = parseCatalog(
            """
            [versions]
            okhttp = "4.12.0"
            [libraries]
            okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
            """.trimIndent(),
        )
        val parsed = parseGradleScript(
            """
            dependencies {
                implementation(libs.okhttp)
            }
            """.trimIndent(),
            ":app",
            DependencySource.KotlinDsl,
            catalog,
            relativeScriptPath = "app/build.gradle.kts",
        )
        val declared = catalogToDependencies(catalog, ":") + parsed.dependencies
        val item = advice(declared.first { it.coordinates.artifact == "okhttp" }, outdated = true, recommended = "5.0.0")
        val hints = dependencyHints(reportOf(declared, listOf(item)))
        assertTrue(hints.any { it.relativePath == "gradle/libs.versions.toml" && it.needle == "okhttp" }, hints.toString())
        assertTrue(hints.any { it.relativePath == "app/build.gradle.kts" && it.needle == "libs.okhttp" }, hints.toString())
    }

    @Test
    fun gradleLiteral_emitsHintOnScriptLine() {
        val declared = DeclaredDependency(
            coordinates = Coordinates("com.squareup.okhttp3", "okhttp"),
            requestedVersion = "4.12.0",
            configuration = "implementation",
            module = ":app",
            source = DependencySource.KotlinDsl,
            usagePath = "app/build.gradle.kts",
            usageLine = 3,
        )
        val hints = dependencyHints(reportOf(listOf(declared), listOf(advice(declared, outdated = true, recommended = "5.0.0"))))
        assertEquals("app/build.gradle.kts", hints.single().relativePath)
        assertEquals("com.squareup.okhttp3:okhttp", hints.single().needle)
        assertEquals(3, hints.single().line)
    }

    @Test
    fun lookupErrorWithoutUpdate_isHidden() {
        val declared = DeclaredDependency(
            coordinates = Coordinates("com.example", "missing"),
            requestedVersion = "1.0.0",
            configuration = "implementation",
            module = ":app",
            source = DependencySource.KotlinDsl,
            usagePath = "app/build.gradle.kts",
            usageLine = 3,
        )
        val hidden = LibraryAdvice(
            advice = advice(declared, outdated = false).advice,
            lookupError = "catalog_silent",
        )
        assertEquals(emptyList(), dependencyHints(reportOf(listOf(declared), listOf(hidden))))
    }

    @Test
    fun sharedVersionLine_keepsSingleOutdatedHint() {
        val first = DeclaredDependency(
            coordinates = Coordinates("com.squareup.okhttp3", "okhttp"),
            requestedVersion = "4.12.0",
            catalogAlias = "okhttp",
            versionRef = "okhttp",
            configuration = "implementation",
            module = ":",
            source = DependencySource.Toml,
            catalogPath = "gradle/libs.versions.toml",
            catalogLine = 8,
            catalogVersionLine = 2,
        )
        val second = first.copy(
            coordinates = Coordinates("com.squareup.okhttp3", "logging-interceptor"),
            catalogAlias = "okhttp-logging",
            catalogLine = 9,
        )
        val hints = dependencyHints(
            reportOf(
                listOf(first, second),
                listOf(
                    advice(first, outdated = true, recommended = "5.0.0"),
                    advice(second, outdated = false),
                ),
            ),
        )
        assertEquals(1, hints.size)
        assertEquals(2, hints.single().line)
        assertEquals(DependencyHintKind.Outdated, hints.single().kind)
    }

    @Test
    fun hintBelongsToLine_matchesVersionKeyWithoutFalsePrefix() {
        assertTrue(hintBelongsToLine("""kotlin-datetime = "0.8.0"""", "kotlin-datetime"))
        assertEquals(false, hintBelongsToLine("""kotlin-datetime-compat = "0.8.0-RC"""", "kotlin-datetime"))
        assertTrue(hintBelongsToLine("""kotlin-datetime-compat = "0.8.0-RC"""", "kotlin-datetime-compat"))
        assertTrue(hintBelongsToLine("    implementation(libs.kotlinx.browser)", "libs.kotlinx.browser"))
    }

    @Test
    fun sourceSetAliases_emitTomlVersionAndEveryGradleUsage() {
        val catalog = parseCatalog(
            """
            [versions]
            kotlinx-browser = "0.3.0"
            kotlin-test = "2.0.0"
            [libraries]
            kotlinx-browser = { module = "org.jetbrains.kotlinx:kotlinx-browser", version.ref = "kotlinx-browser" }
            kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin-test" }
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
            ":",
            DependencySource.KotlinDsl,
            catalog,
            relativeScriptPath = "build.gradle.kts",
        )
        val declared = catalogToDependencies(catalog, ":") + parsed.dependencies
        val libraries = declared
            .distinctBy { it.coordinates.key }
            .map { advice(it, outdated = false) }
        val hints = dependencyHints(reportOf(declared, libraries))
        assertTrue(hints.any { it.relativePath == "gradle/libs.versions.toml" && it.needle == "kotlinx-browser" }, hints.toString())
        assertTrue(hints.any { it.relativePath == "gradle/libs.versions.toml" && it.needle == "kotlin-test" }, hints.toString())
        assertTrue(hints.any { it.relativePath == "build.gradle.kts" && it.needle == "libs.kotlinx.browser" }, hints.toString())
        assertTrue(hints.any { it.relativePath == "build.gradle.kts" && it.needle == "libs.kotlin.test" }, hints.toString())
        assertTrue(hints.none { it.relativePath == "gradle/libs.versions.toml" && it.needle == "kotlinx-browser" && it.line != catalog.versionLines["kotlinx-browser"] })
    }

    @Test
    fun mergeKeepsEveryUsageLine() {
        val catalog = parseCatalog(
            """
            [versions]
            okhttp = "4.12.0"
            [libraries]
            okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
            """.trimIndent(),
        )
        val parsed = parseGradleScript(
            """
            kotlin {
                sourceSets {
                    commonMain.dependencies {
                        implementation(libs.okhttp)
                    }
                    androidMain.dependencies {
                        implementation(libs.okhttp)
                    }
                }
            }
            """.trimIndent(),
            ":",
            DependencySource.KotlinDsl,
            catalog,
            relativeScriptPath = "build.gradle.kts",
        )
        val merged = mergeUniqueDependencies(catalogToDependencies(catalog, ":") + parsed.dependencies)
        val okhttp = merged.single { it.coordinates.artifact == "okhttp" }
        assertEquals(2, okhttp.resolvedUsageLocations().size)
        val hints = dependencyHints(reportOf(listOf(okhttp), listOf(advice(okhttp, outdated = false))))
        assertEquals(2, hints.count { it.relativePath == "build.gradle.kts" })
    }

    @Test
    fun nestedVersionRef_putsHintOnVersionsSection() {
        val catalog = parseCatalog(
            """
            [versions]
            androidx-activity = "1.13.0"
            [libraries]
            androidx-activity = { module = "androidx.activity:activity-compose", version = { ref = "androidx-activity" } }
            """.trimIndent(),
        )
        val declared = catalogToDependencies(catalog, ":").single()
        assertEquals("androidx-activity", declared.versionRef)
        assertEquals(2, declared.catalogVersionLine)
        val hints = dependencyHints(reportOf(listOf(declared), listOf(advice(declared, outdated = true, recommended = "1.14.0"))))
        assertEquals(2, hints.single().line)
        assertEquals("androidx-activity", hints.single().needle)
    }

    @Test
    fun bindHintToCurrentText_followsMovedVersionKey() {
        val stale = DependencyHint(
            relativePath = "gradle/libs.versions.toml",
            line = 40,
            needle = "androidx-activity",
            coordinatesKey = "androidx.activity:activity-compose",
            kind = DependencyHintKind.Outdated,
            currentVersion = "1.12.0",
            recommendedVersion = "1.13.0",
            catalogAlias = "androidx-activity",
        )
        val file = """
            [versions]
            agp = "9.3.2"
            androidx-activity = "1.12.0"
            [libraries]
            androidx-activity = { module = "androidx.activity:activity-compose", version.ref = "androidx-activity" }
        """.trimIndent()
        val bound = checkNotNull(bindHintToCurrentText(stale, file))
        assertEquals(3, bound.line)
    }

    @Test
    fun interpolatedGav_emitsHintOnCatalogVersionLine() {
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
        val declared = parsed.dependencies.single()
        val hints = dependencyHints(reportOf(listOf(declared), listOf(advice(declared, outdated = true, recommended = "1.14.0"))))
        assertTrue(hints.any { it.relativePath == "gradle/libs.versions.toml" && it.needle == "androidx-activity" && it.line == 2 }, hints.toString())
        assertTrue(hints.any { it.relativePath == "app/build.gradle.kts" }, hints.toString())
    }

    private fun reportOf(
        dependencies: List<DeclaredDependency>,
        libraries: List<LibraryAdvice>,
    ) = ProjectReport(
        inventory = ProjectInventory(
            modules = persistentListOf(":app"),
            dependencies = dependencies.toPersistentList(),
            repositories = persistentListOf(),
            httpProxy = null,
        ),
        libraries = libraries.toPersistentList(),
        metadataFromProxyOnly = false,
        scanPlan = ScanPlan(
            catalogPresent = true,
            moduleCount = 1,
            dependencyCount = dependencies.size,
            unresolvedAliasCount = 0,
            httpProxy = null,
            repositories = persistentListOf(),
        ),
    )

    private fun advice(
        dependency: DeclaredDependency,
        outdated: Boolean,
        recommended: String? = null,
    ) = LibraryAdvice(
        advice = UpdateAdvice(
            dependency = dependency,
            current = dependency.requestedVersion?.let { MavenVersion.parse(it) },
            currentChannel = VersionChannel.Stable,
            isOutdated = outdated,
            preferredStable = recommended?.let { version ->
                ChannelOffer(
                    channel = VersionChannel.Stable,
                    version = MavenVersion.parse(version),
                    isPreferred = true,
                    score = OfferScore.Safe,
                    consequences = persistentListOf(),
                    conflicts = persistentListOf(),
                    origin = MetadataOrigin(MetadataOriginKind.OfficialDirect, "https://repo1.maven.org/maven2/"),
                )
            },
            latestRc = null,
            latestBeta = null,
            latestAlpha = null,
        ),
    )
}
