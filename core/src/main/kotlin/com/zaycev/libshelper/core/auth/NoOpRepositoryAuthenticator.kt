package com.zaycev.libshelper.core.auth

internal class NoOpRepositoryAuthenticator : RepositoryAuthenticator {
    override fun stored(host: String): RepositoryAuth? = null

    override fun profiles(): List<RepositoryAuthProfile> = emptyList()

    override fun save(host: String, auth: RepositoryAuth) = Unit

    override fun remove(host: String) = Unit

    override suspend fun request(
        host: String,
        reason: String,
        previous: RepositoryAuth?,
    ): RepositoryAuth? = null
}
