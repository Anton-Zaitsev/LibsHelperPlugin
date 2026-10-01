package com.zaycev.libshelper.core.analysis

import com.zaycev.libshelper.core.analytics.AnalyticsComputer
import com.zaycev.libshelper.core.analytics.ProjectAnalytics
import com.zaycev.libshelper.core.cache.InMemoryMetadataCache
import com.zaycev.libshelper.core.concurrency.standardDispatcherProvider
import com.zaycev.libshelper.core.log.NoOpLibsHelperLogger
import com.zaycev.libshelper.core.settings.PlatformFactsSource
import com.zaycev.libshelper.core.settings.emptyFacts
import com.zaycev.libshelper.core.model.HttpProxySettings
import com.zaycev.libshelper.core.network.HttpGetResult
import com.zaycev.libshelper.core.network.MetadataGateway
import com.zaycev.libshelper.core.auth.RepositoryAuth
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisOrchestratorTest {
    @Test
    fun analyzesAProjectAndReusesTheReport() = runTest {
        val root = Files.createTempDirectory("libshelper-analysis")
        try {
            root.resolve("settings.gradle.kts").writeText("rootProject.name = \"demo\"\n")
            root.resolve("build.gradle.kts").writeText(
                """
                dependencies {
                    implementation("com.squareup.okhttp3:okhttp:4.12.0")
                    implementation(files("libs/local.jar"))
                }
                """.trimIndent(),
            )
            val xml = requireNotNull(javaClass.getResource("/fixtures/okhttp-metadata.xml")).readText()
            val gateway = object : MetadataGateway {
                override suspend fun get(
                    url: String,
                    httpProxy: HttpProxySettings?,
                    credentials: RepositoryAuth?,
                    allowAuthPrompt: Boolean,
                ): HttpGetResult = HttpGetResult.Success(xml, 200, 3)
            }
            val runner = AnalysisOrchestrator(
                gateway = gateway,
                cache = InMemoryMetadataCache(),
                dispatchers = standardDispatcherProvider(),
                logger = NoOpLibsHelperLogger(),
                analytics = AnalyticsComputer { ProjectAnalytics.Empty },
                platformFacts = PlatformFactsSource { emptyFacts() },
            )
            val plans = mutableListOf<String>()
            val progress = mutableListOf<Int>()
            val report = runner.analyze(root, forceRefresh = false, onPlan = { plans += it.moduleCount.toString() }, onProgress = {
                progress += it.completed
            })
            assertTrue(report.libraries.isNotEmpty())
            assertTrue(plans.isNotEmpty())
            assertTrue(progress.isNotEmpty())
            val again = runner.analyze(root, forceRefresh = false, onPlan = {}, onProgress = {})
            assertTrue(again.servedFromCache)
            runner.invalidate()
            val fresh = runner.analyze(root, forceRefresh = true, onPlan = {}, onProgress = {})
            assertEquals(false, fresh.servedFromCache)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
