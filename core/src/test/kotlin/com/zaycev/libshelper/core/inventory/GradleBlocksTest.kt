package com.zaycev.libshelper.core.inventory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GradleBlocksTest {
    @Test
    fun bracesInsideStringsDoNotCloseTheBlock() {
        val text = """
            dependencies {
                implementation("com.example:demo:1.0")
                def note = "}"
            }
            android { compileSdk = 35 }
        """.trimIndent()
        val block = extractBlock(text, "dependencies")
        assertEquals(true, block.contains("note"))
        assertFalse(block.contains("compileSdk"))
    }
}
