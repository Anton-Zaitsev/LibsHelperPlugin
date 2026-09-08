package com.zaycev.libshelper.core.versioning

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/**
 * Maven-like version: numeric tokens and qualifiers, never [String.compareTo].
 */
data class MavenVersion(val raw: String) : Comparable<MavenVersion> {
    val tokens: ImmutableList<VersionToken> = tokenize(raw)

    override fun compareTo(other: MavenVersion): Int = compareTokenLists(tokens, other.tokens)

    override fun toString(): String = raw

    companion object {
        fun parse(raw: String): MavenVersion = MavenVersion(raw.trim())
    }
}

sealed interface VersionToken : Comparable<VersionToken> {
    data class Number(val value: Long) : VersionToken {
        override fun compareTo(other: VersionToken): Int = when (other) {
            is Number -> value.compareTo(other.value)
            is Qualifier -> if (other.isRelease) -1 else 1
        }
    }

    data class Qualifier(val normalized: String) : VersionToken {
        val rank: Int get() = qualifierRank(normalized)
        val isRelease: Boolean get() = rank >= RELEASE_RANK

        override fun compareTo(other: VersionToken): Int = when (other) {
            is Number -> if (isRelease) 1 else -1
            is Qualifier -> {
                val byRank = rank.compareTo(other.rank)
                if (byRank != 0) byRank else normalized.compareTo(other.normalized)
            }
        }
    }
}

internal const val RELEASE_RANK = 50
internal const val SNAPSHOT_RANK = 45
internal const val UNKNOWN_QUALIFIER_RANK = 35

internal fun qualifierRank(name: String): Int = when (name) {
    "alpha", "a" -> 10
    "beta", "b" -> 20
    "milestone", "m" -> 30
    "rc", "cr" -> 40
    "snapshot" -> SNAPSHOT_RANK
    "ga", "final", "release", "sp" -> RELEASE_RANK
    else -> UNKNOWN_QUALIFIER_RANK
}

internal fun tokenize(raw: String): ImmutableList<VersionToken> {
    val cleaned = raw.trim().trimStart('v', 'V')
    if (cleaned.isEmpty()) return persistentListOf()
    val parts = cleaned.split(Regex("[.\\-_+]"))
        .flatMap { splitAlphaNumeric(it) }
        .filter { it.isNotBlank() }
    return parts.map { part ->
        val number = part.toLongOrNull()
        if (number != null) {
            VersionToken.Number(number)
        } else {
            VersionToken.Qualifier(part.lowercase())
        }
    }.toPersistentList()
}

private fun splitAlphaNumeric(value: String): List<String> {
    val out = mutableListOf<String>()
    val buf = StringBuilder()
    var digitMode: Boolean? = null
    for (ch in value) {
        val isDigit = ch.isDigit()
        if (digitMode != null && digitMode != isDigit) {
            out += buf.toString()
            buf.clear()
        }
        digitMode = isDigit
        buf.append(ch)
    }
    if (buf.isNotEmpty()) out += buf.toString()
    return out
}

internal fun compareTokenLists(left: List<VersionToken>, right: List<VersionToken>): Int {
    val max = maxOf(left.size, right.size)
    for (i in 0 until max) {
        val l = left.getOrNull(i) ?: VersionToken.Number(0)
        val r = right.getOrNull(i) ?: VersionToken.Number(0)
        val cmp = l.compareTo(r)
        if (cmp != 0) return cmp
    }
    return 0
}
