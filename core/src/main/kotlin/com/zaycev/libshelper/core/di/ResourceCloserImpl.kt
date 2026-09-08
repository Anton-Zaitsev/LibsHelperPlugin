package com.zaycev.libshelper.core.di

import com.zaycev.libshelper.core.network.LibsHelperHttpClients
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class ResourceCloserImpl(
    private val clients: LibsHelperHttpClients,
) : ResourceCloser {
    override fun close() = clients.close()
}
