package com.zaycev.libshelper.core.log

import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsTest {
    @Test
    fun redactorHidesSecretsAndHome() {
        val raw = "Authorization: Bearer secret-token home=/home/runner token=abc"
        val cleaned = DefaultTextRedactor.redact(raw, home = "/home/runner")
        assertFalse(cleaned.contains("secret-token"))
        assertFalse(cleaned.contains("/home/runner"))
        assertFalse(cleaned.contains("token=abc"))
        assertTrue(cleaned.contains("~"))
    }

    @Test
    fun redactsCookiesBasicAuthAndUserInfo() {
        val raw = "Cookie: session=abc Authorization: Basic dXNlcjpwYXNz https://u:p@host password: hunter2 ?access_token=xyz"
        val cleaned = DefaultTextRedactor.redact(raw, home = null)
        assertFalse(cleaned.contains("abc"))
        assertFalse(cleaned.contains("dXNlcjpwYXNz"))
        assertFalse(cleaned.contains("u:p"))
        assertFalse(cleaned.contains("hunter2"))
        assertFalse(cleaned.contains("xyz"))
    }

    @Test
    fun formatRedactsEvenIfTheCallerForgot() {
        val report = DiagnosticReport(
            pluginVersion = "1.0",
            ideVersion = null,
            os = "test",
            summary = "Authorization: Bearer super-secret-token",
            actions = emptyList(),
            error = null,
        )
        assertFalse(DefaultDiagnosticFormatter().format(report).contains("super-secret-token"))
    }

    @Test
    fun trailDropsTheOldestEvent() {
        val trail = ActionTrail(capacity = 2, clock = { 10L })
        trail.record("ui", "tab", "libraries")
        trail.record("ui", "tab", "updates")
        trail.record("ui", "tab", "analytics")
        assertEquals(listOf("updates", "analytics"), trail.snapshot().map { it.detail })
    }

    @Test
    fun formatIncludesTheErrorAndRecentActions() {
        val report = DiagnosticReport(
            pluginVersion = "1.1.0",
            ideVersion = "Studio",
            os = "mac",
            summary = "failed",
            actions = listOf(
                ActionEvent(1L, "ui", "open", null),
                ActionEvent(2L, "ui", "apply", "okhttp"),
            ),
            error = "boom",
        )
        val text = DefaultDiagnosticFormatter().format(report)
        assertTrue(text.contains("Studio"))
        assertTrue(text.contains("Error"))
        assertTrue(text.contains("boom"))
        assertTrue(text.contains("apply"))
        assertTrue(text.contains("okhttp"))
        assertFalse(text.contains("#0969da"))
        assertFalse(text.contains("<details>"))
    }

    @Test
    fun markdownHidesTheProjectAndFoldsClicks() {
        val report = DiagnosticReport(
            pluginVersion = "1.1.0",
            ideVersion = "2026.1.5",
            os = "Mac OS X",
            summary = "Project Zhiraff",
            actions = listOf(
                ActionEvent(1_000L, "ui", "click", "editor"),
                ActionEvent(1_000L, "ui", "inlay-click", "org.jetbrains.kotlin:kotlin-gradle-plugin"),
                ActionEvent(2_000L, "ui", "inlay-click", "org.jetbrains.kotlin:kotlin-gradle-plugin"),
                ActionEvent(3_000L, "ui", "inlay-menu", "org.jetbrains.compose.material3:material3"),
                ActionEvent(3_000L, "ui", "inlay-menu", "org.jetbrains.compose.material3:material3"),
                ActionEvent(4_000L, "analysis", "refresh", "false"),
                ActionEvent(5_000L, "mcp", "start", "8765"),
            ),
            error = "boom\n" + "y".repeat(STACK_PADDING) + "END-OF-STACK",
            host = sampleHost(),
        )
        val text = DefaultDiagnosticFormatter(zone = ZoneOffset.UTC).format(report)
        assertFalse(text.contains("Zhiraff"))
        assertFalse(text.contains("## Summary"))
        assertTrue(text.contains("Apple M5 Max"))
        assertTrue(text.contains("Mac17,7"))
        assertTrue(text.contains("18 logical · 18 physical · JVM 8"))
        assertTrue(text.contains("6 Super · 12 Performance"))
        assertTrue(text.contains("36.0 GB installed"))
        assertTrue(text.contains("512 MB used / 4.0 GB max"))
        assertTrue(text.contains("color:#0969da"))
        assertTrue(text.contains("color:#d1242f"))
        assertTrue(text.contains("> [!NOTE]"))
        assertTrue(text.contains("> [!CAUTION]"))
        val clicks = text.indexOf("<details>")
        assertTrue(text.indexOf("Apple M5 Max") < text.indexOf("refresh"))
        assertTrue(text.indexOf("refresh") < clicks)
        assertTrue(text.indexOf("inlay-click") > clicks)
        assertTrue(text.indexOf("Stack trace") > clicks)
        assertTrue(text.contains("×2"))
        assertTrue(text.contains("Clicks and menus"))
        assertFalse(text.take(URL_BUDGET).contains("END-OF-STACK"))
        assertTrue(text.take(URL_BUDGET).contains("Apple M5 Max"))
        assertTrue(text.take(URL_BUDGET).contains("refresh"))
        val piped = DefaultDiagnosticFormatter().format(
            report.copy(
                actions = listOf(ActionEvent(1L, "ui", "inlay-click", "a|b <script> " + "z".repeat(CELL_OVERFLOW))),
                error = null,
            ),
        )
        assertTrue(piped.contains("a\\|b"))
        assertTrue(piped.contains("…"))
        assertTrue(piped.contains(">click</span>"))
        assertTrue(piped.contains("🔵 Clicks ("))
        assertFalse(piped.contains("<script>"))
        val menus = DefaultDiagnosticFormatter().format(
            report.copy(actions = listOf(ActionEvent(1L, "ui", "inlay-menu", "material3")), error = null),
        )
        assertTrue(menus.contains("🔵 Menus ("))
        assertTrue(menus.contains(">menu</span>"))
    }

    @Test
    fun compactMachineOmitsRepeatedCoreLayout() {
        val host = sampleHost().let { snap ->
            snap.copy(
                cpu = snap.cpu.copy(clusters = listOf(CoreCluster("Super", 6)), jvmProcessors = 18),
                memory = snap.memory.copy(heapMaxBytes = -1L, heapUsedBytes = SMALL_HEAP, freeBytes = GIB),
            )
        }
        val text = DefaultDiagnosticFormatter().format(
            DiagnosticReport("1", "IDE", "os", "failed", emptyList(), null, host),
        )
        assertTrue(text.contains("no limit"))
        assertTrue(text.contains("1.0 GB free"))
        assertTrue(text.contains("KB used"))
        assertFalse(text.contains("Core layout"))
        assertFalse(text.contains("· JVM"))
        val plain = DefaultDiagnosticFormatter().wrapWithLog("plain", null)
        assertTrue(plain.contains("plain"))
    }

    @Test
    fun logTailIsCollapsedAndRedacted() {
        val body = DefaultDiagnosticFormatter().format(
            DiagnosticReport("1.1.0", null, "mac", "", emptyList(), null),
        )
        val wrapped = DefaultDiagnosticFormatter().wrapWithLog(body, "token=abc\nline")
        assertTrue(wrapped.contains("<summary>Plugin log"))
        assertFalse(wrapped.contains("abc"))
        assertTrue(wrapped.contains("No actions."))
    }

    @Test
    fun redactsSetCookieUserInfoWithoutPasswordAndApiKey() {
        val raw = "Set-Cookie: a=b ftp://onlyuser@host api_key: secret-value secret=hidden"
        val cleaned = DefaultTextRedactor.redact(raw, home = "   ")
        assertFalse(cleaned.contains("a=b"))
        assertFalse(cleaned.contains("onlyuser@"))
        assertFalse(cleaned.contains("secret-value"))
        assertFalse(cleaned.contains("hidden"))
    }

    @Test
    fun shortIssueUrlIsNotTruncated() {
        val url = DefaultDiagnosticFormatter().issueUrl("title", "short body")
        assertTrue(url.contains("short+body") || url.contains("short%20body"))
        assertFalse(url.contains("truncated"))
    }

    @Test
    fun concurrentTrailKeepsCapacity() {
        val trail = ActionTrail(capacity = 8, clock = { 1L })
        val threads = List(4) { index ->
            Thread {
                repeat(20) { step -> trail.record("ui", "click", "$index-$step") }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(8, trail.snapshot().size)
    }

    @Test
    fun issueUrlTruncatesTheBody() {
        val url = DefaultDiagnosticFormatter().issueUrl("boom", "x".repeat(5000), "example/libshelper")
        assertTrue(url.startsWith("https://github.com/example/libshelper/issues/new"))
        assertTrue(url.contains("truncated"))
    }
}

private fun sampleHost(): HostSnapshot = HostSnapshot(
    osName = "Mac OS X",
    osVersion = "26.0",
    arch = "aarch64",
    cpu = MachineCpu(
        name = "Apple M5 Max",
        model = "Mac17,7",
        logicalCores = 18,
        physicalCores = 18,
        jvmProcessors = 8,
        clusters = listOf(CoreCluster("Super", 6), CoreCluster("Performance", 12)),
    ),
    memory = MachineMemory(
        totalBytes = INSTALLED_RAM,
        freeBytes = null,
        heapUsedBytes = HEAP_USED,
        heapMaxBytes = HEAP_MAX,
    ),
    jvmName = "OpenJDK 64-Bit Server VM",
    jvmVersion = "21.0.8",
)

private const val KIB = 1024L
private const val MIB = KIB * KIB
private const val GIB = MIB * KIB
private const val INSTALLED_RAM = 36L * GIB
private const val HEAP_USED = 512L * MIB
private const val HEAP_MAX = 4L * GIB
private const val STACK_PADDING = 3000
private const val URL_BUDGET = 1500
private const val CELL_OVERFLOW = 250
private const val SMALL_HEAP = 2 * KIB
