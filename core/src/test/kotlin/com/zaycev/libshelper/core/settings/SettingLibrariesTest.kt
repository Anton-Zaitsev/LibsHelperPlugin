package com.zaycev.libshelper.core.settings

import com.zaycev.libshelper.core.model.ProjectInventory
import com.zaycev.libshelper.core.model.ProjectReport
import com.zaycev.libshelper.core.model.ScanPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf

class SettingLibrariesTest {
    @Test
    fun trackedSettingsAppearUnderTheirCatalogKey() {
        val toml = """
            [versions]
            android-compileSdk = "34"
            android-minSdk = "28"
            android-targetSdk = "35"
            android-ndk = "29.0.14206865"
        """.trimIndent()
        val report = ProjectReport(
            inventory = ProjectInventory(
                modules = persistentListOf(":"),
                dependencies = persistentListOf(),
                repositories = persistentListOf(),
                httpProxy = null,
                versionSources = mapOf("gradle/libs.versions.toml" to toml),
            ),
            libraries = persistentListOf(),
            metadataFromProxyOnly = false,
            scanPlan = ScanPlan(
                catalogPresent = true,
                moduleCount = 1,
                dependencyCount = 0,
                unresolvedAliasCount = 0,
                httpProxy = null,
                repositories = persistentListOf(),
            ),
            buildSettings = persistentListOf(
                BuildSettingAdvice("android-compileSdk", "34", BuildSettingRole.CompileSdk, listOf("37"), null, "37"),
                BuildSettingAdvice("android-minSdk", "28", BuildSettingRole.MinSdk, emptyList(), "note", null),
                BuildSettingAdvice("android-targetSdk", "35", BuildSettingRole.TargetSdk, listOf("37"), null, "37"),
                BuildSettingAdvice("android-ndk", "29.0.14206865", BuildSettingRole.Ndk, emptyList(), null, "29.0.14206865"),
            ),
        )
        val listed = settingLibraries(report)
        assertEquals(setOf("android-compileSdk", "android-targetSdk", "android-ndk"), listed.map { it.advice.dependency.catalogAlias }.toSet())
        val target = listed.first { it.advice.dependency.catalogAlias == "android-targetSdk" }
        assertTrue(target.advice.isOutdated)
        assertEquals("37", target.advice.preferredStable?.version?.raw)
        assertEquals("gradle/libs.versions.toml", target.advice.dependency.catalogPath)
        assertEquals(4, target.advice.dependency.catalogVersionLine)
        val ndk = listed.first { it.advice.dependency.catalogAlias == "android-ndk" }
        assertTrue(!ndk.advice.isOutdated)
        assertEquals(null, ndk.advice.preferredStable)
    }
}
