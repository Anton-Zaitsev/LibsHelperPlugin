package com.zaycev.libshelper.ide.apply

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.zaycev.libshelper.core.inventory.VersionPatchTarget
import com.zaycev.libshelper.core.inventory.applyVersionPatch
import com.zaycev.libshelper.core.inventory.isSafeVersionToken
import com.zaycev.libshelper.core.inventory.versionPatchTarget
import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.ide.i18n.msg
import java.nio.file.Path

fun applyLibraryVersion(
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
    val root = project.basePath ?: return fail(project)
    val file = safeProjectFile(root, relative) ?: return fail(project)
    val virtual = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file.toFile()) ?: return fail(project)
    val document = FileDocumentManager.getInstance().getDocument(virtual) ?: return fail(project)
    val patched = applyVersionPatch(document.text, target, newVersion) ?: return fail(project)
    if (patched == document.text) return true
    WriteCommandAction.writeCommandAction(project)
        .withName(msg("action.apply"))
        .run<RuntimeException> {
            document.replaceString(0, document.textLength, patched)
            FileDocumentManager.getInstance().saveDocument(document)
        }
    val from = dependency.requestedVersion ?: dependency.coordinates.artifact
    notify(project, msg("action.apply.done", from, newVersion), NotificationType.INFORMATION)
    return true
}

fun canApplyLibraryVersion(dependency: DeclaredDependency): Boolean =
    versionPatchTarget(dependency) !is VersionPatchTarget.Unsupported

private fun fail(project: Project): Boolean {
    notify(project, msg("action.apply.failed"), NotificationType.WARNING)
    return false
}

private fun safeProjectFile(root: String, relative: String): Path? {
    val base = Path.of(root).toAbsolutePath().normalize()
    val resolved = base.resolve(relative).normalize()
    if (!resolved.startsWith(base)) return null
    return resolved.takeIf { it.toFile().isFile }
}

private fun notify(project: Project, message: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("LibsHelper")
        .createNotification(msg("app.title"), message, type)
        .notify(project)
}
