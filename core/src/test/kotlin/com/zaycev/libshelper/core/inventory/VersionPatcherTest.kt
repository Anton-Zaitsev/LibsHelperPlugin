package com.zaycev.libshelper.core.inventory

import com.zaycev.libshelper.core.model.Coordinates
import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.DependencySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class VersionPatcherTest {
    @Test
    fun patchesVersionRefInCatalog() {
        val text = requireNotNull(javaClass.getResource("/fixtures/libs.versions.toml")).readText()
        val patched = checkNotNull(patchCatalogVersionRef(text, "okhttp", "4.12.1"))
        assertEquals("4.12.1", parseCatalog(patched).versions["okhttp"])
        assertEquals("4.12.0", parseCatalog(text).versions["okhttp"])
    }

    @Test
    fun patchesInlineCatalogVersion() {
        val text = requireNotNull(javaClass.getResource("/fixtures/libs.versions.toml")).readText()
        val patched = checkNotNull(patchCatalogInlineVersion(text, "guava", "33.0.0-android"))
        val guava = parseCatalog(patched).libraries.first { it.alias == "guava" }
        assertEquals("33.0.0-android", guava.version)
    }

    @Test
    fun doesNotPatchInlineWhenVersionRefIsUsed() {
        val text = requireNotNull(javaClass.getResource("/fixtures/libs.versions.toml")).readText()
        assertNull(patchCatalogInlineVersion(text, "okhttp", "4.12.1"))
    }

    @Test
    fun rejectsUnsafeVersion() {
        val text = "okhttp = \"4.12.0\"\n"
        assertNull(patchCatalogVersionRef(text, "okhttp", "1.0\"\nevil = \"x"))
    }

    @Test
    fun patchesGradleLiteral() {
        val text = """
            dependencies {
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
            }
        """.trimIndent()
        val patched = checkNotNull(
            patchGradleLiteral(text, "com.squareup.okhttp3", "okhttp", "4.12.0", "4.12.1"),
        )
        assertEquals(true, patched.contains("com.squareup.okhttp3:okhttp:4.12.1"))
        assertEquals(false, patched.contains(":4.12.0"))
    }

    @Test
    fun choosesCatalogRefTarget() {
        val target = versionPatchTarget(
            DeclaredDependency(
                coordinates = Coordinates("com.squareup.okhttp3", "okhttp"),
                requestedVersion = "4.12.0",
                catalogAlias = "okhttp",
                versionRef = "okhttp",
                configuration = "implementation",
                module = ":app",
                source = DependencySource.KotlinDsl,
                catalogPath = "gradle/libs.versions.toml",
                usagePath = "app/build.gradle.kts",
            ),
        )
        assertIs<VersionPatchTarget.CatalogVersionRef>(target)
    }
}
