package com.zaycev.libshelper.core.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

data class DeclaredRepository(
    val name: String,
    val url: String,
    val type: RepositoryType,
    val kind: RepositoryKind,
    val scope: RepositoryScope,
    val connectStatus: ConnectStatus = ConnectStatus.Unknown,
    val message: String? = null,
    val includeGroups: ImmutableSet<String> = persistentSetOf(),
    val exclusive: Boolean = false,
    val includeGroupRegex: ImmutableList<String> = persistentListOf(),
    val includeModules: ImmutableSet<String> = persistentSetOf(),
    val excludeGroups: ImmutableSet<String> = persistentSetOf(),
    val excludeGroupRegex: ImmutableList<String> = persistentListOf(),
    val excludeModules: ImmutableSet<String> = persistentSetOf(),
) {
    fun servesGroup(group: String): Boolean = serves(group, "")

    fun serves(group: String, artifact: String): Boolean {
        val moduleKey = "$group:$artifact"
        if (group in excludeGroups) return false
        if (artifact.isNotEmpty() && moduleKey in excludeModules) return false
        if (excludeGroupRegex.any { groupMatches(group, it) }) return false
        val restricted = includeGroups.isNotEmpty() || includeGroupRegex.isNotEmpty() || includeModules.isNotEmpty()
        if (!restricted) return true
        if (group in includeGroups) return true
        if (artifact.isNotEmpty() && moduleKey in includeModules) return true
        if (includeGroupRegex.any { groupMatches(group, it) }) return true
        if (includeModules.isNotEmpty() && artifact.isEmpty()) return true
        return false
    }
}

private fun groupMatches(group: String, pattern: String): Boolean {
    if (pattern.length !in 1..MAX_PATTERN_LENGTH) return false
    val regex = COMPILED_PATTERNS.compute(pattern) { _, existing ->
        existing ?: runCatching { Regex(pattern) }.getOrNull()
    } ?: return false
    return runCatching { regex.matches(group) }.getOrDefault(false)
}

private const val MAX_PATTERN_LENGTH = 128

private val COMPILED_PATTERNS = java.util.concurrent.ConcurrentHashMap<String, Regex>()
