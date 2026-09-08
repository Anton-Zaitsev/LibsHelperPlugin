package com.zaycev.libshelper.core.metadata

import com.zaycev.libshelper.core.model.Coordinates
import com.zaycev.libshelper.core.model.DeclaredRepository
import com.zaycev.libshelper.core.model.RepositoryScope
import com.zaycev.libshelper.core.model.RepositoryType

fun projectRepositoriesFor(
    coordinates: Coordinates,
    isPlugin: Boolean,
    repositories: List<DeclaredRepository>,
): List<DeclaredRepository> {
    val wanted = if (isPlugin) RepositoryScope.Plugin else RepositoryScope.Dependency
    val scoped = repositories.filter { it.scope == wanted }
        .ifEmpty { repositories.filter { it.scope == RepositoryScope.Dependency } }
    val usable = scoped.filter {
        it.type != RepositoryType.FlatDir && it.type != RepositoryType.MavenLocal
    }
    val exclusiveHits = usable.filter { it.exclusive && it.servesGroup(coordinates.group) }
    if (exclusiveHits.isNotEmpty()) return exclusiveHits.distinctBy { it.url }
    return usable.filter { !it.exclusive && it.servesGroup(coordinates.group) }
}
