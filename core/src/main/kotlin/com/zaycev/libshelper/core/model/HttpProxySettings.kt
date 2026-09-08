package com.zaycev.libshelper.core.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class HttpProxySettings(
    val host: String,
    val port: Int,
    val nonProxyHosts: ImmutableList<String> = persistentListOf(),
)
