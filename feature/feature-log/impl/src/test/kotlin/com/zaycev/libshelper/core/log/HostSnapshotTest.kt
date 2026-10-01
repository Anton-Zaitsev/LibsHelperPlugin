package com.zaycev.libshelper.core.log

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostSnapshotTest {
    @Test
    fun macSnapshotUsesSysctlMemoryAndCoreClusters() {
        val facts = HardwareFacts(sysctl = MAC_SYSCTL, linuxCpuInfo = "cpu", processorIdentifier = "id")
        assertEquals(facts, facts.copy())
        assertEquals(facts.hashCode(), facts.copy().hashCode())
        assertTrue(facts.toString().contains("Apple M5 Max"))
        assertNotEquals(facts, facts.copy(processorIdentifier = null))
        val snapshot = facts.toSnapshot(
            osName = "Mac OS X",
            osVersion = "26.0",
            arch = "aarch64",
            jvmName = "OpenJDK",
            jvmVersion = "21",
            jvmProcessors = 8,
            heapUsedBytes = 1L,
            heapMaxBytes = 2L,
            osMemoryTotalBytes = 1L,
            osMemoryFreeBytes = 3L,
        )
        assertEquals("Apple M5 Max", snapshot.cpu.name)
        assertEquals("Mac17,7", snapshot.cpu.model)
        assertEquals(18, snapshot.cpu.logicalCores)
        assertEquals(18, snapshot.cpu.physicalCores)
        assertEquals(8, snapshot.cpu.jvmProcessors)
        assertEquals(listOf(CoreCluster("Super", 6), CoreCluster("Performance", 12)), snapshot.cpu.clusters)
        assertEquals(INSTALLED_RAM, snapshot.memory.totalBytes)
        assertNull(snapshot.memory.freeBytes)
    }

    @Test
    fun linuxSnapshotCountsPhysicalCoresOnce() {
        val snapshot = HardwareFacts(linuxCpuInfo = LINUX_CPUINFO).toSnapshot(
            osName = "Linux",
            osVersion = "6.8",
            arch = "amd64",
            jvmName = "OpenJDK",
            jvmVersion = "21",
            jvmProcessors = 2,
            heapUsedBytes = 1L,
            heapMaxBytes = -1L,
            osMemoryTotalBytes = 99L,
            osMemoryFreeBytes = 40L,
        )
        assertEquals("AMD Ryzen 7 7840U", snapshot.cpu.name)
        assertEquals(2, snapshot.cpu.logicalCores)
        assertEquals(1, snapshot.cpu.physicalCores)
        assertEquals(99L, snapshot.memory.totalBytes)
        assertEquals(40L, snapshot.memory.freeBytes)
    }

    @Test
    fun windowsSnapshotUsesTheProcessorIdentifier() {
        val snapshot = HardwareFacts(processorIdentifier = "Intel64 Family 6 Model 154, GenuineIntel").toSnapshot(
            osName = "Windows 11",
            osVersion = "10.0",
            arch = "amd64",
            jvmName = "OpenJDK",
            jvmVersion = "21",
            jvmProcessors = 8,
            heapUsedBytes = 1L,
            heapMaxBytes = 2L,
            osMemoryTotalBytes = null,
            osMemoryFreeBytes = 0L,
        )
        assertEquals("Intel64 Family 6 Model 154, GenuineIntel", snapshot.cpu.name)
        assertEquals(8, snapshot.cpu.logicalCores)
        assertNull(snapshot.cpu.physicalCores)
        assertNull(snapshot.memory.totalBytes)
        assertNull(snapshot.memory.freeBytes)
    }

    @Test
    fun blankHardwareFallsBackToTheJvm() {
        val snapshot = HardwareFacts(
            sysctl = mapOf("machdep.cpu.brand_string" to "  ", "hw.nperflevels" to "0"),
        ).toSnapshot(
            osName = "Mac OS X",
            osVersion = "26",
            arch = "aarch64",
            jvmName = "OpenJDK",
            jvmVersion = "21",
            jvmProcessors = 4,
            heapUsedBytes = 1L,
            heapMaxBytes = 2L,
            osMemoryTotalBytes = null,
            osMemoryFreeBytes = null,
        )
        assertNull(snapshot.cpu.name)
        assertEquals(4, snapshot.cpu.logicalCores)
        assertTrue(snapshot.cpu.clusters.isEmpty())
    }

    @Test
    fun readMacSysctlLoadsCoreClusters() {
        val seen = mutableListOf<String>()
        val values = readMacSysctl { args ->
            val key = args.last()
            seen += key
            when (key) {
                "hw.nperflevels" -> "2"
                "hw.perflevel0.physicalcpu" -> "6"
                "hw.perflevel0.name" -> "Super"
                "hw.perflevel1.physicalcpu" -> "12"
                "hw.perflevel1.name" -> "Performance"
                else -> "x"
            }
        }
        assertEquals("2", values["hw.nperflevels"])
        assertEquals("Super", values["hw.perflevel0.name"])
        assertTrue(seen.contains("machdep.cpu.brand_string"))
        assertTrue(seen.contains("hw.perflevel1.name"))
    }

    @Test
    fun readHardwareDispatchesByOsName() {
        val linux = readHardware(osName = "Linux", cpuInfo = { "model name : Test" })
        assertEquals("model name : Test", linux.linuxCpuInfo)
        val windows = readHardware(osName = "Windows 11", processor = { "GenuineIntel" })
        assertEquals("GenuineIntel", windows.processorIdentifier)
        val mac = readHardware(osName = "Mac OS X", command = { null })
        assertTrue(mac.sysctl.isEmpty())
        assertTrue(readHardware(osName = "FreeBSD").sysctl.isEmpty())
    }

    @Test
    fun cpuInfoReadsAFileAndIgnoresAMissingPath() {
        val file = Files.createTempFile("libshelper-cpuinfo", ".txt")
        try {
            Files.writeString(file, "model name : Example\n")
            assertEquals("model name : Example\n", readCpuInfo(file))
        } finally {
            Files.deleteIfExists(file)
        }
        assertNull(readCpuInfo(file.resolveSibling("libshelper-missing-cpuinfo")))
    }

    @Test
    fun missingCommandReturnsNull() {
        assertNull(runCommand(listOf("libshelper-missing-command-xyz")))
    }

    @Test
    fun liveProbeReadsJvmAndOs() {
        val snapshot = JdkHostProbe().snapshot()
        assertTrue(snapshot.osName.isNotBlank())
        assertTrue(snapshot.jvmVersion.isNotBlank())
        assertTrue(snapshot.cpu.jvmProcessors > 0)
        assertTrue(snapshot.cpu.logicalCores > 0)
        assertTrue(snapshot.memory.heapUsedBytes >= 0L)
        if (snapshot.osName.contains("Mac", ignoreCase = true)) {
            assertFalse(snapshot.cpu.name.isNullOrBlank())
            assertTrue((snapshot.memory.totalBytes ?: 0L) > 0L)
            if (snapshot.cpu.name.orEmpty().startsWith("Apple")) {
                assertTrue(snapshot.cpu.clusters.isNotEmpty(), snapshot.cpu.toString())
            }
        }
    }
}

private const val KIB = 1024L
private const val GIB = KIB * KIB * KIB
private const val INSTALLED_RAM = 36L * GIB

private val MAC_SYSCTL = mapOf(
    "machdep.cpu.brand_string" to "Apple M5 Max",
    "hw.model" to "Mac17,7",
    "hw.physicalcpu" to "18",
    "hw.logicalcpu" to "18",
    "hw.memsize" to INSTALLED_RAM.toString(),
    "hw.nperflevels" to "2",
    "hw.perflevel0.physicalcpu" to "6",
    "hw.perflevel0.name" to "Super",
    "hw.perflevel1.physicalcpu" to "12",
    "hw.perflevel1.name" to "Performance",
)

private val LINUX_CPUINFO = """
    processor	: 0
    model name	: AMD Ryzen 7 7840U
    physical id	: 0
    core id		: 0

    processor	: 1
    model name	: AMD Ryzen 7 7840U
    physical id	: 0
    core id		: 0
""".trimIndent()
