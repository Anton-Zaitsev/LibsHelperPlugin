package com.zaycev.libshelper.core.inventory

import com.zaycev.libshelper.core.model.DeclaredDependency

private val SAFE_VERSION = Regex("^[A-Za-z0-9._+-]+$")
private val SAFE_TOKEN = Regex("^[A-Za-z0-9._-]+$")

sealed interface VersionPatchTarget {
    data class CatalogVersionRef(val catalogPath: String, val ref: String) : VersionPatchTarget
    data class CatalogInline(val catalogPath: String, val alias: String) : VersionPatchTarget
    data class GradleLiteral(val scriptPath: String, val group: String, val artifact: String, val oldVersion: String) : VersionPatchTarget
    data object Unsupported : VersionPatchTarget
}

fun versionPatchTarget(dependency: DeclaredDependency): VersionPatchTarget {
    val catalogPath = dependency.catalogPath
    val ref = dependency.versionRef
    if (!ref.isNullOrBlank() && !catalogPath.isNullOrBlank()) {
        return VersionPatchTarget.CatalogVersionRef(catalogPath, ref)
    }
    val alias = dependency.catalogAlias
    if (!alias.isNullOrBlank() && !catalogPath.isNullOrBlank()) {
        return VersionPatchTarget.CatalogInline(catalogPath, alias)
    }
    val scriptPath = dependency.usagePath
    val old = dependency.requestedVersion
    if (!scriptPath.isNullOrBlank() && !old.isNullOrBlank()) {
        return VersionPatchTarget.GradleLiteral(
            scriptPath = scriptPath,
            group = dependency.coordinates.group,
            artifact = dependency.coordinates.artifact,
            oldVersion = old,
        )
    }
    return VersionPatchTarget.Unsupported
}

fun isSafeVersionToken(raw: String): Boolean = SAFE_VERSION.matches(raw.trim())

fun patchCatalogVersionRef(text: String, ref: String, newVersion: String): String? {
    if (!SAFE_TOKEN.matches(ref) || !isSafeVersionToken(newVersion)) return null
    for (regex in catalogVersionRefPatterns(ref)) {
        val match = regex.find(text) ?: continue
        val value = match.groups["ver"] ?: continue
        if (value.value == newVersion) return text
        return text.replaceRange(value.range, newVersion)
    }
    return null
}

fun versionFileOf(dependency: DeclaredDependency): String? = when (val target = versionPatchTarget(dependency)) {
    is VersionPatchTarget.CatalogVersionRef -> target.catalogPath
    is VersionPatchTarget.CatalogInline -> target.catalogPath
    is VersionPatchTarget.GradleLiteral -> target.scriptPath
    VersionPatchTarget.Unsupported -> null
}?.replace('\\', '/')

fun readDeclaredVersion(text: String, dependency: DeclaredDependency): String? =
    when (val target = versionPatchTarget(dependency)) {
        is VersionPatchTarget.CatalogVersionRef -> readCatalogVersionRef(text, target.ref)
        is VersionPatchTarget.CatalogInline -> readCatalogInlineVersion(text, target.alias)
        is VersionPatchTarget.GradleLiteral -> readGradleLiteralVersion(text, target.group, target.artifact)
        VersionPatchTarget.Unsupported -> null
    }

internal fun readCatalogVersionRef(text: String, ref: String): String? {
    if (!SAFE_TOKEN.matches(ref)) return null
    for (regex in catalogVersionRefPatterns(ref)) {
        val value = regex.find(text)?.groups?.get("ver")?.value ?: continue
        return value
    }
    return null
}

internal fun readCatalogInlineVersion(text: String, alias: String): String? {
    if (!SAFE_TOKEN.matches(alias)) return null
    var section: String? = null
    val aliasPrefix = Regex("""^${Regex.escape(alias)}\s*=""")
    for (line in text.lines()) {
        val trimmed = line.substringBefore('#').trim()
        val sectionMatch = SECTION_HEADER.matchEntire(trimmed)
        if (sectionMatch != null) {
            section = sectionMatch.groupValues[1]
            continue
        }
        if (section != "libraries" && section != "plugins") continue
        if (!aliasPrefix.containsMatchIn(trimmed)) continue
        if (trimmed.contains("version.ref")) return null
        return readQuotedField(line, "version")
    }
    return null
}

internal fun readGradleLiteralVersion(text: String, group: String, artifact: String): String? {
    if (group.isBlank() || artifact.isBlank()) return null
    val pattern = Regex(
        """["']${Regex.escape(group)}:${Regex.escape(artifact)}:(?<ver>[A-Za-z0-9._+-]+)["'@]""",
    )
    val comments = commentMask(text)
    val versions = pattern.findAll(text)
        .filter { match -> match.range.first < comments.size && !comments[match.range.first] }
        .mapNotNull { it.groups["ver"]?.value }
        .distinct()
        .toList()
    return versions.singleOrNull()
}

