package com.zaycev.libshelper.core.metadata

import com.zaycev.libshelper.core.model.ConnectStatus
import com.zaycev.libshelper.core.model.DeclaredRepository
import com.zaycev.libshelper.core.model.RepositoryKind
import com.zaycev.libshelper.core.model.RepositoryScope
import com.zaycev.libshelper.core.model.RepositoryType

fun repositoryStatusOf(
    url: String,
    status: ConnectStatus,
    message: String?,
    official: Boolean,
): DeclaredRepository = DeclaredRepository(
    name = url.substringAfter("://").substringBefore('/'),
    url = url.substringBefore("/${url.substringAfter("maven2/").substringBefore("/maven-metadata.xml")}"),
    type = when {
        url.contains("google") -> RepositoryType.Google
        url.contains("plugins.gradle") -> RepositoryType.PluginPortal
        url.contains("jitpack") -> RepositoryType.Maven
        else -> RepositoryType.MavenCentral
    },
    kind = if (official) RepositoryKind.Official else RepositoryKind.ProxyMirror,
    scope = RepositoryScope.Dependency,
    connectStatus = status,
    message = message,
)
