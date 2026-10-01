package com.zaycev.libshelper.core.log

import com.sun.management.OperatingSystemMXBean
import java.io.IOException
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal data class HardwareFacts(
    val sysctl: Map<String, String> = emptyMap(),
    val linuxCpuInfo: String? = null,
    val processorIdentifier: String? = null,
)

class JdkHostProbe {
    fun snapshot(): HostSnapshot {
        val heap = liveHeap()
        val memory = liveOsMemory()
        return cachedHardware.toSnapshot(
            osName = System.getProperty("os.name").orEmpty(),
            osVersion = System.getProperty("os.version").orEmpty(),
            arch = System.getProperty("os.arch").orEmpty(),
            jvmName = System.getProperty("java.vm.name").orEmpty(),
            jvmVersion = System.getProperty("java.version").orEmpty(),
            jvmProcessors = Runtime.getRuntime().availableProcessors(),
            heapUsedBytes = heap.first,
            heapMaxBytes = heap.second,
            osMemoryTotalBytes = memory.first,
            osMemoryFreeBytes = memory.second,
        )
    }

    private companion object {
        val cachedHardware: HardwareFacts by lazy { readHardware() }
    }
}

internal fun HardwareFacts.toSnapshot(
    osName: String,
    osVersion: String,
    arch: String,
    jvmName: String,
    jvmVersion: String,
    jvmProcessors: Int,
    heapUsedBytes: Long,
    heapMaxBytes: Long,
    osMemoryTotalBytes: Long?,
    osMemoryFreeBytes: Long?,
): HostSnapshot {
    val cpu = when {
        osName.contains("mac", ignoreCase = true) -> macCpu(sysctl, jvmProcessors)
        osName.contains("linux", ignoreCase = true) -> linuxCpu(linuxCpuInfo.orEmpty(), jvmProcessors)
        osName.contains("win", ignoreCase = true) -> windowsCpu(processorIdentifier, jvmProcessors)
        else -> MachineCpu(null, null, jvmProcessors, null, jvmProcessors)
    }
    val installed = sysctl["hw.memsize"]?.toLongOrNull().positive() ?: osMemoryTotalBytes.positive()
    // macOS "free" is unused pages, not memory a process can actually take.
    val free = if (osName.contains("mac", ignoreCase = true)) null else osMemoryFreeBytes.positive()
    return HostSnapshot(
        osName = osName,
        osVersion = osVersion,
        arch = arch,
        cpu = cpu,
        memory = MachineMemory(installed, free, heapUsedBytes, heapMaxBytes),
        jvmName = jvmName,
        jvmVersion = jvmVersion,
    )
}

internal fun readHardware(
    osName: String = System.getProperty("os.name").orEmpty(),
    command: (List<String>) -> String? = ::runCommand,
    cpuInfo: () -> String? = { readCpuInfo() },
    processor: () -> String? = { System.getenv("PROCESSOR_IDENTIFIER") },
): HardwareFacts = when {
    osName.contains("mac", ignoreCase = true) -> HardwareFacts(sysctl = readMacSysctl(command))
    osName.contains("linux", ignoreCase = true) -> HardwareFacts(linuxCpuInfo = cpuInfo())
    osName.contains("win", ignoreCase = true) -> HardwareFacts(processorIdentifier = processor())
    else -> HardwareFacts()
}

internal fun readMacSysctl(command: (List<String>) -> String? = ::runCommand): Map<String, String> {
    val values = linkedMapOf<String, String>()
    fun ask(keys: List<String>) {
        keys.forEach { key ->
            val value = command(listOf("sysctl", "-n", key)) ?: return@forEach
            values[key] = value
        }
    }
    ask(MAC_KEYS)
    val levels = values["hw.nperflevels"]?.toIntOrNull() ?: return values
    if (levels <= 0) return values
    val extra = (0 until levels.coerceAtMost(MAX_PERF_LEVELS)).flatMap { index ->
        listOf("hw.perflevel$index.physicalcpu", "hw.perflevel$index.name")
    }
    ask(extra)
    return values
}

