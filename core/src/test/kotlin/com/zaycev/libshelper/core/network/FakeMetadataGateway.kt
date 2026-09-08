package com.zaycev.libshelper.core.network

import com.zaycev.libshelper.core.auth.RepositoryAuth
import com.zaycev.libshelper.core.model.HttpProxySettings

class FakeMetadataGateway(
    private val responses: Map<String, HttpGetResult>,
) : MetadataGateway {
    val requestedUrls = mutableListOf<String>()

    override suspend fun get(
        url: String,
        httpProxy: HttpProxySettings?,
        credentials: RepositoryAuth?,
        allowAuthPrompt: Boolean,
    ): HttpGetResult {
        requestedUrls += url
        responses[url]?.let { return it }
        return HttpGetResult.Failure(
            HttpFailure.Unreachable(url, "no fixture for $url"),
            durationMs = 0,
        )
    }
}
