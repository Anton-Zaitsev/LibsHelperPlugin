package com.zaycev.libshelper.core.auth

interface RepositoryAuthenticator {
    fun stored(host: String): RepositoryAuth?

    fun profiles(): List<RepositoryAuthProfile>

    fun save(host: String, auth: RepositoryAuth)

    fun remove(host: String)

    suspend fun request(
        host: String,
        reason: String,
        previous: RepositoryAuth? = null,
    ): RepositoryAuth?
}
