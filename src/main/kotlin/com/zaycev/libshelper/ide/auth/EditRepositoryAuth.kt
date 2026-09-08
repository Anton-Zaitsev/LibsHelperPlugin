package com.zaycev.libshelper.ide.auth

import com.intellij.openapi.project.Project
import com.zaycev.libshelper.core.proxy.hostOf
import com.zaycev.libshelper.ide.i18n.msg

internal fun editRepositoryAuth(project: Project, repositoryUrl: String) {
    val host = hostOf(repositoryUrl) ?: repositoryUrl
    val authenticator = PasswordSafeAuthenticator(project)
    val dialog = RepositoryAuthDialog(
        project = project,
        initialHost = host,
        reason = msg("settings.auth.edit"),
        initial = authenticator.stored(host),
    )
    if (!dialog.showAndGet()) return
    val auth = dialog.result() ?: return
    authenticator.save(dialog.host().ifBlank { host }, auth)
}
