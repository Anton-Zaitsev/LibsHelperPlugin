package com.zaycev.libshelper.core.model

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeclaredRepositoryServesTest {
    @Test
    fun includeAndExcludeGroups() {
        val repo = DeclaredRepository(
            name = "nexus",
            url = "https://nexus.example/maven/",
            type = RepositoryType.Maven,
            kind = RepositoryKind.ProxyMirror,
            scope = RepositoryScope.Dependency,
            includeGroups = persistentSetOf("com.example"),
            excludeGroups = persistentSetOf("com.example.internal"),
            includeGroupRegex = persistentListOf("com\\.other\\..*"),
        )
        assertTrue(repo.serves("com.example", "lib"))
        assertFalse(repo.serves("com.example.internal", "lib"))
        assertTrue(repo.serves("com.other.app", "lib"))
        assertFalse(repo.serves("org.foreign", "lib"))
    }

    @Test
    fun invalidRegexDoesNotMatch() {
        val repo = DeclaredRepository(
            name = "nexus",
            url = "https://nexus.example/maven/",
            type = RepositoryType.Maven,
            kind = RepositoryKind.Private,
            scope = RepositoryScope.Dependency,
            includeGroupRegex = persistentListOf("("),
        )
        assertFalse(repo.serves("com.example", "lib"))
    }
}
