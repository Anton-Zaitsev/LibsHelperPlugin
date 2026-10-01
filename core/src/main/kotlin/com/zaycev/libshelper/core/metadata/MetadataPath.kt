package com.zaycev.libshelper.core.metadata

import java.net.URLEncoder

private val SEGMENT = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*""")

fun metadataPath(group: String, artifact: String): String? {
    val groups = group.split('.')
    if (groups.isEmpty() || groups.any { !isSafeSegment(it) }) return null
    if (!isSafeSegment(artifact)) return null
    return groups.joinToString("/") { encodeSegment(it) } +
        "/${encodeSegment(artifact)}/maven-metadata.xml"
}

private fun isSafeSegment(value: String): Boolean =
    value.isNotEmpty() && value != "." && value != ".." && SEGMENT.matches(value)

private fun encodeSegment(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
