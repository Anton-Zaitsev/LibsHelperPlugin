package com.zaycev.libshelper.core.auth

data class RepositoryAuthProfile(
    val host: String,
    val scheme: RepositoryAuthScheme,
    val username: String = "",
    val headerName: String = "",
    val hasSecret: Boolean = false,
)