fun patchCatalogInlineVersion(text: String, alias: String, newVersion: String): String? {
    if (!SAFE_TOKEN.matches(alias) || !isSafeVersionToken(newVersion)) return null
    val newline = if ("\r\n" in text) "\r\n" else "\n"
    var section: String? = null
    val lines = splitKeepingEnds(text, newline)
    val aliasPrefix = Regex("""^${Regex.escape(alias)}\s*=""")
    for (index in lines.indices) {
        val trimmed = lines[index].substringBefore('#').trim()
        val sectionMatch = SECTION_HEADER.matchEntire(trimmed)
        if (sectionMatch != null) {
            section = sectionMatch.groupValues[1]
            continue
        }
        if (section != "libraries" && section != "plugins") continue
        if (!aliasPrefix.containsMatchIn(trimmed)) continue
        if (trimmed.contains("version.ref")) return null
        val replaced = replaceQuotedField(lines[index], "version", newVersion) ?: return null
        if (replaced == lines[index]) return text
        lines[index] = replaced
        return lines.joinToString(newline)
    }
    return null
}

fun patchGradleLiteral(
    text: String,
    group: String,
    artifact: String,
    oldVersion: String,
    newVersion: String,
): String? {
    if (!isSafeVersionToken(newVersion) || !isSafeVersionToken(oldVersion)) return null
    if (group.isBlank() || artifact.isBlank()) return null
    val pattern = Regex(
        """(?<q>["'])${Regex.escape(group)}:${Regex.escape(artifact)}:${Regex.escape(oldVersion)}(?<end>["':@])""",
    )
    val comments = commentMask(text)
    val matches = pattern.findAll(text).filter { match -> !comments[match.range.first] }.toList()
    if (matches.isEmpty()) return null
    if (oldVersion == newVersion) return text
    var patched = text
    for (match in matches.asReversed()) {
        val versionEnd = match.range.last
        val versionStart = versionEnd - oldVersion.length
        patched = patched.replaceRange(versionStart until versionEnd, newVersion)
    }
    return patched
}

fun applyVersionPatch(
    text: String,
    target: VersionPatchTarget,
    newVersion: String,
): String? = when (target) {
    is VersionPatchTarget.CatalogVersionRef -> patchCatalogVersionRef(text, target.ref, newVersion)
    is VersionPatchTarget.CatalogInline -> patchCatalogInlineVersion(text, target.alias, newVersion)
    is VersionPatchTarget.GradleLiteral -> patchGradleLiteral(
        text = text,
        group = target.group,
        artifact = target.artifact,
        oldVersion = target.oldVersion,
        newVersion = newVersion,
    )
    VersionPatchTarget.Unsupported -> null
}

private fun catalogVersionRefPatterns(ref: String): List<Regex> = listOf(
    Regex("""(?m)^[ \t]*${Regex.escape(ref)}[ \t]*=[ \t]*"(?<ver>[^"]+)""""),
    Regex("""(?m)^[ \t]*${Regex.escape(ref)}[ \t]*=[ \t]*'(?<ver>[^']+)'"""),
    Regex(
        """(?m)^[ \t]*${Regex.escape(ref)}[ \t]*=[ \t]*\{[^}]*?(?:prefer|require|strictly)[ \t]*=[ \t]*["'](?<ver>[^"']+)["']""",
    ),
)

private fun readQuotedField(line: String, name: String): String? {
    val regex = Regex("""(\b${Regex.escape(name)}\s*=\s*)(["'])([^"']+)\2""")
    return regex.find(line)?.groupValues?.getOrNull(3)
}

private fun replaceQuotedField(line: String, name: String, newValue: String): String? {
    val regex = Regex("""(\b${Regex.escape(name)}\s*=\s*)(["'])([^"']+)\2""")
    val match = regex.find(line) ?: return null
    val range = match.groups[3]?.range ?: return null
    if (match.groupValues[3] == newValue) return line
    return line.replaceRange(range, newValue)
}

private fun commentMask(text: String): BooleanArray {
    val mask = BooleanArray(text.length)
    var index = 0
    var lineComment = false
    var blockComment = false
    var quote: Char? = null
    while (index < text.length) {
        val char = text[index]
        when {
            lineComment -> {
                mask[index] = true
                if (char == '\n') lineComment = false
                index++
            }
            blockComment -> {
                mask[index] = true
                val closes = char == '*' && index + 1 < text.length && text[index + 1] == '/'
                if (closes) {
                    mask[index + 1] = true
                    blockComment = false
                    index += 2
                } else {
                    index++
                }
            }
            quote != null -> {
                val escaped = char == '\\' && quote == '"' && index + 1 < text.length
                if (escaped) {
                    index += 2
                } else {
                    if (char == quote) quote = null
                    index++
                }
            }
            char == '/' && index + 1 < text.length && text[index + 1] == '/' -> {
                lineComment = true
                mask[index] = true
                index++
            }
            char == '/' && index + 1 < text.length && text[index + 1] == '*' -> {
                blockComment = true
                mask[index] = true
                index++
            }
            char == '"' || char == '\'' -> {
                quote = char
                index++
            }
            else -> index++
        }
    }
    return mask
}

private val SECTION_HEADER = Regex("""^\[([a-zA-Z0-9._-]+)]$""")

private fun splitKeepingEnds(text: String, newline: String): MutableList<String> {
    if (text.isEmpty()) return mutableListOf("")
    val parts = mutableListOf<String>()
    var start = 0
    while (start <= text.length) {
        val index = text.indexOf(newline, start)
        if (index < 0) {
            parts += text.substring(start)
            break
        }
        parts += text.substring(start, index)
        start = index + newline.length
        if (start == text.length) {
            parts += ""
            break
        }
    }
    return parts
}
