package com.zaycev.libshelper.core.inventory

import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.DeclaredRepository
import com.zaycev.libshelper.core.model.DependencySource
import com.zaycev.libshelper.core.model.ProjectInventory
import com.zaycev.libshelper.core.model.UnresolvedCatalogAlias
import com.zaycev.libshelper.core.proxy.parseGradleProperties
import com.zaycev.libshelper.core.proxy.parseHttpProxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlinx.collections.immutable.toPersistentList

fun isGradleProject(root: Path): Boolean =
    root.resolve("settings.gradle.kts").exists() ||
        root.resolve("settings.gradle").exists() ||
        root.resolve("build.gradle.kts").exists() ||
        root.resolve("build.gradle").exists()

private val scanStack: ThreadLocal<MutableSet<Path>> =
    ThreadLocal.withInitial { HashSet() }

private val INCLUDE_BUILD = Regex("""includeBuild\(\s*["']([^"']+)["']\s*\)""")
private val CATALOG_FILE = Regex("""from\(\s*files\(\s*["']([^"']+)["']\s*\)\s*\)""")

fun scanProject(root: Path): ProjectInventory {
    val normalized = root.toAbsolutePath().normalize()
    val seen = scanStack.get()
    if (!seen.add(normalized)) return emptyInventory()
    return try {
        scanProjectBody(normalized, projectTree(normalized))
    } finally {
        seen.remove(normalized)
    }
}

internal fun scanProject(root: Path, tree: ProjectTree): ProjectInventory {
    val normalized = root.toAbsolutePath().normalize()
    val seen = scanStack.get()
    if (!seen.add(normalized)) return emptyInventory()
    return try {
        scanProjectBody(normalized, tree)
    } finally {
        seen.remove(normalized)
    }
}

private fun emptyInventory(): ProjectInventory = ProjectInventory(
    modules = kotlinx.collections.immutable.persistentListOf(),
    dependencies = kotlinx.collections.immutable.persistentListOf(),
    repositories = kotlinx.collections.immutable.persistentListOf(),
    httpProxy = null,
    catalogPresent = false,
)

