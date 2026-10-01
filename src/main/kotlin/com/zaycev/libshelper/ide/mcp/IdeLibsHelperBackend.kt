package com.zaycev.libshelper.ide.mcp

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.zaycev.libshelper.core.inventory.applyVersionPatch
import com.zaycev.libshelper.core.inventory.isSafeVersionToken
import com.zaycev.libshelper.core.inventory.pathInsideRoot
import com.zaycev.libshelper.core.inventory.versionPatchTarget
import com.zaycev.libshelper.core.model.LibraryAdvice
import com.zaycev.libshelper.core.model.OfferScore
import com.zaycev.libshelper.core.model.ProjectReport
import com.zaycev.libshelper.core.model.displayTarget
import com.zaycev.libshelper.ide.LibsHelperService
import com.zaycev.libshelper.ide.diagnostics.LibsHelperDiagnostics
import com.zaycev.libshelper.ide.project.gradleProjectRoots
import com.zaycev.libshelper.ide.project.primaryGradleRoot
import com.zaycev.libshelper.mcp.McpDependencyView
import com.zaycev.libshelper.mcp.McpProjectRef
import com.zaycev.libshelper.mcp.McpUpdateGroup
import com.zaycev.libshelper.mcp.LibsHelperBackend
import java.nio.file.Path
import kotlin.io.path.readText

class IdeLibsHelperBackend(
    private val allowWrites: () -> Boolean,
) : LibsHelperBackend {
    override fun projects(): List<McpProjectRef> =
        ProjectManager.getInstance().openProjects.flatMap { project ->
            gradleProjectRoots(project).map { root -> McpProjectRef(project.name, root.toString()) }
        }

    override suspend fun analyze(projectPath: String): String {
        val project = projectAt(projectPath) ?: return "project not open"
        val report = project.service<LibsHelperService>().refreshAndAwait(forceRefresh = false)
        return "libraries=${report?.libraries?.size ?: 0} settings=${report?.buildSettings?.size ?: 0}"
    }

    override suspend fun dependencies(projectPath: String, outdatedOnly: Boolean): List<McpDependencyView> {
        val report = reportOf(projectPath) ?: return emptyList()
        return report.libraries
            .filter { !outdatedOnly || it.advice.isOutdated }
            .map { it.toView() }
    }

    override suspend fun dependency(projectPath: String, key: String): McpDependencyView? =
        reportOf(projectPath)?.libraries?.firstOrNull { it.matches(key) }?.toView()

    override suspend fun recommend(projectPath: String): List<McpUpdateGroup> {
        val report = reportOf(projectPath) ?: return emptyList()
        val buckets = linkedMapOf(
            "safe" to mutableListOf<McpDependencyView>(),
            "careful" to mutableListOf(),
            "avoid" to mutableListOf(),
        )
        for (item in report.libraries) {
            val view = item.toView()
            val score = item.advice.preferredStable?.score
            val bucket = when (score) {
                OfferScore.Safe, OfferScore.Recommended, null -> if (item.advice.isOutdated) "safe" else continue
                OfferScore.Risky -> "careful"
                OfferScore.DoNot -> "avoid"
            }
            buckets.getValue(bucket) += view
        }
        return buckets.map { (title, items) -> McpUpdateGroup(title, items) }.filter { it.items.isNotEmpty() }
    }

    override suspend fun impact(projectPath: String, key: String, version: String): String {
        val item = reportOf(projectPath)?.libraries?.firstOrNull { it.matches(key) }
            ?: return "not found"
        val reasons = item.toView().reasons.joinToString("; ").ifBlank { "no extra warnings" }
        return "${item.advice.dependency.coordinates.key}: ${item.advice.current?.raw} -> $version. $reasons"
    }

    override suspend fun applyUpdate(projectPath: String, key: String, version: String, dryRun: Boolean): String {
        if (!isSafeVersionToken(version)) return "rejected"
        val project = projectAt(projectPath) ?: return "project not open"
        val item = project.service<LibsHelperService>().lastReport?.libraries?.firstOrNull { it.matches(key) }
            ?: return "not found"
        val preview = previewDiff(project, item, version)
        if (dryRun || !allowWrites()) {
            return "dry-run\n$preview"
        }
        LibsHelperDiagnostics.record("mcp", "apply-update", "$key $version")
        val applied = project.service<LibsHelperService>().applyVersion(item.advice.dependency, version)
        return if (applied) "applied\n$preview" else "failed\n$preview"
    }

    override suspend fun buildSettings(projectPath: String): List<String> {
        val report = reportOf(projectPath) ?: return emptyList()
        return report.buildSettings.map { advice ->
            buildString {
                append(advice.key)
                append(" role=")
                append(advice.role)
                append(" current=")
                append(advice.current)
                append(" tracked=")
                append(advice.trackedVersion.orEmpty())
                append(" suggestions=")
                append(advice.suggestions.joinToString(","))
                append(" note=")
                append(advice.note.orEmpty())
            }
        }
    }

    override suspend fun moduleGraph(projectPath: String, mermaid: Boolean): String {
        val map = reportOf(projectPath)?.moduleMap ?: return ""
        if (!mermaid) {
            return map.links.joinToString("\n") { link ->
                "${map.nodes[link.fromIndex].id} -> ${map.nodes[link.toIndex].id} ${link.kind}"
            }
        }
        return buildString {
            appendLine("flowchart LR")
            map.links.forEach { link ->
                append(map.nodes[link.fromIndex].id.replace(":", "_"))
                append(" --> ")
                append(map.nodes[link.toIndex].id.replace(":", "_"))
                appendLine()
            }
        }
    }

    override suspend fun repositories(projectPath: String): List<String> =
        reportOf(projectPath)?.inventory?.repositories?.map { repo ->
            "${repo.url} ${repo.kind} ${repo.scope}"
        }.orEmpty()

    private suspend fun reportOf(path: String): ProjectReport? {
        val service = projectAt(path)?.service<LibsHelperService>() ?: return null
        return service.lastReport ?: service.refreshAndAwait(forceRefresh = false)
    }

    private fun projectAt(path: String): Project? {
        val wanted = runCatching { Path.of(path).toAbsolutePath().normalize() }.getOrNull() ?: return null
        return ProjectManager.getInstance().openProjects.firstOrNull { project ->
            gradleProjectRoots(project).any { it.toAbsolutePath().normalize() == wanted } ||
                primaryGradleRoot(project)?.toAbsolutePath()?.normalize() == wanted
        }
    }
}

