package com.zaycev.libshelper.core.i18n

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundleParityTest {
    @Test
    fun englishAndRussianHaveTheSameKeys() {
        val directory = Path.of("..", "src", "main", "resources", "messages")
        val english = keys(directory.resolve("LibsHelperBundle.properties"))
        val russian = keys(directory.resolve("LibsHelperBundle_ru.properties"))
        assertTrue(english.isNotEmpty())
        assertEquals(english, russian)
    }

    private fun keys(path: Path): Set<String> {
        check(Files.exists(path)) { "missing $path" }
        val properties = Properties()
        Files.newBufferedReader(path).use { properties.load(it) }
        return properties.stringPropertyNames()
    }
}
