package com.zaycev.libshelper.ide.auth

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.zaycev.libshelper.core.auth.RepositoryAuth
import com.zaycev.libshelper.core.auth.gitAuthCandidateUrls
import java.util.concurrent.ConcurrentHashMap

internal class StudioGitCredentialSource(
    private val project: Project,
) {
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val remotes: List<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching {
            if (!git4IdeaEnabled()) emptyList() else Git4IdeaSilentAuth.remoteUrls(project)
        }.getOrDefault(emptyList())
    }

    fun stored(host: String): RepositoryAuth? {
        if (host.isBlank()) return null
        cache[host]?.let { return it.auth }
        if (ApplicationManager.getApplication().isDispatchThread) return null
        val auth = lookup(host)
        cache[host] = CacheEntry(auth)
        return auth
    }

    private fun lookup(host: String): RepositoryAuth? {
        if (!git4IdeaEnabled()) return null
        return runCatching { findSilent(host) }
            .onFailure { LOG.debug("Studio Git login unavailable for $host: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    private fun findSilent(host: String): RepositoryAuth? {
        val urls = gitAuthCandidateUrls(host, remotes)
        for (url in urls) {
            val auth = Git4IdeaSilentAuth.silentAuth(project, url) ?: continue
            LOG.debug("Reusing Studio Git login for Maven host $host")
            return auth
        }
        return null
    }

    private data class CacheEntry(val auth: RepositoryAuth?)

    private companion object {
        val LOG = Logger.getInstance(StudioGitCredentialSource::class.java)
    }
}
