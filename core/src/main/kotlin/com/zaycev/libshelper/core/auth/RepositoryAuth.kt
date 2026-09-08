package com.zaycev.libshelper.core.auth

data class RepositoryAuth(
    val scheme: RepositoryAuthScheme,
    val username: String = "",
    val secret: String = "",
    val headerName: String = "",
) {
    val isBlank: Boolean
        get() = when (scheme) {
            RepositoryAuthScheme.None -> true
            RepositoryAuthScheme.Basic -> username.isBlank() || secret.isBlank()
            RepositoryAuthScheme.Bearer -> secret.isBlank()
            RepositoryAuthScheme.Header -> headerName.isBlank() || secret.isBlank()
        }
}
