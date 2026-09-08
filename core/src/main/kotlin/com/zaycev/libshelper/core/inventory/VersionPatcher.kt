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
    val regex = Regex("""(^|\n)([ \t]*${Regex.escape(ref)}[ \t]*=[ \t]*")([^"]+)(")""")
    val match = regex.find(text) ?: return null
    val oldRange = match.groups[3]?.range ?: return null
    if (match.groupValues[3] == newVersion) return text
    return text.replaceRange(oldRange, newVersion)
}

fun patchCatalogInlineVersion(text: String, alias: String, newVersion: String): String? {
    if (!SAFE_TOKEN.matches(alias) || !isSafeVersionToken(newVersion)) return null
    var section: String? = null
    val lines = text.lines().toMutableList()
    val aliasPrefix = Regex("""^${Regex.escape(alias)}\s*=""")
    for (index in lines.indices) {
        val trimmed = lines[index].substringBefore('#').trim()
        val sectionMatch = Regex("""^\[([a-zA-Z0-9._-]+)]$""").matchEntire(trimmed)
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
        return lines.joinToString("\n").let { patched ->
            if (text.endsWith("\n") && !patched.endsWith("\n")) patched + "\n" else patched
        }
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
    if (!isSafeVersionToken(newVersion)) return null
    val needle = "$group:$artifact:$oldVersion"
    if (!text.contains(needle)) return null
    if (oldVersion == newVersion) return text
    return text.replace(needle, "$group:$artifact:$newVersion")
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

private fun replaceQuotedField(line: String, name: String, newValue: String): String? {
    val regex = Regex("""(\b${Regex.escape(name)}\s*=\s*")([^"]+)(")""")
    val match = regex.find(line) ?: return null
    val range = match.groups[2]?.range ?: return null
    if (match.groupValues[2] == newValue) return line
    return line.replaceRange(range, newValue)
}