private fun LibraryAdvice.matches(key: String): Boolean =
    advice.dependency.coordinates.key == key || advice.dependency.catalogAlias == key

private fun LibraryAdvice.toView(): McpDependencyView {
    val target = advice.displayTarget()
    val reasons = buildList {
        advice.preferredStable?.conflicts?.forEach { add(it.type.name) }
        advice.preferredStable?.consequences?.forEach { add(it.id.name) }
        if (advice.dependency.versionRef != null) add("shared:${advice.dependency.versionRef}")
    }
    return McpDependencyView(
        key = advice.dependency.coordinates.key,
        current = advice.current?.raw,
        recommended = target?.version,
        channel = target?.channel?.name,
        outdated = advice.isOutdated,
        score = advice.preferredStable?.score?.name,
        reasons = reasons,
        versions = advice.candidates.map { it.version.raw },
    )
}

private suspend fun previewDiff(project: Project, item: LibraryAdvice, version: String): String {
    val root = primaryGradleRoot(project) ?: return "no project root"
    val target = versionPatchTarget(item.advice.dependency)
    val relative = when (target) {
        is com.zaycev.libshelper.core.inventory.VersionPatchTarget.CatalogVersionRef -> target.catalogPath
        is com.zaycev.libshelper.core.inventory.VersionPatchTarget.CatalogInline -> target.catalogPath
        is com.zaycev.libshelper.core.inventory.VersionPatchTarget.GradleLiteral -> target.scriptPath
        com.zaycev.libshelper.core.inventory.VersionPatchTarget.Unsupported -> return "unsupported"
    }
    val file = pathInsideRoot(root, relative) ?: return "rejected"
    val text = readEditorOrDisk(file) ?: return "unreadable"
    val patched = applyVersionPatch(text, target, version) ?: return "no change"
    return patched.lineSequence().zip(text.lineSequence()).filter { it.first != it.second }
        .joinToString("\n") { (next, previous) -> "- $previous\n+ $next" }
        .ifBlank { "unchanged" }
}

private suspend fun readEditorOrDisk(file: Path): String? {
    val virtual = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file.toFile())
    val document = virtual?.let { found ->
        readAction { FileDocumentManager.getInstance().getDocument(found)?.text }
    }
    return document ?: runCatching { file.readText() }.getOrNull()
}
