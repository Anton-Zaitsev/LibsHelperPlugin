package com.zaycev.libshelper.ide.apply

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.zaycev.libshelper.ide.project.primaryGradleRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.zaycev.libshelper.core.inventory.VersionPatchTarget
import com.zaycev.libshelper.core.inventory.applyVersionPatch
import com.zaycev.libshelper.core.inventory.isSafeVersionToken
import com.zaycev.libshelper.core.inventory.safeProjectFile
import com.zaycev.libshelper.core.inventory.versionPatchTarget
import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.ide.i18n.msg

@Suppress("InjectDispatcher")
suspend fun applyLibraryVersion(
    project: Project,
    dependency: DeclaredDependency,
    newVersion: String,
): Boolean {
    if (!isSafeVersionToken(newVersion)) {
        notify(project, msg("action.apply.failed"), NotificationType.WARNING)
        return false
    }
    val target = versionPatchTarget(dependency)
    if (target is VersionPatchTarget.Unsupported) {
        notify(project, msg("action.apply.unsupported"), NotificationType.WARNING)
        return false
    }
    val relative = when (target) {
        is VersionPatchTarget.CatalogVersionRef -> target.catalogPath
        is VersionPatchTarget.CatalogInline -> target.catalogPath
        is VersionPatchTarget.GradleLiteral -> target.scriptPath
        VersionPatchTarget.Unsupported -> return false
    }
    val root = primaryGradleRoot(project)?.toString() ?: return fail(project)
    val file = safeProjectFile(root, relative) ?: return fail(project)
    val virtual = withContext(Dispatchers.IO) {
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file.toFile())
    } ?: return fail(project)
    val document = readAction { FileDocumentManager.getInstance().getDocument(virtual) } ?: return fail(project)
    var applied = false
    writeCommandAction(project, msg("action.apply")) {
        val current = document.text
        val patched = applyVersionPatch(current, target, newVersion) ?: return@writeCommandAction
        if (patched != current) {
            replaceChangedRange(document, current, patched)
            FileDocumentManager.getInstance().saveDocument(document)
        }
        applied = true
    }
    if (!applied) return fail(project)
    val from = dependency.requestedVersion ?: dependency.coordinates.artifact
    notify(project, msg("action.apply.done", from, newVersion), NotificationType.INFORMATION)
    return true
}

fun canApplyLibraryVersion(dependency: DeclaredDependency): Boolean =
    versionPatchTarget(dependency) !is VersionPatchTarget.Unsupported

private fun replaceChangedRange(document: com.intellij.openapi.editor.Document, current: String, patched: String) {
    var prefix = 0
    val shared = minOf(current.length, patched.length)
    while (prefix < shared && current[prefix] == patched[prefix]) prefix++
    var suffix = 0
    while (
        suffix < current.length - prefix &&
        suffix < patched.length - prefix &&
        current[current.length - 1 - suffix] == patched[patched.length - 1 - suffix]
    ) {
        suffix++
    }
    document.replaceString(prefix, current.length - suffix, patched.substring(prefix, patched.length - suffix))
}

private fun fail(project: Project): Boolean {
    notify(project, msg("action.apply.failed"), NotificationType.WARNING)
    return false
}

private fun notify(project: Project, message: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("LibsHelper")
        .createNotification(msg("app.title"), message, type)
        .notify(project)
}