@Suppress("CyclomaticComplexMethod")
private fun scanProjectBody(root: Path, tree: ProjectTree): ProjectInventory {
    val settingsTexts = listOf("settings.gradle.kts", "settings.gradle")
        .map { root.resolve(it) }
        .filter { it.isRegularFile() }
        .mapNotNull { path -> path.readCappedText()?.let { GradleText(path, it) } }
    val catalogs = discoverCatalogs(root, settingsTexts.map { it.text })
    val catalogPresent = catalogs.isNotEmpty()
    val mergedVersions = linkedMapOf<String, String>()
    val versionSources = linkedMapOf<String, String>()
    var catalogError: String? = null
    val catalogLibraries = mutableListOf<CatalogLibrary>()
    val catalogPlugins = mutableListOf<CatalogPlugin>()
    val catalogBundles = linkedMapOf<String, List<String>>()
    val versionLines = linkedMapOf<String, Int>()
    val catalogDependencies = mutableListOf<DeclaredDependency>()
    for (file in catalogs) {
        val text = file.readCappedText() ?: continue
        val relative = root.relativize(file).toString().replace('\\', '/')
        versionSources[relative] = text
        val parsed = parseCatalog(text)
        catalogError = catalogError ?: parsed.error
        parsed.versions.forEach { (key, value) -> mergedVersions.putIfAbsent(key, value) }
        if (relative == "gradle/libs.versions.toml") {
            parsed.versionLines.forEach { (key, line) -> versionLines.putIfAbsent(key, line) }
        }
        catalogLibraries += parsed.libraries
        catalogPlugins += parsed.plugins
        parsed.bundles.forEach { (key, value) -> catalogBundles.putIfAbsent(key, value) }
        catalogDependencies += catalogToDependencies(parsed, ":", relative)
    }
    val catalog = CatalogParseResult(
        versions = mergedVersions,
        libraries = catalogLibraries,
        plugins = catalogPlugins,
        bundles = catalogBundles,
        error = catalogError,
        versionLines = versionLines,
    )

    val propertiesFile = root.resolve("gradle.properties")
    val httpProxy = if (propertiesFile.isRegularFile()) {
        parseHttpProxy(parseGradleProperties(propertiesFile.readCappedText().orEmpty()))
    } else {
        null
    }

    val modules = linkedSetOf(":")
    val factsByModule = HashMap<String, ModuleFacts>()
    val dependencies = mutableListOf<DeclaredDependency>()
    val repositories = mutableListOf<DeclaredRepository>()
    val unresolvedAliases = mutableListOf<UnresolvedCatalogAlias>()
    var minSdk: Int? = null
    var compileSdk: Int? = null
    var kotlinVersion: String? = catalog.versions["kotlin"] ?: catalog.versions["kotlin-android"]

    dependencies += catalogDependencies

    for (settingsFile in settingsTexts) {
        val settings = settingsFile.path
        val text = settingsFile.text
        versionSources.putIfAbsent(
            root.relativize(settings).toString().replace('\\', '/'),
            text,
        )
        repositories += parseSettingsRepositories(text)
        parseIncludedModules(text).forEach { modules += it }
        absorbIncludedBuilds(root, text, dependencies, repositories, versionSources, mergedVersions)
    }

    val buildFiles = tree.buildScripts
    for (file in buildFiles) {
        val module = moduleName(root, file)
        modules += module
        val source = if (file.name.endsWith(".kts")) DependencySource.KotlinDsl else DependencySource.Groovy
        val relative = root.relativize(file).toString().replace('\\', '/')
        val text = file.readCappedText() ?: continue
        versionSources[relative] = text
        factsByModule[module] = parseModuleFacts(text)
        val parsed = parseGradleScript(
            text,
            module,
            source,
            catalog,
            moduleDir = file.parent,
            relativeScriptPath = relative,
        )
        dependencies += parsed.dependencies
        repositories += parsed.repositories
        unresolvedAliases += parsed.unresolvedCatalogAliases
        minSdk = minSdk ?: parsed.minSdk
        compileSdk = compileSdk ?: parsed.compileSdk
        kotlinVersion = kotlinVersion ?: parsed.kotlinVersion
    }

    return ProjectInventory(
        modules = modules.toPersistentList(),
        dependencies = withoutKnownPlugins(tree.pluginIds, dependencies).distinctBy {
            "${it.module}:${it.coordinates.key}:${it.configuration}:${it.requestedVersion}:" +
                "${it.usagePath}:${it.usageLine}:${it.catalogPath}:${it.catalogLine}"
        }.toPersistentList(),
        repositories = repositories.distinctBy {
            "${it.url}|${it.scope}|${it.exclusive}|${it.includeGroups.sorted().joinToString()}"
        }.toPersistentList(),
        httpProxy = httpProxy,
        kotlinVersion = kotlinVersion,
        minSdk = minSdk,
        compileSdk = compileSdk,
        catalogPresent = catalogPresent,
        catalogError = catalog.error,
        unresolvedCatalogAliases = unresolvedAliases.distinctBy { "${it.module}:${it.alias}" }.toPersistentList(),
        moduleGraph = buildModuleGraph(modules, factsByModule),
        catalogVersions = mergedVersions,
        versionSources = versionSources,
        fingerprint = tree.fingerprint,
    )
}

private fun discoverCatalogs(root: Path, settingsTexts: List<String>): List<Path> {
    val found = linkedSetOf<Path>()
    val gradle = root.resolve("gradle")
    if (Files.isDirectory(gradle)) {
        Files.newDirectoryStream(gradle, "*.versions.toml").use { stream ->
            stream.forEach { found.add(it.toAbsolutePath().normalize()) }
        }
    }
    for (text in settingsTexts) {
        for (match in CATALOG_FILE.findAll(text)) {
            val path = pathInsideRoot(root, match.groupValues[1]) ?: continue
            if (path.isRegularFile()) found.add(path)
        }
    }
    return found.sortedBy { if (it.name == "libs.versions.toml") 0 else 1 }
}

private data class GradleText(val path: Path, val text: String)

private fun absorbIncludedBuilds(
    root: Path,
    text: String,
    dependencies: MutableList<DeclaredDependency>,
    repositories: MutableList<DeclaredRepository>,
    versionSources: MutableMap<String, String>,
    mergedVersions: MutableMap<String, String>,
) {
    for (match in INCLUDE_BUILD.findAll(text)) {
        val extra = root.resolve(match.groupValues[1]).normalize()
        if (extra.startsWith(root) || !isGradleProject(extra)) continue
        val nested = scanProject(extra)
        dependencies += nested.dependencies
        repositories += nested.repositories
        val prefix = root.relativize(extra).toString().replace('\\', '/')
        nested.versionSources.forEach { (path, body) ->
            versionSources.putIfAbsent("$prefix/$path", body)
        }
        nested.catalogVersions.forEach { (key, value) -> mergedVersions.putIfAbsent(key, value) }
    }
}

private fun moduleName(root: Path, file: Path): String {
    val parent = file.parent ?: return ":"
    if (parent == root) return ":"
    return ":" + root.relativize(parent).toString().replace('/', ':')
}