internal fun readCpuInfo(path: Path = Path.of("/proc/cpuinfo")): String? {
    if (!Files.exists(path)) return null
    return try {
        Files.readString(path)
    } catch (_: IOException) {
        null
    }
}

internal fun runCommand(command: List<String>): String? = try {
    val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()
    if (!process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly()
        null
    } else if (process.exitValue() != 0) {
        null
    } else {
        process.inputStream.bufferedReader().use { it.readText() }.trim().ifEmpty { null }
    }
} catch (_: IOException) {
    null
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    null
}

internal fun macCpu(sysctl: Map<String, String>, jvmProcessors: Int): MachineCpu {
    val logical = sysctl["hw.logicalcpu"]?.toIntOrNull().positive() ?: jvmProcessors
    return MachineCpu(
        name = sysctl["machdep.cpu.brand_string"]?.ifBlank { null },
        model = sysctl["hw.model"]?.ifBlank { null },
        logicalCores = logical,
        physicalCores = sysctl["hw.physicalcpu"]?.toIntOrNull().positive(),
        jvmProcessors = jvmProcessors,
        clusters = macClusters(sysctl),
    )
}

internal fun macClusters(sysctl: Map<String, String>): List<CoreCluster> {
    val levels = sysctl["hw.nperflevels"]?.toIntOrNull() ?: return emptyList()
    if (levels <= 0) return emptyList()
    return (0 until levels.coerceAtMost(MAX_PERF_LEVELS)).mapNotNull { index ->
        val cores = sysctl["hw.perflevel$index.physicalcpu"]?.toIntOrNull().positive() ?: return@mapNotNull null
        val name = sysctl["hw.perflevel$index.name"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        CoreCluster(name, cores)
    }
}

internal fun linuxCpu(text: String, jvmProcessors: Int): MachineCpu {
    var model: String? = null
    var logical = 0
    val cores = mutableSetOf<String>()
    var physical: String? = null
    var core: String? = null
    fun flush() {
        if (physical != null && core != null) cores += "$physical:$core"
        physical = null
        core = null
    }
    text.lineSequence().forEach { line ->
        if (line.isBlank()) {
            flush()
            return@forEach
        }
        val key = line.substringBefore(':').trim()
        val value = line.substringAfter(':', "").trim()
        when (key) {
            "processor" -> logical += 1
            "model name" -> if (model == null && value.isNotEmpty()) model = value
            "physical id" -> physical = value
            "core id" -> core = value
        }
    }
    flush()
    return MachineCpu(
        name = model,
        model = null,
        logicalCores = logical.positive() ?: jvmProcessors,
        physicalCores = cores.size.positive(),
        jvmProcessors = jvmProcessors,
    )
}

private fun windowsCpu(processorIdentifier: String?, jvmProcessors: Int): MachineCpu = MachineCpu(
    name = processorIdentifier?.ifBlank { null },
    model = null,
    logicalCores = jvmProcessors,
    physicalCores = null,
    jvmProcessors = jvmProcessors,
)

internal fun liveHeap(): Pair<Long, Long> {
    val usage = ManagementFactory.getMemoryMXBean().heapMemoryUsage
    return usage.used to usage.max
}

internal fun liveOsMemory(): Pair<Long?, Long?> {
    val bean = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean ?: return null to null
    return bean.totalMemorySize.positive() to bean.freeMemorySize.positive()
}

private fun Long?.positive(): Long? = this?.takeIf { it > 0L }

private fun Int?.positive(): Int? = this?.takeIf { it > 0 }

private val MAC_KEYS = listOf(
    "machdep.cpu.brand_string",
    "hw.model",
    "hw.physicalcpu",
    "hw.logicalcpu",
    "hw.memsize",
    "hw.nperflevels",
)

private const val MAX_PERF_LEVELS = 4
private const val COMMAND_TIMEOUT_MS = 1000L
