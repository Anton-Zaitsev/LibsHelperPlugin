package com.zaycev.libshelper.core.di

import com.zaycev.libshelper.core.analysis.AnalysisRunner
import com.zaycev.libshelper.core.analytics.AnalyticsComputer
import com.zaycev.libshelper.core.auth.RepositoryAuthenticator
import com.zaycev.libshelper.core.log.LibsHelperLogger
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory
import java.nio.file.Path

@DependencyGraph(AppScope::class)
interface CoreGraph : AutoCloseable {
    val analysis: AnalysisRunner
    val logger: LibsHelperLogger
    val resourceCloser: ResourceCloser

    override fun close() {
        resourceCloser.close()
    }

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides logger: LibsHelperLogger,
            @Provides authenticator: RepositoryAuthenticator,
            @Provides cacheDirectory: Path,
            @Provides analytics: AnalyticsComputer,
        ): CoreGraph
    }
}

fun createCoreGraph(
    logger: LibsHelperLogger,
    authenticator: RepositoryAuthenticator,
    cacheDirectory: Path,
    analytics: AnalyticsComputer,
): CoreGraph {
    val graph = createGraphFactory<CoreGraph.Factory>().create(
        logger = logger,
        authenticator = authenticator,
        cacheDirectory = cacheDirectory,
        analytics = analytics,
    )
    graph.resourceCloser
    return graph
}
