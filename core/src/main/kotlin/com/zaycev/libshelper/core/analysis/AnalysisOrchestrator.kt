package com.zaycev.libshelper.core.analysis

import com.zaycev.libshelper.core.analytics.AnalyticsComputer
import com.zaycev.libshelper.core.analytics.analyticsInput
import com.zaycev.libshelper.core.graph.layoutModuleMap
import com.zaycev.libshelper.core.cache.MetadataCache
import com.zaycev.libshelper.core.concurrency.DispatcherProvider
import com.zaycev.libshelper.core.di.AppScope
import com.zaycev.libshelper.core.inventory.LOCAL_FILE_GROUP
import com.zaycev.libshelper.core.inventory.mergeUniqueDependencies
import com.zaycev.libshelper.core.inventory.projectTree
import com.zaycev.libshelper.core.inventory.scanProject
import com.zaycev.libshelper.core.links.libraryLinksOf
import com.zaycev.libshelper.core.log.LibsHelperLogger
import com.zaycev.libshelper.core.metadata.lookupMetadata
import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.LibraryAdvice
import com.zaycev.libshelper.core.model.MetadataOrigin
import com.zaycev.libshelper.core.model.MetadataOriginKind
import com.zaycev.libshelper.core.model.ProjectInventory
import com.zaycev.libshelper.core.model.ProjectReport
import com.zaycev.libshelper.core.model.ScanPlan
import com.zaycev.libshelper.core.network.MetadataGateway
import com.zaycev.libshelper.core.network.NetworkTimeouts
import com.zaycev.libshelper.core.progress.AnalysisProgress
import com.zaycev.libshelper.core.proxy.buildScanPlan
import com.zaycev.libshelper.core.recommend.alignSharedVersionRefs
import com.zaycev.libshelper.core.recommend.buildAdvice
import com.zaycev.libshelper.core.settings.PlatformFactsSource
import com.zaycev.libshelper.core.settings.VersionSourceText
import com.zaycev.libshelper.core.settings.adviseProjectSettings
import com.zaycev.libshelper.core.settings.indexVersionUsages
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.async
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class AnalysisOrchestrator(
    private val gateway: MetadataGateway,
    private val cache: MetadataCache,
    private val dispatchers: DispatcherProvider,
    private val logger: LibsHelperLogger,
    private val analytics: AnalyticsComputer,
    private val platformFacts: PlatformFactsSource,
) : AnalysisRunner {
    private val stored = AtomicReference<StoredReport?>(null)

    override fun invalidate() {
        cache.clear()
        gateway.resetAuthPrompts()
        stored.set(null)
        logger.info("Кеш анализа очищен")
    }

    override fun rememberReport(report: ProjectReport) {
        val fingerprint = report.fingerprint ?: return
        stored.set(StoredReport(fingerprint, report))
    }

    override suspend fun analyze(
        root: Path,
        forceRefresh: Boolean,
        onPlan: (ScanPlan) -> Unit,
        onProgress: (AnalysisProgress) -> Unit,
    ): ProjectReport = withContext(dispatchers.io) {
        if (forceRefresh) invalidate()
        val normalized = root.toAbsolutePath().normalize()
        val tree = projectTree(normalized)
        val fingerprint = tree.fingerprint
        val cached = stored.get()
        if (!forceRefresh && cached != null && cached.fingerprint == fingerprint) {
            logger.info("Проект не менялся — отдаём кешированный отчёт")
            onPlan(cached.report.scanPlan)
            onProgress(
                progress(
                    currentCoordinates = null,
                    currentHost = null,
                    completed = cached.report.libraries.size,
                    total = cached.report.libraries.size,
                    lastOutcome = "project_cache",
                    lastDurationMs = 0,
                    usingCache = true,
                ),
            )
            return@withContext cached.report.copy(servedFromCache = true)
        }

        val inventory = scanProject(normalized, tree)
        val plan = buildScanPlan(inventory)
        onPlan(plan)
        val unique = mergeUniqueDependencies(inventory.dependencies)
            .sortedBy { if (it.referenced) 0 else 1 }
        val total = unique.size
        val completed = AtomicInteger(0)
        logger.info("Сканирование: ${inventory.modules.size} модулей, $total библиотек")
        onProgress(progress(null, null, 0, total, "inventory_ready", 0, false))

        val semaphore = Semaphore(NetworkTimeouts.PARALLEL_LIMIT)
        val progressGate = Any()
        val libraries = supervisorScope {
            unique.map { dependency ->
                async(dispatchers.io) {
                    semaphore.withPermit {
                        lookupOne(dependency, inventory, forceRefresh, completed, total) { update ->
                            synchronized(progressGate) { onProgress(update) }
                        }
                    }
                }
            }.awaitAll()
        }.let { found -> alignSharedVersionRefs(found, inventory) }
            .sortedWith(
                compareByDescending<LibraryAdvice> { it.advice.isOutdated }
                    .thenBy { it.advice.dependency.coordinates.key },
            )

        val draft = ProjectReport(
            inventory = inventory,
            libraries = libraries.toPersistentList(),
            metadataFromProxyOnly = libraries.any {
                it.advice.preferredStable?.origin?.kind == MetadataOriginKind.ProjectProxy ||
                    it.advice.latestRc?.origin?.kind == MetadataOriginKind.ProjectProxy
            },
            scanPlan = plan,
            fingerprint = fingerprint,
            servedFromCache = false,
        )
        val report = coroutineScope {
            val flush = async { cache.flush() }
            val analyticsJob = async(dispatchers.default) { analytics.compute(draft.analyticsInput()) }
            val moduleMap = async(dispatchers.default) { layoutModuleMap(inventory.moduleGraph) }
            val usages = indexVersionUsages(
                inventory.versionSources.entries.map { entry -> VersionSourceText(entry.key, entry.value) },
                inventory.catalogVersions.keys,
            )
            val facts = platformFacts.load(inventory.httpProxy)
            val settings = adviseProjectSettings(usages, inventory.catalogVersions, facts)
            flush.await()
            draft.copy(
                analytics = analyticsJob.await(),
                moduleMap = moduleMap.await(),
                buildSettings = settings.toPersistentList(),
                versionUsages = usages.toPersistentList(),
            )
        }
        stored.set(StoredReport(fingerprint, report))
        report
    }

    private suspend fun lookupOne(
        dependency: DeclaredDependency,
        inventory: ProjectInventory,
        forceRefresh: Boolean,
        completed: AtomicInteger,
        total: Int,
        onProgress: (AnalysisProgress) -> Unit,
    ): LibraryAdvice {
        val key = dependency.coordinates.key
        return try {
            onProgress(progress(key, null, completed.get(), total, "requesting", null, false))
            if (dependency.isLocalArtifact && dependency.coordinates.group == LOCAL_FILE_GROUP) {
                val done = completed.incrementAndGet()
                val kind = dependency.localKind?.name?.uppercase() ?: "JAR"
                val error = "local:$kind:${dependency.localFileName ?: key}"
                onProgress(progress(key, null, done, total, "local_file", 0, false))
                return LibraryAdvice(
                    advice = buildAdvice(
                        dependency = dependency,
                        versions = emptyList(),
                        origin = MetadataOrigin(MetadataOriginKind.OfficialDirect, ""),
                        inventory = inventory,
                    ),
                    repositories = inventory.repositories,
                    links = libraryLinksOf(dependency.coordinates),
                    lookupError = error,
                )
            }
            val lookup = lookupMetadata(
                coordinates = dependency.coordinates,
                isPlugin = dependency.isPlugin,
                projectRepositories = inventory.repositories,
                httpProxy = inventory.httpProxy,
                gateway = gateway,
                cache = cache,
                forceRefresh = forceRefresh,
                onAttempt = { url, fromCache, outcome, durationMs ->
                    onProgress(
                        progress(
                            currentCoordinates = key,
                            currentHost = url.substringAfter("://").substringBefore('/'),
                            completed = completed.get(),
                            total = total,
                            lastOutcome = outcome,
                            lastDurationMs = durationMs,
                            usingCache = fromCache,
                        ),
                    )
                },
            )
            val done = completed.incrementAndGet()
            val origin = lookup.resolved?.origin ?: MetadataOrigin(MetadataOriginKind.OfficialDirect, "")
            val versions = lookup.resolved?.metadata?.versions.orEmpty()
            val advice = buildAdvice(
                dependency = dependency,
                versions = versions,
                origin = origin,
                inventory = inventory,
                proxyMissingOfficial = lookup.resolved?.usedProxyFallback == true,
            )
            val error = if (lookup.resolved == null) {
                listOfNotNull(lookup.officialError, lookup.proxyError).joinToString(" · ")
                    .ifBlank { "catalog_silent" }
            } else {
                null
            }
            onProgress(
                progress(
                    currentCoordinates = key,
                    currentHost = lookup.lastUrl?.substringAfter("://")?.substringBefore('/'),
                    completed = done,
                    total = total,
                    lastOutcome = error ?: "done",
                    lastDurationMs = lookup.resolved?.durationMs,
                    usingCache = lookup.resolved?.fromCache == true,
                ),
            )
            LibraryAdvice(
                advice = advice,
                repositories = (inventory.repositories + lookup.repositoryStatuses).distinctBy { it.url }.toPersistentList(),
                links = libraryLinksOf(dependency.coordinates),
                lookupDurationMs = lookup.resolved?.durationMs,
                fromCache = lookup.resolved?.fromCache == true,
                lookupError = error,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val done = completed.incrementAndGet()
            logger.warn("Не удалось разобрать $key", error)
            onProgress(progress(key, null, done, total, error.message ?: "error", null, false))
            LibraryAdvice(
                advice = buildAdvice(
                    dependency = dependency,
                    versions = emptyList(),
                    origin = MetadataOrigin(MetadataOriginKind.OfficialDirect, ""),
                    inventory = inventory,
                    proxyMissingOfficial = false,
                ),
                repositories = inventory.repositories,
                links = libraryLinksOf(dependency.coordinates),
                lookupError = error.message ?: "lookup_unknown",
            )
        }
    }

    private fun progress(
        currentCoordinates: String?,
        currentHost: String?,
        completed: Int,
        total: Int,
        lastOutcome: String?,
        lastDurationMs: Long?,
        usingCache: Boolean,
    ) = AnalysisProgress(
        currentCoordinates = currentCoordinates,
        currentHost = currentHost,
        completed = completed,
        total = total,
        lastOutcome = lastOutcome,
        lastDurationMs = lastDurationMs,
        usingCache = usingCache,
    )
}

private data class StoredReport(
    val fingerprint: String,
    val report: ProjectReport,
)
