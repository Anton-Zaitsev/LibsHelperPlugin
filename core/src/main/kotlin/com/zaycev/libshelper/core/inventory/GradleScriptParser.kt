package com.zaycev.libshelper.core.inventory

import com.zaycev.libshelper.core.model.Coordinates
import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.DeclaredRepository
import com.zaycev.libshelper.core.model.DependencySource
import com.zaycev.libshelper.core.model.RepositoryScope
import com.zaycev.libshelper.core.model.RepositoryType
import com.zaycev.libshelper.core.model.SourceLocation
import com.zaycev.libshelper.core.model.UnresolvedCatalogAlias
import com.zaycev.libshelper.core.proxy.detectRepositoryKind
import com.zaycev.libshelper.core.proxy.detectRepositoryType
import com.zaycev.libshelper.core.proxy.officialGoogleMaven
import com.zaycev.libshelper.core.proxy.officialMavenCentral
import com.zaycev.libshelper.core.proxy.officialPluginPortal
import java.nio.file.Path
import kotlinx.collections.immutable.toPersistentSet

private val COORD_LITERAL = Regex(
    """(?<![A-Za-z])(?:implementation|api|ksp|kapt|compileOnly|runtimeOnly|""" +
        """testImplementation|androidTestImplementation|debugImplementation|annotationProcessor)""" +
        """\s*\(\s*(?:platform\s*\(\s*)?"([^":\s]+):([^":\s]+):([^"]+)"\s*\)?""",
)
private val LIBS_ALIAS = Regex("""(?<![A-Za-z0-9_.])libs\.(?!versions\b)(?!plugins\b)([A-Za-z0-9_.]+)""")
private val PLUGIN_ALIAS = Regex("""alias\s*\(\s*libs\.plugins\.([A-Za-z0-9_.]+)""")
private val MAVEN_URL = Regex("""maven\s*(?:\{\s*url\s*=\s*(?:uri\()?["']([^"']+)["']|[(]\s*["']([^"']+)["'])""")
private val MIN_SDK = Regex("""minSdk(?:\s*=\s*|\s*\.\s*set\s*\(\s*)(\d+)""")
private val COMPILE_SDK = Regex("""compileSdk(?:\s*=\s*|\s*\.\s*set\s*\(\s*)(\d+)""")
private val KOTLIN_VERSION = Regex("""(?:kotlin(?:-android)?)\s*=\s*"(\d+\.\d+[^\"]*)"|id\("org\.jetbrains\.kotlin\.android"\)\s+version\s+"([^"]+)"""")

data class ScriptParseResult(
    val dependencies: List<DeclaredDependency>,
    val repositories: List<DeclaredRepository>,
    val minSdk: Int? = null,
    val compileSdk: Int? = null,
    val kotlinVersion: String? = null,
    val unresolvedCatalogAliases: List<UnresolvedCatalogAlias> = emptyList(),
)

fun parseGradleScript(
    text: String,
    module: String,
    source: DependencySource,
    catalog: CatalogParseResult? = null,
    scope: RepositoryScope = RepositoryScope.Dependency,
    moduleDir: Path? = null,
    relativeScriptPath: String? = null,
): ScriptParseResult {
    val repositories = parseRepositories(text, scope)
    val fromLiterals = COORD_LITERAL.findAll(text).map { match ->
        val isBom = match.value.contains("platform(")
        val interpolated = catalogVersionInterpolation(match.groupValues[3], catalog)
        DeclaredDependency(
            coordinates = Coordinates(match.groupValues[1], match.groupValues[2]),
            requestedVersion = interpolated?.second
                ?: match.groupValues[3].takeIf { !it.startsWith("$") && !it.contains("{") },
            versionRef = interpolated?.first,
            configuration = configurationOf(match.value),
            module = module,
            source = source,
            isBom = isBom || match.groupValues[2].contains("bom", ignoreCase = true),
            catalogPath = interpolated?.let { "gradle/libs.versions.toml" },
            catalogVersionLine = interpolated?.let { (ref, _) -> catalog?.versionLines?.get(ref) },
            usagePath = relativeScriptPath,
            usageLine = lineNumberAt(text, match.range.first),
            usageLocations = usageLocations(relativeScriptPath, lineNumberAt(text, match.range.first)),
        )
    }.toList()

    val unresolvedAliases = mutableListOf<UnresolvedCatalogAlias>()
    val fromAliases = LIBS_ALIAS.findAll(text).mapNotNull { match ->
        val alias = match.groupValues[1].replace('.', '-')
        val dotted = match.groupValues[1]
        val lib = catalog?.libraries?.firstOrNull {
            it.alias.equals(alias, ignoreCase = true) ||
                it.alias.equals(dotted, ignoreCase = true) ||
                it.alias.replace('-', '.') == dotted
        }
        if (lib == null) {
            unresolvedAliases += UnresolvedCatalogAlias(
                alias = dotted,
                module = module,
                configuration = configurationAt(text, match.range.first),
            )
            return@mapNotNull null
        }
        DeclaredDependency(
            coordinates = lib.coordinates,
            requestedVersion = lib.version,
            catalogAlias = lib.alias,
            versionRef = lib.versionRef,
            configuration = configurationAt(text, match.range.first),
            module = module,
            source = source,
            isBom = isPlatformCall(text, match.range.first) || lib.coordinates.artifact.contains("bom", ignoreCase = true),
            catalogPath = "gradle/libs.versions.toml",
            catalogLine = lib.line,
            catalogVersionLine = lib.versionRef?.let { ref -> checkNotNull(catalog).versionLines[ref] },
            usagePath = relativeScriptPath,
            usageLine = lineNumberAt(text, match.range.first),
            usageLocations = usageLocations(relativeScriptPath, lineNumberAt(text, match.range.first)),
        )
    }.toList()

    val fromPlugins = PLUGIN_ALIAS.findAll(text).mapNotNull { match ->
        val alias = match.groupValues[1].replace('.', '-')
        val dotted = match.groupValues[1]
        val plugin = catalog?.plugins?.firstOrNull {
            it.alias.equals(alias, ignoreCase = true) ||
                it.alias.equals(dotted, ignoreCase = true) ||
                it.alias.replace('-', '.') == dotted
        } ?: return@mapNotNull null
        DeclaredDependency(
            coordinates = pluginIdToCoordinates(plugin.id),
            requestedVersion = plugin.version,
            catalogAlias = plugin.alias,
            versionRef = plugin.versionRef,
            configuration = "plugin",
            module = module,
            source = source,
            isPlugin = true,
            catalogPath = "gradle/libs.versions.toml",
            catalogLine = plugin.line,
            catalogVersionLine = plugin.versionRef?.let { ref -> checkNotNull(catalog).versionLines[ref] },
            usagePath = relativeScriptPath,
            usageLine = lineNumberAt(text, match.range.first),
            usageLocations = usageLocations(relativeScriptPath, lineNumberAt(text, match.range.first)),
        )
    }.toList()

    val fromLocal = parseLocalFileDependencies(text, moduleDir, module, source)

    val kotlinVersion = KOTLIN_VERSION.find(text)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
        ?: catalog?.versions?.get("kotlin")
        ?: catalog?.versions?.get("kotlin-android")

    return ScriptParseResult(
        dependencies = (fromLiterals + fromAliases + fromPlugins + fromLocal).distinctBy {
            it.coordinates.key + it.module + it.configuration + it.localFileName.orEmpty() +
                it.usagePath.orEmpty() + (it.usageLine ?: 0)
        },
        repositories = repositories,
        minSdk = MIN_SDK.find(text)?.groupValues?.get(1)?.toIntOrNull(),
        compileSdk = COMPILE_SDK.find(text)?.groupValues?.get(1)?.toIntOrNull(),
        kotlinVersion = kotlinVersion,
        unresolvedCatalogAliases = unresolvedAliases,
    )
}

fun parseRepositories(text: String, scope: RepositoryScope): List<DeclaredRepository> {
    val found = mutableListOf<DeclaredRepository>()
    if (text.contains("google()")) {
        officialGoogleMaven().first().let { url ->
            found += repo("google", url, RepositoryType.Google, scope)
        }
    }
    if (text.contains("mavenCentral()")) {
        found += repo("mavenCentral", officialMavenCentral(), RepositoryType.MavenCentral, scope)
    }
    if (text.contains("gradlePluginPortal()")) {
        found += repo("gradlePluginPortal", officialPluginPortal(), RepositoryType.PluginPortal, scope)
    }
    if (text.contains("mavenLocal()")) {
        found += repo("mavenLocal", "mavenLocal", RepositoryType.MavenLocal, scope)
    }
    if (text.contains("flatDir")) {
        found += repo("flatDir", "flatDir", RepositoryType.FlatDir, scope)
    }
    MAVEN_URL.findAll(text).forEach { match ->
        val url = match.groupValues[1].ifBlank { match.groupValues[2] }
        val name = nameFromUrl(url)
        found += repo(name, url, detectRepositoryType(url, name), scope)
    }
    return found.distinctBy { it.url }
}

fun parseSettingsRepositories(text: String): List<DeclaredRepository> {
    val plugin = extractBlock(text, "pluginManagement")
    val drm = extractBlock(text, "dependencyResolutionManagement")
    val leftover = stripNamedBlocks(
        text,
        "pluginManagement",
        "dependencyResolutionManagement",
        "buildCache",
        "develocity",
        "plugins",
    )
    return parseRepositorySection(plugin, RepositoryScope.Plugin) +
        parseRepositorySection(drm, RepositoryScope.Dependency) +
        parseRepositorySection(leftover, RepositoryScope.Dependency)
}

internal fun parseRepositorySection(text: String, scope: RepositoryScope): List<DeclaredRepository> {
    if (text.isBlank()) return emptyList()
    val exclusive = parseExclusiveContent(text, scope)
    val remainder = stripNamedBlocks(text, "exclusiveContent")
    return exclusive + parseRepositories(remainder, scope)
}

private val INCLUDE_GROUP = Regex("""includeGroup\s*\(\s*"([^"]+)"""")

private fun parseExclusiveContent(text: String, scope: RepositoryScope): List<DeclaredRepository> =
    extractNamedBlocks(text, "exclusiveContent").mapNotNull { block ->
        val url = MAVEN_URL.find(block)?.let { match ->
            match.groupValues[1].ifBlank { match.groupValues[2] }
        } ?: return@mapNotNull null
        val groups = INCLUDE_GROUP.findAll(block).map { it.groupValues[1] }.toSet().toPersistentSet()
        val name = nameFromUrl(url)
        DeclaredRepository(
            name = name,
            url = normalizeRepoUrl(url),
            type = detectRepositoryType(url, name),
            kind = detectRepositoryKind(url),
            scope = scope,
            includeGroups = groups,
            exclusive = true,
        )
    }

private fun repo(
    name: String,
    url: String,
    type: RepositoryType,
    scope: RepositoryScope,
): DeclaredRepository = DeclaredRepository(
    name = name,
    url = normalizeRepoUrl(url),
    type = type,
    kind = detectRepositoryKind(url),
    scope = scope,
)

private fun nameFromUrl(url: String): String {
    val withoutScheme = url.removePrefix("https://").removePrefix("http://").trimEnd('/')
    val pathTail = withoutScheme.substringAfter('/', missingDelimiterValue = "")
    return pathTail.substringAfterLast('/').ifBlank { withoutScheme.substringBefore('/') }
}

fun normalizeRepoUrl(url: String): String =
    if (url.endsWith("/") || url == "flatDir" || url == "mavenLocal") url else "$url/"

private fun configurationOf(raw: String): String {
    val start = raw.takeWhile { it.isLetter() }
    return if (start.isBlank() || start == "libs") "implementation" else start
}

private fun configurationAt(text: String, offset: Int): String {
    val from = (offset - 80).coerceAtLeast(0)
    val prefix = text.substring(from, offset)
    val match = Regex("""([A-Za-z]+)\s*\(\s*(?:platform\s*\(\s*)?$""").find(prefix)
    val name = match?.groupValues?.get(1)
    return if (name.isNullOrBlank() || name == "libs") "implementation" else name
}

private fun usageLocations(path: String?, line: Int): List<SourceLocation> =
    if (path == null) emptyList() else listOf(SourceLocation(path, line))

private fun isPlatformCall(text: String, offset: Int): Boolean {
    val from = (offset - 16).coerceAtLeast(0)
    return text.substring(from, offset).contains("platform(")
}

internal fun lineNumberAt(text: String, offset: Int): Int =
    text.take(offset.coerceIn(0, text.length)).count { it == '\n' } + 1

private val LIBS_VERSION_INTERPOLATION = Regex(
    """^\$\{libs\.versions\.([A-Za-z0-9_.]+)(?:\.get\(\))?}$"""
)

private fun catalogVersionInterpolation(
    rawVersion: String,
    catalog: CatalogParseResult?,
): Pair<String, String>? {
    val accessor = LIBS_VERSION_INTERPOLATION.matchEntire(rawVersion.trim())?.groupValues?.get(1)
        ?: return null
    return resolveCatalogVersion(catalog, accessor)
}
