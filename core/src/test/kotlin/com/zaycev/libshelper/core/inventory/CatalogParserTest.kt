package com.zaycev.libshelper.core.inventory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogParserTest {
    @Test
    fun parsesVersionCatalogFixture() {
        val text = requireNotNull(javaClass.getResource("/fixtures/libs.versions.toml")).readText()
        val catalog = parseCatalog(text)
        assertEquals("4.12.0", catalog.versions["okhttp"])
        val okhttp = catalog.libraries.first { it.alias == "okhttp" }
        assertEquals("com.squareup.okhttp3", okhttp.coordinates.group)
        assertEquals("okhttp", okhttp.coordinates.artifact)
        assertEquals("4.12.0", okhttp.version)
        assertEquals("okhttp", okhttp.versionRef)
        val retrofit = catalog.libraries.first { it.alias == "retrofit" }
        assertEquals("com.squareup.retrofit2", retrofit.coordinates.group)
        assertTrue(catalog.bundles["network"]!!.contains("okhttp"))
        val plugin = catalog.plugins.first { it.alias == "kotlin-android" }
        assertEquals("1.9.24", plugin.version)
        assertTrue(okhttp.line > 0)
        assertTrue(plugin.line > 0)
        assertEquals(2, catalog.versionLines["okhttp"])
        assertEquals(4, catalog.versionLines["kotlin"])
    }

    @Test
    fun compactGav_nestedRef_andSubtable_areParsed() {
        val catalog = parseCatalog(
            """
            [versions]
            okhttp = "4.12.0"
            guava = "32.1.0-android"
            agp = "8.7.0"
            [libraries]
            okhttp = "com.squareup.okhttp3:okhttp:4.12.0"
            logging = { module = "com.squareup.okhttp3:logging-interceptor", version = { ref = "okhttp" } }
            [libraries.guava]
            group = "com.google.guava"
            name = "guava"
            version.ref = "guava"
            [plugins.android]
            id = "com.android.application"
            version.ref = "agp"
            """.trimIndent(),
        )
        val compact = catalog.libraries.first { it.alias == "okhttp" }
        assertEquals("com.squareup.okhttp3", compact.coordinates.group)
        assertEquals("okhttp", compact.coordinates.artifact)
        assertEquals("4.12.0", compact.version)
        val nested = catalog.libraries.first { it.alias == "logging" }
        assertEquals("okhttp", nested.versionRef)
        assertEquals("4.12.0", nested.version)
        val subtable = catalog.libraries.first { it.alias == "guava" }
        assertEquals("com.google.guava", subtable.coordinates.group)
        assertEquals("guava", subtable.versionRef)
        assertEquals("32.1.0-android", subtable.version)
        val plugin = catalog.plugins.single()
        assertEquals("android", plugin.alias)
        assertEquals("com.android.application", plugin.id)
        assertEquals("agp", plugin.versionRef)
        assertEquals("8.7.0", plugin.version)
    }

    @Test
    fun richVersionKeepsNestedBracesAndSingleQuotedBundles() {
        val catalog = parseCatalog(
            """
            [versions]
            okhttp = { strictly = "4.12.0", reject = ["1.0", "2.0"] }
            [libraries]
            okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
            [bundles]
            net = ['okhttp']
            """.trimIndent(),
        )
        assertEquals("4.12.0", catalog.versions["okhttp"])
        assertEquals(listOf("okhttp"), catalog.bundles["net"])
    }
}
