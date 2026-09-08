package com.zaycev.libshelper.core.inventory

import com.zaycev.libshelper.core.model.ModuleKind

data class ModuleFacts(
    val projectDeps: List<String>,
    val targets: Set<ModuleKind>,
    val kind: ModuleKind,
)

fun parseIncludedModules(text: String): List<String> {
    val out = LinkedHashSet<String>()
    INCLUDE_ARGS.findAll(text).forEach { match ->
        QUOTED_MODULE.findAll(match.groupValues[1]).forEach { quoted ->
            out += normalizeModuleId(quoted.groupValues[1])
        }
    }
    return out.toList()
}

fun parseModuleFacts(text: String): ModuleFacts {
    val projectDeps = LinkedHashSet<String>()
    PROJECT_PATH.findAll(text).forEach { match ->
        projectDeps += normalizeModuleId(match.groupValues[1])
    }
    PROJECTS_ACCESSOR.findAll(text).forEach { match ->
        projectDeps += accessorToModuleId(match.groupValues[1])
    }
    val kmp = isKmpScript(text)
    val targets = if (kmp) parseKmpTargets(text) else emptySet()
    val kind = when {
        kmp -> ModuleKind.Kmp
        isAndroidScript(text) -> ModuleKind.Android
        isJvmScript(text) -> ModuleKind.Jvm
        else -> ModuleKind.Gradle
    }
    return ModuleFacts(
        projectDeps = projectDeps.filter { it != ":" }.distinct(),
        targets = targets,
        kind = kind,
    )
}

fun normalizeModuleId(raw: String): String {
    val trimmed = raw.trim().replace('/', ':').trim(':')
    return if (trimmed.isEmpty()) ":" else ":$trimmed"
}

fun parentModuleId(id: String): String? {
    if (id == ":" || !id.startsWith(':')) return null
    val hash = id.indexOf('#')
    if (hash > 0) return id.substring(0, hash)
    val cut = id.lastIndexOf(':')
    return if (cut <= 0) ":" else id.substring(0, cut)
}

fun gradleModuleId(id: String): String = id.substringBefore('#')

fun moduleScriptCandidates(moduleId: String): List<String> {
    val gradleId = gradleModuleId(moduleId)
    if (gradleId == ":") return listOf("build.gradle.kts", "build.gradle")
    val dir = gradleId.trimStart(':').replace(':', '/')
    return listOf("$dir/build.gradle.kts", "$dir/build.gradle")
}

fun shortModuleLabel(id: String): String {
    if (id == ":") return "root"
    val hash = id.substringAfter('#', missingDelimiterValue = "")
    if (hash.isNotEmpty()) return hash
    return id.substringAfterLast(':')
}

private fun accessorToModuleId(accessor: String): String =
    normalizeModuleId(accessor.replace('.', ':'))

private fun isKmpScript(text: String): Boolean =
    text.contains("kotlin.multiplatform") ||
        text.contains("kotlin(\"multiplatform\")") ||
        text.contains("kotlin('multiplatform')") ||
        text.contains("org.jetbrains.kotlin.multiplatform")

private fun isAndroidScript(text: String): Boolean =
    text.contains("com.android.application") ||
        text.contains("com.android.library") ||
        text.contains("com.android.kotlin.multiplatform") ||
        text.contains("id(\"com.android") ||
        text.contains("id('com.android")

private fun isJvmScript(text: String): Boolean {
    val plugins = extractBlock(text, "plugins").ifBlank { text.take(PLUGIN_HEAD) }
    return plugins.contains("org.jetbrains.kotlin.jvm") ||
        plugins.contains("kotlin(\"jvm\")") ||
        plugins.contains("kotlin('jvm')") ||
        plugins.contains("java-library") ||
        plugins.contains("id(\"java\")") ||
        plugins.contains("id('java')") ||
        plugins.contains("id(\"application\")")
}

private fun parseKmpTargets(text: String): Set<ModuleKind> {
    val kotlinBlock = extractBlock(text, "kotlin").ifBlank { text }
    val found = linkedSetOf<ModuleKind>()
    if (ANDROID_TARGET.containsMatchIn(kotlinBlock) || kotlinBlock.contains("androidTarget")) {
        found += ModuleKind.Android
    }
    if (JVM_TARGET.containsMatchIn(kotlinBlock)) found += ModuleKind.Jvm
    if (IOS_TARGET.containsMatchIn(kotlinBlock)) found += ModuleKind.Ios
    if (JS_TARGET.containsMatchIn(kotlinBlock)) found += ModuleKind.Js
    if (WASM_TARGET.containsMatchIn(kotlinBlock)) found += ModuleKind.Wasm
    if (NATIVE_TARGET.containsMatchIn(kotlinBlock)) found += ModuleKind.Native
    if (DESKTOP_TARGET.containsMatchIn(kotlinBlock) || kotlinBlock.contains("compose.desktop")) {
        found += ModuleKind.Desktop
    }
    if (found.isNotEmpty()) found += ModuleKind.Common
    return found
}

private const val PLUGIN_HEAD = 800
private val INCLUDE_ARGS = Regex("""(?<![A-Za-z0-9_])include\s*\(([^)]*)\)""")
private val QUOTED_MODULE = Regex("""["']([^"']+)["']""")
private val PROJECT_PATH = Regex("""project\s*\(\s*(?:path\s*=\s*)?["']([^"']+)["']""")
private val PROJECTS_ACCESSOR = Regex("""(?<![A-Za-z0-9_])projects\.([A-Za-z0-9_.]+)""")
private val ANDROID_TARGET = Regex("""androidTarget\s*[(\{]|(?<![A-Za-z])android\s*\{""")
private val JVM_TARGET = Regex("""(?<![A-Za-z])jvm\s*[(\{]""")
private val IOS_TARGET = Regex("""ios[A-Z]|ios\s*\(|iosX64|iosArm64|iosSimulator""")
private val JS_TARGET = Regex("""(?<![A-Za-z])js\s*[(\{]""")
private val WASM_TARGET = Regex("""wasmJs\s*[(\{]|wasmWasi\s*[(\{]""")
private val NATIVE_TARGET = Regex("""linuxX64|linuxArm64|macosX64|macosArm64|mingwX64|watchos|tvos""")
private val DESKTOP_TARGET = Regex("""(?<![A-Za-z])desktop\s*[(\{]|compose\.desktop""")
