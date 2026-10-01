package com.zaycev.libshelper.core.log

import kotlin.test.Test
import kotlin.test.assertEquals

class NoOpLoggerTest {
    @Test
    fun everyLevelIsSilent() {
        val logger = NoOpLibsHelperLogger()
        logger.debug("debug")
        logger.info("info")
        logger.warn("warn", IllegalStateException("warn"))
        logger.warn("warn-only")
        logger.error("error", IllegalStateException("error"))
        logger.error("error-only")
        val trail = ActionTrail(capacity = 1, clock = { 1L })
        trail.record("mcp", "start", "1")
        assertEquals("start", trail.snapshot().single().action)
    }
}
