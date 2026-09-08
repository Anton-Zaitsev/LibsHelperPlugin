package com.zaycev.libshelper.core.inventory

import com.zaycev.libshelper.core.model.Coordinates
import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.DependencySource

data class CatalogLibrary(
    val alias: String,
    val coordinates: Coordinates,
    val version: String?,
    val versionRef: String?,
    val line: Int = 1,
)

data class CatalogPlugin(
    val alias: String,
    val id: String,
    val version: String?,
    val versionRef: String?,
    val line: Int = 1,
)

data class CatalogParseResult(
    val versions: Map<String, String>,
    val libraries: List<CatalogLibrary>,
    val plugins: List<CatalogPlugin>,
    val bundles: Map<String, List<String>>,
    val error: String? = null,
    val versionLines: Map<String, Int> = emptyMap(),
)

fun parseCatalog(text: String): CatalogParseResult {
    return runCatching { parseCatalogInternal(text) }
        .getOrElse { CatalogParseResult(emptyMap(), emptyList(), emptyList(), emptyMap(), it.message) }
}

private fun parseCatalogInternal(text: String): CatalogParseResult {
    val versions = linkedMapOf<String, String>()
    val versionLines = linkedMapOf<String, Int>()
    val libraries = mutableListOf<CatalogLibrary>()
    val plugins = mutableListOf<CatalogPlugin>()
    val bundles = linkedMapOf<String, List<String>>()
    var section: String? = null
    var tableAlias: String? = null
    var tableLine = 1
    val tableFields = linkedMapOf<String, String>()

    fun flushTable() {
        val alias = tableAlias ?: return
        when (section) {
            "libraries" -> parseLibraryFields(alias, tableFields, tableLine)?.let { libraries += it }
            "plugins" -> parsePluginFields(alias, tableFields, tableLine)?.let { plugins += it }
        }
        tableAlias = null
        tableFields.clear()
    }

    text.lines().forEachIndexed { index, raw ->
        val line = stripTomlComment(raw).trim()
        if (line.isEmpty()) return@forEachIndexed
        val sectionMatch = SECTION.matchEntire(line)
        if (sectionMatch != null) {
            flushTable()
            val full = sectionMatch.groupValues[1]
            val root = full.substringBefore('.')
            section = root
            val rest = full.substringAfter('.', missingDelimiterValue = "")
            if (rest.isNotEmpty() && (root == "libraries" || root == "plugins")) {
                tableAlias = rest.replace('.', '-')
                tableLine = index + 1
            }
            return@forEachIndexed
        }
        val at = index + 1
        when (section) {
            "versions" -> parseVersionEntry(line)?.let { parsed ->
                versions[parsed.first] = parsed.second
                versionLines[parsed.first] = at
            }
            "libraries" -> if (tableAlias != null) {
                parseFieldLine(line)?.let { tableFields[it.first] = it.second }
            } else {
                parseLibraryLine(line, at)?.let { libraries += it }
            }
            "plugins" -> if (tableAlias != null) {
                parseFieldLine(line)?.let { tableFields[it.first] = it.second }
            } else {
                parsePluginLine(line, at)?.let { plugins += it }
            }
            "bundles" -> parseBundleLine(line)?.let { bundles[it.first] = it.second }
        }
    }
    flushTable()

    val resolvedLibraries = libraries.map { lib ->
        val version = lib.version ?: lib.versionRef?.let { versions[it] }
        lib.copy(version = version)
    }
    val resolvedPlugins = plugins.map { plugin ->
        val version = plugin.version ?: plugin.versionRef?.let { versions[it] }
        plugin.copy(version = version)
    }
    return CatalogParseResult(
        versions = versions,
        libraries = resolvedLibraries,
        plugins = resolvedPlugins,
        bundles = bundles,
        versionLines = versionLines,
    )
}

