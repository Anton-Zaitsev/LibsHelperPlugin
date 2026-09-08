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
import kotlin.io.path.readText
import kotlinx.collections.immutable.toPersistentList

fun isGradleProject(root: Path): Boolean =
    root.resolve("settings.gradle.kts").exists() ||
        root.resolve("settings.gradle").exists() ||
        root.resolve("build.gradle.kts").exists() ||
        root.resolve("build.gradle").exists()

fun scanProject(root: Path): ProjectInventory {
    val catalogFile = root.resolve("gradle/libs.versions.toml")
    val catalogPresent = catalogFile.isRegularFile()
    val catalog = if (catalogPresent) {
        parseCatalog(catalogFile.readText())
    } else {
        CatalogParseResult(emptyMap(), emptyList(), emptyList(), emptyMap())
    }

    val propertiesFile = root.resolve("gradle.properties")
    val httpProxy = if (propertiesFile.isRegularFile()) {
        parseHttpProxy(parseGradleProperties(propertiesFile.readText()))
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

    dependencies += catalogToDependencies(catalog, ":", catalogPath = "gradle/libs.versions.toml")

    val settingsFiles = listOf("settings.gradle.kts", "settings.gradle").map { root.resolve(it) }
    for (settings in settingsFiles.filter { it.isRegularFile() }) {
        val text = settings.readText()
        repositories += parseSettingsRepositories(text)
        parseIncludedModules(text).forEach { modules += it }
    }

    val buildFiles = findBuildScripts(root)
    for (file in buildFiles) {
        val module = moduleName(root, file)
        modules += module
        val source = if (file.name.endsWith(".kts")) DependencySource.KotlinDsl else DependencySource.Groovy
        val relative = root.relativize(file).toString().replace('\\', '/')
        val text = file.readText()
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
        dependencies = dependencies.distinctBy {
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
    )
}

private fun findBuildScripts(root: Path): List<Path> {
    val names = setOf("build.gradle.kts", "build.gradle")
    val out = mutableListOf<Path>()
    Files.walk(root).use { stream ->
        stream.filter { it.isRegularFile() && it.name in names }
            .filter { path ->
                val rel = root.relativize(path).toString()
                !rel.contains("/build/") && !rel.startsWith("build/")
            }
            .forEach { out.add(it) }
    }
    return out
}

private fun moduleName(root: Path, file: Path): String {
    val parent = file.parent ?: return ":"
    if (parent == root) return ":"
    return ":" + root.relativize(parent).toString().replace('/', ':')
}
