package com.zaycev.libshelper.core.metadata

import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ParseMavenMetadataSecurityTest {
    @Test
    fun rejectsDoctype() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <metadata><versioning><versions><version>&xxe;</version></versions></versioning></metadata>
        """.trimIndent()
        assertFails { parseMavenMetadata(xml) }
    }

    @Test
    fun readsVersionsFromAPlainDocument() {
        val xml = """
            <metadata>
              <groupId>g</groupId>
              <artifactId>a</artifactId>
              <versioning><versions><version>1.0.0</version></versions></versioning>
            </metadata>
        """.trimIndent()
        assertTrue(parseMavenMetadata(xml).versions.contains("1.0.0"))
    }
}
