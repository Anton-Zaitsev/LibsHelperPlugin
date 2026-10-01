package com.zaycev.libshelper.core.network

import com.zaycev.libshelper.core.auth.RepositoryAuth
import com.zaycev.libshelper.core.model.HttpProxySettings

interface MetadataGateway {
    suspend fun get(
        url: String,
        httpProxy: HttpProxySettings?,
        credentials: RepositoryAuth? = null,
        allowAuthPrompt: Boolean = false,
    ): HttpGetResult

    fun resetAuthPrompts() {}
}
