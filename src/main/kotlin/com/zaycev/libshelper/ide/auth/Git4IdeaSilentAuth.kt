package com.zaycev.libshelper.ide.auth

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.project.Project
import com.intellij.util.AuthData
import com.zaycev.libshelper.core.auth.RepositoryAuth
import com.zaycev.libshelper.core.auth.studioGitAuthOf
import com.zaycev.libshelper.core.proxy.hostOf
import git4idea.remote.GitHttpAuthDataProvider
import git4idea.repo.GitRepositoryManager

internal object Git4IdeaSilentAuth {
    fun remoteUrls(project: Project): List<String> = runCatching {
        GitRepositoryManager.getInstance(project)
            .repositories
            .flatMap { repository -> repository.remotes.flatMap { remote -> remote.urls } }
    }.getOrDefault(emptyList())

    fun silentAuth(project: Project, gitUrl: String): RepositoryAuth? {
        fromProviders(project, gitUrl)?.let { return it }
        return fromPasswordSafe(gitUrl)
    }

    private fun fromProviders(project: Project, gitUrl: String): RepositoryAuth? {
        val providers = runCatching { GitHttpAuthDataProvider.EP_NAME.extensionList }.getOrNull().orEmpty()
        for (provider in providers) {
            val data = runCatching { provider.getAuthData(project, gitUrl) }.getOrNull() ?: continue
            val auth = toAuth(data) ?: continue
            return auth
        }
        return null
    }

    private fun fromPasswordSafe(gitUrl: String): RepositoryAuth? {
        val host = hostOf(gitUrl) ?: return null
        val keys = listOf(gitUrl, host).distinct()
        for (key in keys) {
            val stored = runCatching {
                PasswordSafe.instance.get(CredentialAttributes(generateServiceName("Git HTTP", key), key))
            }.getOrNull() ?: continue
            val auth = studioGitAuthOf(stored.userName, stored.getPasswordAsString()) ?: continue
            return auth
        }
        return null
    }

    private fun toAuth(data: AuthData): RepositoryAuth? = studioGitAuthOf(data.login, data.password)
}
