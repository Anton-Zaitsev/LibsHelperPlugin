package com.zaycev.libshelper.core.settings

data class VersionSourceText(
    val path: String,
    val text: String,
)

data class VersionUsage(
    val key: String,
    val path: String,
    val line: Int,
    val role: BuildSettingRole,
)

enum class BuildSettingRole {
    CompileSdk,
    TargetSdk,
    MinSdk,
    Ndk,
    JvmToolchain,
    Library,
    Unknown,
}

private val ACCESSOR = Regex("""libs\.versions\.([A-Za-z0-9_.]+)""")
private val FIND_VERSION = Regex("""findVersion\(\s*"([^"]+)"""")
private val VERSION_CALL = Regex("""(?<![.\w])version\(\s*"([^"]+)"""")
private val LOCAL_DEF = Regex("""(?:val|var|def)\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.+)""")
private val COMPILE_SITE = Regex("""(?i)\bcompileSdk\s*(?:=|\()""")
private val TARGET_SITE = Regex("""(?i)\btargetSdk\s*(?:=|\()""")
private val MIN_SITE = Regex("""(?i)\bminSdk\s*(?:=|\()""")
private val NDK_SITE = Regex("""(?i)\bndkVersion\s*(?:=|\()""")
private val JVM_SITE = Regex("""(?i)\b(?:jvmToolchain|javaLanguageVersion|jvmTarget)\s*(?:=|\()""")
private val LIBRARY_SITE = Regex(
    """(?i)\b(implementation|api|compileOnly|runtimeOnly|ksp|annotationProcessor|classpath)\s*\(""",
)

fun indexVersionUsages(
    files: List<VersionSourceText>,
    catalogKeys: Set<String>,
): List<VersionUsage> {
    val usages = mutableListOf<VersionUsage>()
    for (file in files) {
        val lines = file.text.lines()
        val bindings = bindingsOf(lines, catalogKeys)
        lines.forEachIndexed { index, line ->
            val code = codeOf(line)
            val role = roleAt(code)
            for (key in keysOn(code, catalogKeys, bindings)) {
                usages += VersionUsage(key, file.path, index + 1, role)
            }
        }
    }
    return usages.distinctBy { "${it.key}|${it.path}|${it.line}|${it.role}" }
}

private fun bindingsOf(lines: List<String>, catalogKeys: Set<String>): Map<String, String> {
    val bindings = HashMap<String, String>()
    for (line in lines) {
        val code = codeOf(line)
        for (match in LOCAL_DEF.findAll(code)) {
            val key = keyIn(match.groupValues[2], catalogKeys) ?: continue
            bindings[match.groupValues[1]] = key
        }
    }
    return bindings
}

private fun keysOn(code: String, catalogKeys: Set<String>, bindings: Map<String, String>): Set<String> {
    val keys = LinkedHashSet<String>()
    keyIn(code, catalogKeys)?.let { keys.add(it) }
    for (match in ACCESSOR.findAll(code)) {
        resolveKey(match.groupValues[1].removeSuffix(".get"), catalogKeys)?.let { keys.add(it) }
    }
    for (match in FIND_VERSION.findAll(code)) {
        resolveKey(match.groupValues[1], catalogKeys)?.let { keys.add(it) }
    }
    for (match in VERSION_CALL.findAll(code)) {
        resolveKey(match.groupValues[1], catalogKeys)?.let { keys.add(it) }
    }
    for ((name, key) in bindings) {
        if (Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(code)) keys.add(key)
    }
    return keys
}

private fun keyIn(code: String, catalogKeys: Set<String>): String? {
    ACCESSOR.find(code)?.let { match ->
        return resolveKey(match.groupValues[1].removeSuffix(".get"), catalogKeys)
    }
    FIND_VERSION.find(code)?.let { return resolveKey(it.groupValues[1], catalogKeys) }
    VERSION_CALL.find(code)?.let { return resolveKey(it.groupValues[1], catalogKeys) }
    return null
}

private fun codeOf(line: String): String {
    var quote: Char? = null
    var escaped = false
    for (index in line.indices) {
        val char = line[index]
        if (quote != null) {
            if (quote == '"' && escaped) {
                escaped = false
                continue
            }
            if (quote == '"' && char == '\\') {
                escaped = true
                continue
            }
            if (char == quote) quote = null
            continue
        }
        when {
            char == '"' || char == '\'' -> quote = char
            char == '#' -> return line.substring(0, index)
            char == '/' && index + 1 < line.length && line[index + 1] == '/' -> return line.substring(0, index)
        }
    }
    return line
}

private fun roleAt(line: String): BuildSettingRole = when {
    COMPILE_SITE.containsMatchIn(line) -> BuildSettingRole.CompileSdk
    TARGET_SITE.containsMatchIn(line) -> BuildSettingRole.TargetSdk
    MIN_SITE.containsMatchIn(line) -> BuildSettingRole.MinSdk
    NDK_SITE.containsMatchIn(line) -> BuildSettingRole.Ndk
    JVM_SITE.containsMatchIn(line) -> BuildSettingRole.JvmToolchain
    LIBRARY_SITE.containsMatchIn(line) -> BuildSettingRole.Library
    else -> BuildSettingRole.Unknown
}

private fun resolveKey(accessor: String, catalogKeys: Set<String>): String? {
    if (accessor in catalogKeys) return accessor
    val hyphen = accessor.replace('.', '-')
    if (hyphen in catalogKeys) return hyphen
    val dotted = accessor.replace('-', '.')
    return catalogKeys.firstOrNull { it.replace('-', '.').equals(dotted, ignoreCase = true) }
}
