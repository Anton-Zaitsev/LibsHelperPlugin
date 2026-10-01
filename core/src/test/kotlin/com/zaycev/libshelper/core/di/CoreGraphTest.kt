package com.zaycev.libshelper.core.di

import com.zaycev.libshelper.core.analytics.AnalyticsComputer
import com.zaycev.libshelper.core.analytics.ProjectAnalytics
import com.zaycev.libshelper.core.auth.NoOpRepositoryAuthenticator
import com.zaycev.libshelper.core.concurrency.DefaultDispatcherProvider
import com.zaycev.libshelper.core.links.libraryLinksOf
import com.zaycev.libshelper.core.log.ActionTrail
import com.zaycev.libshelper.core.log.DefaultDiagnosticFormatter
import com.zaycev.libshelper.core.log.DiagnosticReport
import com.zaycev.libshelper.core.log.NoOpLibsHelperLogger
import com.zaycev.libshelper.core.model.Coordinates
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

class CoreGraphTest {
    @Test
    fun graphAnalyzesALocalProjectAndCloses() = runTest {
        val root = Files.createTempDirectory("libshelper-graph")
        val cache = Files.createTempDirectory("libshelper-graph-cache")
        try {
            root.resolve("settings.gradle.kts").writeText("rootProject.name = \"demo\"\n")
            root.resolve("build.gradle.kts").writeText(
                """
                dependencies {
                    implementation(files("libs/local.jar"))
                }
                """.trimIndent(),
            )
            val graph = createCoreGraph(
                NoOpLibsHelperLogger(),
                NoOpRepositoryAuthenticator(),
                cache,
                AnalyticsComputer { ProjectAnalytics.Empty },
            )
            val report = graph.analysis.analyze(root, forceRefresh = true)
            assertTrue(report.libraries.isEmpty() || report.libraries.all { it.advice.dependency.isLocalArtifact })
            graph.close()
            val dispatchers = DefaultDispatcherProvider()
            assertTrue(dispatchers.io.toString().isNotEmpty())
            assertTrue(dispatchers.default.toString().isNotEmpty())
            val links = libraryLinksOf(Coordinates("io.github.example", "demo"))
            assertTrue(links.github.orEmpty().contains("github.com"))
            assertTrue(libraryLinksOf(Coordinates("androidx.core", "core")).googleMaven != null)
            val trail = ActionTrail(capacity = 1)
            trail.record("ui", "open", "a")
            trail.record("ui", "open", "b")
            assertTrue(trail.snapshot().single().detail == "b")
            val formatter = DefaultDiagnosticFormatter()
            val url = formatter.issueUrl(
                "boom",
                formatter.format(DiagnosticReport("1.1.0", null, "test", "summary", trail.snapshot(), "failure")),
                "example/libshelper",
            )
            assertTrue(url.contains("github.com") && url.contains("labels=bug"))
        } finally {
            root.toFile().deleteRecursively()
            cache.toFile().deleteRecursively()
        }
    }
}