fun catalogToDependencies(
    catalog: CatalogParseResult,
    module: String,
    catalogPath: String = "gradle/libs.versions.toml",
): List<DeclaredDependency> {
    val libs = catalog.libraries.map { lib ->
        DeclaredDependency(
            coordinates = lib.coordinates,
            requestedVersion = lib.version,
            catalogAlias = lib.alias,
            versionRef = lib.versionRef,
            configuration = "implementation",
            module = module,
            source = DependencySource.Toml,
            isBom = lib.coordinates.artifact.contains("bom", ignoreCase = true),
            catalogPath = catalogPath,
            catalogLine = lib.line,
            catalogVersionLine = lib.versionRef?.let { catalog.versionLines[it] },
        )
    }
    val plugins = catalog.plugins.map { plugin ->
        val coords = pluginIdToCoordinates(plugin.id)
        DeclaredDependency(
            coordinates = coords,
            requestedVersion = plugin.version,
            catalogAlias = plugin.alias,
            versionRef = plugin.versionRef,
            configuration = "plugin",
            module = module,
            source = DependencySource.Toml,
            isPlugin = true,
            catalogPath = catalogPath,
            catalogLine = plugin.line,
            catalogVersionLine = plugin.versionRef?.let { catalog.versionLines[it] },
        )
    }
    return libs + plugins
}

fun pluginIdToCoordinates(id: String): Coordinates {
    val group = id
    val artifact = "$id.gradle.plugin"
    return Coordinates(group, artifact)
}

fun catalogVersionKey(accessor: String): List<String> {
    val dotted = accessor.trim()
    if (dotted.isEmpty()) return emptyList()
    val hyphen = dotted.replace('.', '-')
    return listOf(dotted, hyphen, dotted.replace('-', '.')).distinct()
}

fun resolveCatalogVersion(catalog: CatalogParseResult?, accessor: String): Pair<String, String>? {
    if (catalog == null) return null
    for (key in catalogVersionKey(accessor)) {
        catalog.versions[key]?.let { return key to it }
    }
    val normalized = accessor.replace('-', '.')
    val found = catalog.versions.entries.firstOrNull { (key, _) ->
        key.replace('-', '.').equals(normalized, ignoreCase = true)
    } ?: return null
    return found.key to found.value
}

internal fun stripTomlComment(raw: String): String {
    var inDouble = false
    var inSingle = false
    for (index in raw.indices) {
        val char = raw[index]
        when {
            char == '"' && !inSingle -> inDouble = !inDouble
            char == '\'' && !inDouble -> inSingle = !inSingle
            char == '#' && !inDouble && !inSingle -> return raw.substring(0, index)
        }
    }
    return raw
}

private val SECTION = Regex("""^\[([a-zA-Z0-9._-]+)]$""")
private val ASSIGNMENT = Regex("""^([A-Za-z0-9._-]+)\s*=\s*(.+)$""")
private val QUOTED = Regex("""^(["'])([^"']*)\1$""")

private fun parseVersionEntry(line: String): Pair<String, String>? {
    val match = ASSIGNMENT.matchEntire(line) ?: return null
    val key = match.groupValues[1]
    val body = match.groupValues[2].trim()
    val version = quotedValue(body) ?: richVersionValue(body) ?: return null
    return key to version
}

private fun parseLibraryLine(line: String, at: Int): CatalogLibrary? {
    val match = ASSIGNMENT.matchEntire(line) ?: return null
    val alias = match.groupValues[1]
    val body = match.groupValues[2].trim()
    val compact = quotedValue(body)
    if (compact != null) {
        return parseCompactLibrary(alias, compact, at)
    }
    return libraryFromFields(alias, fieldsOf(body), at)
}

private fun parsePluginLine(line: String, at: Int): CatalogPlugin? {
    val match = ASSIGNMENT.matchEntire(line) ?: return null
    val alias = match.groupValues[1]
    val body = match.groupValues[2].trim()
    return pluginFromFields(alias, fieldsOf(body), at)
}

private fun parseLibraryFields(
    alias: String,
    fields: Map<String, String>,
    at: Int,
): CatalogLibrary? = libraryFromFields(alias, fields, at)

private fun parsePluginFields(
    alias: String,
    fields: Map<String, String>,
    at: Int,
): CatalogPlugin? = pluginFromFields(alias, fields, at)

private fun libraryFromFields(alias: String, fields: Map<String, String>, at: Int): CatalogLibrary? {
    val module = scalarField(fields, "module")
    val group: String
    val artifact: String
    if (module != null && module.contains(':')) {
        group = module.substringBefore(':')
        artifact = module.substringAfter(':')
    } else {
        group = scalarField(fields, "group") ?: return null
        artifact = scalarField(fields, "name") ?: return null
    }
    val versionRef = versionRefOf(fields)
    val version = scalarField(fields, "version") ?: richVersionValue(fields["version"].orEmpty())
    return CatalogLibrary(alias, Coordinates(group, artifact), version, versionRef, at)
}

