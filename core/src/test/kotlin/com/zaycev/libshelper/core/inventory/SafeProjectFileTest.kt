package com.zaycev.libshelper.core.inventory

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SafeProjectFileTest {
    @Test
    fun rejectsPathsThatLeaveTheProject() {
        val root = Files.createTempDirectory("libshelper-root")
        val file = Files.writeString(root.resolve("libs.versions.toml"), "ok = \"1\"\n")
        try {
            assertNotNull(safeProjectFile(root, "libs.versions.toml"))
            assertNull(pathInsideRoot(root, "../outside.toml"))
            assertNull(safeProjectFile(root.toString(), "../outside.toml"))
            assertEqualsPath(file, safeProjectFile(root, "libs.versions.toml"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun assertEqualsPath(expected: java.nio.file.Path, actual: java.nio.file.Path?) {
        kotlin.test.assertEquals(expected.toAbsolutePath().normalize(), actual?.toAbsolutePath()?.normalize())
    }
}
