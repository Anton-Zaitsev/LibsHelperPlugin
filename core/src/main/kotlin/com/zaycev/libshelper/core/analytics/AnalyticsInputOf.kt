package com.zaycev.libshelper.core.analytics

import com.zaycev.libshelper.core.model.DependencySource
import com.zaycev.libshelper.core.model.ProjectReport

fun ProjectReport.analyticsInput(): AnalyticsInput {
    val adviceByKey = libraries.associateBy { it.advice.dependency.coordinates.key }
    return AnalyticsInput(
        moduleCount = inventory.modules.size.coerceAtLeast(scanPlan.moduleCount),
        dependencies = inventory.dependencies.map { declared ->
            val item = adviceByKey[declared.coordinates.key]
            val advice = item?.advice
            AnalyticsDependency(
                key = declared.coordinates.key,
                group = declared.coordinates.group,
                artifact = declared.coordinates.artifact,
                module = declared.module,
                configuration = declared.configuration,
                catalogAlias = declared.catalogAlias,
                versionRef = declared.versionRef,
                requestedVersion = declared.requestedVersion,
                isPlugin = declared.isPlugin,
                isLocal = declared.isLocalArtifact,
                isBom = declared.isBom,
                fromCatalog = declared.source == DependencySource.Toml,
                current = advice?.current?.raw ?: declared.requestedVersion,
                recommended = advice?.preferredStable?.version?.raw,
                outdated = advice?.isOutdated == true,
                channel = advice?.currentChannel?.name,
                github = item?.links?.github,
                mavenCentral = item?.links?.mavenCentral,
            )
        },
    )
}
