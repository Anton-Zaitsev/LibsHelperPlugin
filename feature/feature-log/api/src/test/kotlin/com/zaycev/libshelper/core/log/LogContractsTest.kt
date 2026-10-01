package com.zaycev.libshelper.core.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LogContractsTest {
    @Test
    fun reportsAndEventsRoundTrip() {
        val event = ActionEvent(1L, "ui", "open", null)
        val report = DiagnosticReport(
            pluginVersion = "1.1.0",
            ideVersion = "Studio",
            os = "mac",
            summary = "ok",
            actions = listOf(event),
            error = null,
        )
        assertEquals(report, report.copy())
        assertEquals(report.hashCode(), report.copy().hashCode())
        assertTrue(report.toString().contains("1.1.0"))
        assertNotEquals(event, event.copy(detail = "x"))
        val logger = object : LibsHelperLogger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String, error: Throwable?) = Unit
            override fun error(message: String, error: Throwable?) = Unit
        }
        logger.debug("d")
        logger.info("i")
        logger.warn("w")
        logger.error("e")
        val trail = object : ActionLog {
            override fun record(category: String, action: String, detail: String?) = Unit
            override fun snapshot(): List<ActionEvent> = listOf(event)
        }
        trail.record("ui", "open")
        assertEquals(event, trail.snapshot().single())
        val redactor = object : TextRedactor {
            override fun redact(text: String, home: String?): String = text
        }
        assertEquals("plain", redactor.redact("plain", null))
        val formatter = object : DiagnosticFormatter {
            override fun format(report: DiagnosticReport): String = report.summary
            override fun issueUrl(title: String, body: String, repo: String): String = "$repo/$title"
        }
        assertEquals("ok", formatter.format(report))
        assertEquals("repo/title", formatter.issueUrl("title", "body", "repo"))
    }

    @Test
    fun headlineDropsABareProjectName() {
        val cluster = CoreCluster("Super", 6)
        val cpu = MachineCpu("Apple M5 Max", "Mac17,7", 18, 18, 18, listOf(cluster))
        val memory = MachineMemory(1L, 2L, 3L, 4L)
        val host = HostSnapshot("Mac OS X", "26", "aarch64", cpu, memory, "OpenJDK", "21")
        val report = DiagnosticReport("1.1.0", null, "mac", "Project Zhiraff", emptyList(), null, host)
        assertEquals(host, host.copy())
        assertEquals(host.hashCode(), host.copy().hashCode())
        assertTrue(host.toString().contains("Apple M5 Max"))
        assertEquals(cluster, cluster.copy())
        assertEquals(memory, memory.copy())
        assertEquals(cpu, cpu.copy())
        assertNotEquals(cpu, cpu.copy(name = "other"))
        assertEquals(null, report.visibleSummary())
        assertEquals("Diagnostic report", report.publicHeadline())
        val failed = report.copy(summary = "Project resolution failed")
        assertEquals("Project resolution failed", failed.visibleSummary())
        assertEquals("Project resolution failed", failed.publicHeadline())
        val long = "e".repeat(200)
        val headline = report.copy(summary = long).publicHeadline()
        assertTrue(headline.length < long.length)
        assertEquals(long.take(headline.length), headline)
        assertEquals(null, report.copy(summary = "  ").visibleSummary())
    }
}
