package com.zaycev.libshelper.core.auth

class PreferExplicitAuthenticator(
    private val explicit: RepositoryAuthenticator,
    private val silentFallback: (String) -> RepositoryAuth? = { null },
) : RepositoryAuthenticator {
    override fun stored(host: String): RepositoryAuth? {
        if (host.isBlank()) return null
        return explicit.stored(host) ?: silentFallback(host)
    }

    override fun profiles(): List<RepositoryAuthProfile> = explicit.profiles()

    override fun save(host: String, auth: RepositoryAuth) {
        explicit.save(host, auth)
    }

    override fun remove(host: String) {
        explicit.remove(host)
    }

    override suspend fun request(
        host: String,
        reason: String,
        previous: RepositoryAuth?,
    ): RepositoryAuth? = explicit.request(host, reason, previous)
}
