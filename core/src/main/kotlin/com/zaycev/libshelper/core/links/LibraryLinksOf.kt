package com.zaycev.libshelper.core.links

import com.zaycev.libshelper.core.model.Coordinates
import com.zaycev.libshelper.core.model.LibraryLinks
import java.net.URLEncoder

fun libraryLinksOf(coordinates: Coordinates): LibraryLinks {
    val group = encodeSegment(coordinates.group)
    val artifact = encodeSegment(coordinates.artifact)
    val github = githubUrl(group, artifact)
    val google = if (
        group.startsWith("androidx.") ||
        group.startsWith("com.android.") ||
        group.startsWith("com.google.")
    ) {
        "https://maven.google.com/web/index.html#$group:$artifact"
    } else {
        null
    }
    return LibraryLinks(
        mavenCentral = "https://central.sonatype.com/artifact/$group/$artifact",
        mvnRepository = "https://mvnrepository.com/artifact/$group/$artifact",
        googleMaven = google,
        github = github,
    )
}

private fun encodeSegment(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

private fun githubUrl(group: String, artifact: String): String? {
    val prefix = when {
        group.startsWith("com.github.") -> group.removePrefix("com.github.")
        group.startsWith("io.github.") -> group.removePrefix("io.github.")
        else -> return null
    }
    val user = prefix.substringBefore('.')
    if (user.isBlank()) return null
    return "https://github.com/$user/$artifact"
}
