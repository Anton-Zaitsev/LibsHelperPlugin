package com.zaycev.libshelper.core.network

import com.zaycev.libshelper.core.proxy.hostOf

internal fun hostFromUrl(url: String): String = hostOf(url).orEmpty()
