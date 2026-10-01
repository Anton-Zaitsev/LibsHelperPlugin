package com.zaycev.libshelper.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildSettingsTest {
    @Test
    fun usageIndexReadsGradleAccessors() {
        val text = """
            android {
                compileSdk = libs.versions.android.compileSdk.get().toInt()
                defaultConfig {
                    minSdk = libs.versions.android.minSdk.get().toInt()
                    targetSdk = libs.versions.android.targetSdk.get().toInt()
                    ndkVersion = libs.versions.android.ndk.get()
                }
            }
            kotlin {
                jvmToolchain(libs.versions.jvm.toolchain.get().toInt())
            }
        """.trimIndent()
        val keys = setOf("android-compileSdk", "android-minSdk", "android-targetSdk", "android-ndk", "jvm-toolchain")
        val usages = indexVersionUsages(listOf(VersionSourceText("app/build.gradle.kts", text)), keys)
        assertEquals(BuildSettingRole.CompileSdk, usages.first { it.key == "android-compileSdk" }.role)
        assertEquals(BuildSettingRole.MinSdk, usages.first { it.key == "android-minSdk" }.role)
        assertEquals(BuildSettingRole.TargetSdk, usages.first { it.key == "android-targetSdk" }.role)
        assertEquals(BuildSettingRole.Ndk, usages.first { it.key == "android-ndk" }.role)
        assertEquals(BuildSettingRole.JvmToolchain, usages.first { it.key == "jvm-toolchain" }.role)
    }

    @Test
    fun renamedCatalogKeyFollowsTheCallSite() {
        val text = """
            val sdk = libs.versions.sdk.get()
            android {
                compileSdk = sdk.toInt()
            }
            dependencies {
                implementation(libs.versions.compileSdk)
            }
        """.trimIndent()
        val usages = indexVersionUsages(
            listOf(VersionSourceText("app/build.gradle.kts", text)),
            setOf("sdk", "compileSdk"),
        )
        assertEquals(BuildSettingRole.CompileSdk, usages.first { it.key == "sdk" && it.role == BuildSettingRole.CompileSdk }.role)
        assertEquals(BuildSettingRole.Library, usages.first { it.key == "compileSdk" }.role)
    }

    @Test
    fun adviceDiffersByRole() {
        val facts = PlatformFacts(
            stableApis = listOf(ApiLevelOffer(28, "Android 9"), ApiLevelOffer(36, "Android 16")),
            playTargetApi = 35,
            ndkStable = listOf("29.0.14206865", "27.2.12479018"),
            ndkLts = listOf("27.2.12479018"),
            jdkLts = listOf(17, 21),
            jdkCurrent = 25,
        )
        val compile = adviseBuildSetting("android-compileSdk", "34", BuildSettingRole.CompileSdk, facts)
        assertTrue("36" in compile.suggestions)
        val min = adviseBuildSetting("android-minSdk", "28", BuildSettingRole.MinSdk, facts)
        assertTrue(min.suggestions.isEmpty())
        assertTrue(min.note.orEmpty().contains("Android 9"))
        val jdk = adviseBuildSetting("jvm-toolchain", "17", BuildSettingRole.JvmToolchain, facts)
        assertTrue("21" in jdk.suggestions)
    }

    @Test
    fun parsersReadSdkAndFoojayPayloads() {
        val xml = """
            <remotePackage path="platforms;android-35">
              <description>Android 15</description>
              <api-level>35</api-level>
            </remotePackage>
            <remotePackage path="platforms;android-36">
              <codename>Baklava</codename>
              <api-level>36</api-level>
            </remotePackage>
            <remotePackage path="ndk;27.2.12479018"/>
        """.trimIndent()
        assertEquals(listOf(35), parseAndroidRepository(xml).map { it.api })
        assertEquals(listOf("27.2.12479018"), parseNdkVersions(xml))
        assertEquals(listOf(17, 21), parseFoojayMajors("""{"version":"17.0.1","version":"21.0.2"}"""))
        val grouped = adviseProjectSettings(
            usages = listOf(VersionUsage("android-compileSdk", "app/build.gradle.kts", 1, BuildSettingRole.CompileSdk)),
            versions = mapOf("android-compileSdk" to "34"),
            facts = platformFactsOf(xml, """{"version":"21.0.2"}"""),
        )
        assertEquals(BuildSettingRole.CompileSdk, grouped.single().role)
    }

    @Test
    fun remainingRolesAndParserEdges() {
        val facts = PlatformFacts(
            stableApis = listOf(ApiLevelOffer(35, "Android 15")),
            playTargetApi = 35,
            ndkStable = listOf("28.2.13676358"),
            ndkLts = listOf("27.2.12479018"),
            jdkLts = listOf(21),
            jdkCurrent = 25,
        )
        val target = adviseBuildSetting("android-targetSdk", "34", BuildSettingRole.TargetSdk, facts)
        assertTrue(target.suggestions.contains("35"))
        assertTrue(target.note.orEmpty().contains("35"))
        val ndk = adviseBuildSetting("android-ndk", "27.2.12479018", BuildSettingRole.Ndk, facts)
        assertTrue("28.2.13676358" in ndk.suggestions)
        val jdk = adviseBuildSetting("jvm-toolchain", "21", BuildSettingRole.JvmToolchain, facts)
        assertTrue("25" in jdk.suggestions)
        val skipped = adviseProjectSettings(
            usages = listOf(
                VersionUsage("compose", "gradle/libs.versions.toml", 1, BuildSettingRole.Library),
                VersionUsage("mystery", "app/build.gradle.kts", 2, BuildSettingRole.Unknown),
            ),
            versions = mapOf("compose" to "1.7.0", "mystery" to "1"),
            facts = facts,
        )
        assertTrue(skipped.isEmpty())
        val xml = """
            <remotePackage path="platforms;android-34">
              <extension-level>7</extension-level>
            </remotePackage>
            <remotePackage path="platforms;android-33"></remotePackage>
        """.trimIndent()
        assertEquals(listOf(33), parseAndroidRepository(xml).map { it.api })
        assertEquals("API 33", parseAndroidRepository(xml).single().name)
        val loose = adviseBuildSetting("android-compileSdk", "abc", BuildSettingRole.CompileSdk, facts)
        assertTrue("35" in loose.suggestions)
    }

    @Test
    fun targetSdkTracksTheLatestStableApi() {
        val facts = PlatformFacts(
            stableApis = listOf(ApiLevelOffer(35, "Android 15"), ApiLevelOffer(37, "Android 17")),
            playTargetApi = 35,
            ndkStable = listOf("29.0.14000000", "29.0.14206865"),
            ndkLts = listOf("27.2.12479018"),
            jdkLts = listOf(21),
            jdkCurrent = 25,
        )
        val behind = adviseBuildSetting("android-targetSdk", "35", BuildSettingRole.TargetSdk, facts)
        assertEquals("37", behind.trackedVersion)
        assertEquals(listOf("37"), behind.suggestions)
        assertTrue(behind.note.orEmpty().contains("37"))
        val current = adviseBuildSetting("android-targetSdk", "37", BuildSettingRole.TargetSdk, facts)
        assertEquals("37", current.trackedVersion)
        assertTrue(current.suggestions.isEmpty())
        val compile = adviseBuildSetting("android-compileSdk", "37", BuildSettingRole.CompileSdk, facts)
        assertEquals("37", compile.trackedVersion)
        assertTrue(compile.suggestions.isEmpty())
        val ndk = adviseBuildSetting("android-ndk", "29.0.14000000", BuildSettingRole.Ndk, facts)
        assertEquals("29.0.14206865", ndk.trackedVersion)
        assertEquals(listOf("29.0.14206865"), ndk.suggestions)
        val latestNdk = adviseBuildSetting("android-ndk", "29.0.14206865", BuildSettingRole.Ndk, facts)
        assertTrue(latestNdk.suggestions.isEmpty())
        val min = adviseBuildSetting("android-minSdk", "28", BuildSettingRole.MinSdk, facts)
        assertEquals(null, min.trackedVersion)
        assertTrue(min.suggestions.isEmpty())
        val unknown = adviseBuildSetting("android-targetSdk", "34", BuildSettingRole.TargetSdk, emptyFacts())
        assertEquals(null, unknown.trackedVersion)
        assertEquals(null, unknown.note)
        assertTrue(unknown.suggestions.isEmpty())
    }

    @Test
    fun projectAdviceSkipsMinSdkAndReadsCatalogKeyNames() {
        val facts = PlatformFacts(
            stableApis = listOf(ApiLevelOffer(36, "Android 16")),
            playTargetApi = 35,
            ndkStable = listOf("29.0.14206865"),
            ndkLts = emptyList(),
            jdkLts = listOf(21),
            jdkCurrent = 21,
        )
        val advised = adviseProjectSettings(
            usages = listOf(
                VersionUsage("compileSdk", "app/build.gradle.kts", 9, BuildSettingRole.Library),
            ),
            versions = mapOf(
                "android-compileSdk" to "34",
                "android-minSdk" to "28",
                "android-targetSdk" to "34",
                "android-ndk" to "27.2.12479018",
                "compileSdk" to "1.0.0",
                "okhttp" to "4.12.0",
            ),
            facts = facts,
        )
        assertEquals(
            setOf("android-compileSdk", "android-targetSdk", "android-ndk"),
            advised.map { it.key }.toSet(),
        )
        assertEquals("36", advised.first { it.key == "android-targetSdk" }.trackedVersion)
        assertEquals(listOf("36"), advised.first { it.key == "android-compileSdk" }.suggestions)
    }
}
