package com.zaycev.libshelper.core.network

object NetworkTimeouts {
    const val CONNECTION_MS: Long = 5_000
    const val REQUEST_MS: Long = 8_000
    const val SOCKET_MS: Long = 8_000
    const val PARALLEL_LIMIT: Int = 8
    const val MILLIS_PER_SECOND: Long = 1_000
    const val NANOS_PER_MILLI: Long = 1_000_000
}
