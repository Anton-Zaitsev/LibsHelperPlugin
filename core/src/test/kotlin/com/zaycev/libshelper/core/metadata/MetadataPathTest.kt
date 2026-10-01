package com.zaycev.libshelper.core.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetadataPathTest {
    @Test
    fun buildsAPathFromSafeCoordinates() {
        assertEquals(
            "com/squareup/okhttp3/okhttp/maven-metadata.xml",
            metadataPath("com.squareup.okhttp3", "okhttp"),
        )
    }

    @Test
    fun rejectsDotDotAndSpecialCharacters() {
        assertNull(metadataPath("com.example", "../secret"))
        assertNull(metadataPath("..", "okhttp"))
        assertNull(metadataPath("com.example", "ok http"))
        assertNull(metadataPath("com.example", "a/b"))
    }
}
