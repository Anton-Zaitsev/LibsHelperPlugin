package com.zaycev.libshelper.ide.auth

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.zaycev.libshelper.core.auth.RepositoryAuth
import com.zaycev.libshelper.core.auth.RepositoryAuthProfile
import com.zaycev.libshelper.core.auth.RepositoryAuthScheme
import com.zaycev.libshelper.core.auth.RepositoryAuthenticator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class PasswordSafeAuthenticator(
    private val project: Project,
) : RepositoryAuthenticator {
    private val settings = RepoAuthSettings.getInstance(project)

    override fun stored(host: String): RepositoryAuth? = blockingCredentials(project) {
        val secret = PasswordSafe.instance.get(attributes(host))?.getPasswordAsString().orEmpty()
        val profile = settings.profiles().firstOrNull { it.host == host }
        if (profile != null) {
            val scheme = runCatching { RepositoryAuthScheme.valueOf(profile.scheme) }
                .getOrDefault(RepositoryAuthScheme.Basic)
            val auth = RepositoryAuth(
                scheme = scheme,
                username = profile.username,
                secret = secret,
                headerName = profile.headerName,
            )
            auth.takeUnless { it.isBlank }
        } else {
            val stored = PasswordSafe.instance.get(attributes(host))
            val user = stored?.userName.orEmpty()
            if (stored == null || secret.isBlank()) {
                null
            } else {
                RepositoryAuth(RepositoryAuthScheme.Basic, user, secret).takeUnless { it.isBlank }
            }
        }
    }

    override fun profiles(): List<RepositoryAuthProfile> = blockingCredentials(project) {
        val fromSettings = settings.profiles().map { profile ->
            val scheme = runCatching { RepositoryAuthScheme.valueOf(profile.scheme) }
                .getOrDefault(RepositoryAuthScheme.Basic)
            val host = profile.host
            RepositoryAuthProfile(
                host = host,
                scheme = scheme,
                username = profile.username,
                headerName = profile.headerName,
                hasSecret = !PasswordSafe.instance.get(attributes(host))?.getPasswordAsString().isNullOrBlank(),
            )
        }
        fromSettings
    }

    override fun save(host: String, auth: RepositoryAuth) = blockingCredentials(project) {
        if (host.isBlank()) return@blockingCredentials
        settings.upsert(host, auth.scheme, auth.username, auth.headerName)
        if (auth.secret.isBlank()) {
            PasswordSafe.instance.set(attributes(host), null)
        } else {
            PasswordSafe.instance.set(
                attributes(host),
                Credentials(auth.username.ifBlank { host }, auth.secret),
            )
        }
    }

    override fun remove(host: String) = blockingCredentials(project) {
        settings.remove(host)
        PasswordSafe.instance.set(attributes(host), null)
    }

    override suspend fun request(
        host: String,
        reason: String,
        previous: RepositoryAuth?,
    ): RepositoryAuth? {
        val initial = previous ?: stored(host)
        return withContext(Dispatchers.EDT) {
            val dialog = RepositoryAuthDialog(project, host, reason, initial, hostLocked = reason.isNotBlank())
            if (!dialog.showAndGet()) return@withContext null
            val auth = dialog.result() ?: return@withContext null
            val targetHost = dialog.host().ifBlank { host }
            save(targetHost, auth)
            auth
        }
    }

    private fun attributes(host: String) = CredentialAttributes(
        generateServiceName("LibsHelper", host),
        host,
    )
}
