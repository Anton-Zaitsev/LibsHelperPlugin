package com.zaycev.libshelper.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepositoryAuthRequestHeadersTest {
    @Test
    fun basicEncodesAuthorization() {
        val headers = RepositoryAuth(
            RepositoryAuthScheme.Basic,
            username = "ci",
            secret = "token",
        ).requestHeaders()
        assertEquals("Basic Y2k6dG9rZW4=", headers["Authorization"])
    }

    @Test
    fun bearerUsesAuthorizationHeader() {
        val headers = RepositoryAuth(
            RepositoryAuthScheme.Bearer,
            secret = "ghp_123",
        ).requestHeaders()
        assertEquals("Bearer ghp_123", headers["Authorization"])
    }

    @Test
    fun customHeaderForArtifactory() {
        val headers = RepositoryAuth(
            RepositoryAuthScheme.Header,
            secret = "ak-1",
            headerName = "X-JFrog-Art-Api",
        ).requestHeaders()
        assertEquals("ak-1", headers["X-JFrog-Art-Api"])
        assertTrue("Authorization" !in headers)
    }
}
