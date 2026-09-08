package com.zaycev.libshelper.core.auth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class PreferExplicitAuthenticatorTest {
    @Test
    fun prefersExplicitLoginAndSkipsFallback() {
        val explicitAuth = RepositoryAuth(RepositoryAuthScheme.Basic, "user", "pass")
        val fallbackAuth = RepositoryAuth(RepositoryAuthScheme.Bearer, secret = "git-token")
        var fallbackCalls = 0
        val authenticator = PreferExplicitAuthenticator(
            explicit = FakeAuthenticator(explicitAuth),
            silentFallback = {
                fallbackCalls += 1
                fallbackAuth
            },
        )
        assertSame(explicitAuth, authenticator.stored("nexus.company.local"))
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun usesSilentFallbackWhenNothingSaved() {
        val fallbackAuth = RepositoryAuth(RepositoryAuthScheme.Bearer, secret = "git-token")
        val authenticator = PreferExplicitAuthenticator(
            explicit = FakeAuthenticator(null),
            silentFallback = { fallbackAuth },
        )
        assertSame(fallbackAuth, authenticator.stored("git.company.local"))
    }

    @Test
    fun blankHostNeverTouchesFallback() {
        var fallbackCalls = 0
        val authenticator = PreferExplicitAuthenticator(
            explicit = FakeAuthenticator(null),
            silentFallback = {
                fallbackCalls += 1
                RepositoryAuth(RepositoryAuthScheme.Bearer, secret = "nope")
            },
        )
        assertNull(authenticator.stored(" "))
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun delegatesPromptToExplicitStore() = runTest {
        val explicit = FakeAuthenticator(null)
        val authenticator = PreferExplicitAuthenticator(explicit) { null }
        val prompted = RepositoryAuth(RepositoryAuthScheme.Basic, "u", "p")
        explicit.nextRequest = prompted
        val result = authenticator.request("host", "reason", null)
        assertSame(prompted, result)
        assertEquals(1, explicit.requestCount)
    }
}

private class FakeAuthenticator(
    private var storedAuth: RepositoryAuth?,
) : RepositoryAuthenticator {
    var nextRequest: RepositoryAuth? = null
    var requestCount: Int = 0

    override fun stored(host: String): RepositoryAuth? = storedAuth

    override fun profiles(): List<RepositoryAuthProfile> = emptyList()

    override fun save(host: String, auth: RepositoryAuth) {
        storedAuth = auth
    }

    override fun remove(host: String) {
        storedAuth = null
    }

    override suspend fun request(
        host: String,
        reason: String,
        previous: RepositoryAuth?,
    ): RepositoryAuth? {
        requestCount += 1
        return nextRequest
    }
}
