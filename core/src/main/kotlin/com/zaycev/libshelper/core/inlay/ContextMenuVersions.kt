package com.zaycev.libshelper.core.inlay

import com.zaycev.libshelper.core.model.UpdateAdvice
import com.zaycev.libshelper.core.model.VersionCandidate
import com.zaycev.libshelper.core.model.VersionChannel
import com.zaycev.libshelper.core.versioning.MavenVersion

private const val NEWER_STABLE_CHOICES = 3

fun contextMenuVersions(advice: UpdateAdvice): List<VersionCandidate> {
    val current = advice.current
    return newer(advice, VersionChannel.Stable, NEWER_STABLE_CHOICES, current) +
        newer(advice, VersionChannel.ReleaseCandidate, current = current) +
        newer(advice, VersionChannel.Beta, current = current)
}

private fun newer(
    advice: UpdateAdvice,
    channel: VersionChannel,
    limit: Int = 1,
    current: MavenVersion?,
): List<VersionCandidate> =
    advice.candidates
        .filter { it.channel == channel && (current == null || it.version > current) }
        .distinctBy { it.version.raw }
        .sortedByDescending { it.version }
        .take(limit)
