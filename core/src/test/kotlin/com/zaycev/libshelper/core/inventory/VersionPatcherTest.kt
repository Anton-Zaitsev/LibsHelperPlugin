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
    fun literalDoesNotPrefixCorrupt() {
        val text = """implementation("g:a:1.0.1")"""
        assertNull(patchGradleLiteral(text, "g", "a", "1.0", "2.0"))
    }

    @Test
    fun literalLeavesCommentsUntouched() {
        val text = """
            // implementation("g:a:1.0")
            implementation("g:a:1.0")
        """.trimIndent()
        val patched = checkNotNull(patchGradleLiteral(text, "g", "a", "1.0", "2.0"))
        assertEquals(true, patched.contains("// implementation(\"g:a:1.0\")"))
        assertEquals(true, patched.contains("implementation(\"g:a:2.0\")"))
    }

    @Test
    fun inlinePreservesCrlfAndSingleQuotes() {
        val text = "[libraries]\r\nlib = { module = \"g:a\", version = '1.0' }\r\n"
        val patched = checkNotNull(patchCatalogInlineVersion(text, "lib", "2.0"))
        assertEquals("[libraries]\r\nlib = { module = \"g:a\", version = '2.0' }\r\n", patched)
    }

    @Test
    fun patchesStrictlyInVersionsTable() {
        val text = "[versions]\nokhttp = { strictly = \"4.12.0\" }\n"
        val patched = checkNotNull(patchCatalogVersionRef(text, "okhttp", "4.13.0"))
        assertEquals(true, patched.contains("4.13.0"))
        assertEquals(false, patched.contains("4.12.0"))
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

    @Test
    fun readsCatalogVersionThatUndoRestored() {
        val text = "[versions]\nagp = \"9.0.0-rc02\"\n"
        assertEquals("9.0.0-rc02", readCatalogVersionRef(text, "agp"))
        assertEquals("9.5.2", readCatalogVersionRef("[versions]\nagp = { strictly = \"9.5.2\" }\n", "agp"))
    }

    @Test
    fun readsInlineVersionAndSkipsVersionRef() {
        val text = """
            [libraries]
            okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
            guava = { module = "com.google.guava:guava", version = "32.1.0-jre" }
        """.trimIndent()
        assertNull(readCatalogInlineVersion(text, "okhttp"))
        assertEquals("32.1.0-jre", readCatalogInlineVersion(text, "guava"))
    }

    @Test
    fun readsGradleLiteralIgnoringComments() {
        val text = """
            // implementation("g:a:9.9.9")
            implementation("g:a:1.2.0-beta1")
        """.trimIndent()
        val dependency = DeclaredDependency(
            coordinates = Coordinates("g", "a"),
            requestedVersion = "1.0.0",
            configuration = "implementation",
            module = ":app",
            source = DependencySource.KotlinDsl,
            usagePath = "app/build.gradle.kts",
        )
        assertEquals("1.2.0-beta1", readDeclaredVersion(text, dependency))
    }
}
