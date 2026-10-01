package com.zaycev.libshelper.core.inventory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TomlCollapseTest {
    @Test
    fun multilineStringBecomesOneValueAndKeepsLaterLines() {
        val text = """
            [versions]
            kotlin = ${"\"\"\""}
              2.0.0
            ${"\"\"\""}
            compose = "1.7.0"
        """.trimIndent()
        val parsed = parseCatalog(text)
        assertEquals("2.0.0", parsed.versions["kotlin"])
        assertEquals("1.7.0", parsed.versions["compose"])
        assertTrue(parsed.error == null)
    }
}