private fun pluginFromFields(alias: String, fields: Map<String, String>, at: Int): CatalogPlugin? {
    val id = scalarField(fields, "id") ?: return null
    val versionRef = versionRefOf(fields)
    val version = scalarField(fields, "version") ?: richVersionValue(fields["version"].orEmpty())
    return CatalogPlugin(alias, id, version, versionRef, at)
}

private fun parseCompactLibrary(alias: String, gav: String, at: Int): CatalogLibrary? {
    val parts = gav.split(':')
    if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return null
    val version = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
    return CatalogLibrary(alias, Coordinates(parts[0], parts[1]), version, versionRef = null, at)
}

private fun parseBundleLine(line: String): Pair<String, List<String>>? {
    val alias = line.substringBefore('=').trim()
    val body = line.substringAfter('=', "").trim()
    val items = Regex(""""([^"]+)"""").findAll(body).map { it.groupValues[1] }.toList()
    if (alias.isEmpty() || items.isEmpty()) return null
    return alias to items
}

private fun parseFieldLine(line: String): Pair<String, String>? {
    val match = ASSIGNMENT.matchEntire(line) ?: return null
    return match.groupValues[1] to match.groupValues[2].trim()
}

private fun scalarField(fields: Map<String, String>, key: String): String? {
    val raw = fields[key] ?: return null
    return quotedValue(raw) ?: raw.takeIf { !it.startsWith("{") && '=' !in it }
}

private fun fieldsOf(body: String): Map<String, String> {
    val inner = body.trim().removePrefix("{").removeSuffix("}").trim()
    if (inner.isEmpty()) return emptyMap()
    val fields = linkedMapOf<String, String>()
    quotedField(inner, "module")?.let { fields["module"] = it }
    quotedField(inner, "group")?.let { fields["group"] = it }
    quotedField(inner, "name")?.let { fields["name"] = it }
    quotedField(inner, "id")?.let { fields["id"] = it }
    quotedField(inner, "version")?.let { fields["version"] = it }
    quotedField(inner, "version.ref")?.let { fields["version.ref"] = it }
    nestedTable(inner, "version")?.let { nested ->
        fields["version"] = "{ $nested }"
        quotedField(nested, "ref")?.let { fields["version.ref"] = it }
        richVersionValue("{ $nested }")?.let { fields.putIfAbsent("version", it) }
    }
    return fields
}

private fun versionRefOf(fields: Map<String, String>): String? {
    fields["version.ref"]?.let { raw ->
        return quotedValue(raw) ?: raw.takeIf { !it.contains('{') && !it.contains('=') }
    }
    nestedTable(fields["version"].orEmpty(), null)?.let { nested ->
        quotedField(nested, "ref")?.let { return it }
    }
    return quotedField(fields["version"].orEmpty(), "ref")
}

private fun nestedTable(body: String, name: String?): String? {
    if (name == null) {
        val trimmed = body.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed.removePrefix("{").removeSuffix("}").trim()
        }
        return null
    }
    val match = Regex("""\b${Regex.escape(name)}\s*=\s*\{([^}]*)}""").find(body) ?: return null
    return match.groupValues[1].trim()
}

private fun richVersionValue(body: String): String? {
    val nested = nestedTable(body, "version") ?: nestedTable(body, null) ?: body
    return quotedField(nested, "prefer")
        ?: quotedField(nested, "require")
        ?: quotedField(nested, "strictly")
}

private fun quotedField(body: String, name: String): String? {
    val escaped = Regex.escape(name)
    val double = Regex("""\b$escaped\s*=\s*"([^"]+)"""").find(body)
    if (double != null) return double.groupValues[1]
    val single = Regex("""\b$escaped\s*=\s*'([^']+)'""").find(body)
    return single?.groupValues?.get(1)
}

private fun quotedValue(body: String): String? {
    val trimmed = body.trim()
    val match = QUOTED.matchEntire(trimmed) ?: return null
    return match.groupValues[2]
}
