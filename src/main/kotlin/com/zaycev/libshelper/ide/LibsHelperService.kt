package com.zaycev.libshelper.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.zaycev.libshelper.core.auth.PreferExplicitAuthenticator
import com.zaycev.libshelper.core.di.CoreGraph
import com.zaycev.libshelper.core.di.createCoreGraph
import com.zaycev.libshelper.core.inventory.isGradleProject
import com.zaycev.libshelper.core.inventory.projectFingerprint
import com.zaycev.libshelper.core.model.AdvisorErrorKind
import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.ProjectReport
import com.zaycev.libshelper.core.model.ScanPlan
import com.zaycev.libshelper.core.progress.AnalysisProgress
import com.zaycev.libshelper.core.recommend.applyKnownVersion
import com.zaycev.libshelper.ide.apply.applyLibraryVersion
import com.zaycev.libshelper.ide.auth.PasswordSafeAuthenticator
import com.zaycev.libshelper.ide.auth.StudioGitCredentialSource
import com.zaycev.libshelper.ide.i18n.LocaleChangeListener
import com.zaycev.libshelper.ide.inlay.isDependencyHintFile
import com.zaycev.libshelper.ide.inlay.requestDependencyHintsUpdate
import com.zaycev.libshelper.core.concurrency.standardDispatcherProvider
import com.zaycev.libshelper.ide.log.IntelliJLibsHelperLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class LibsHelperService(private val project: Project) : Disposable {
    private val scope = CoroutineScope(SupervisorJob() + standardDispatcherProvider().default)
    private val listeners = CopyOnWriteArrayList<AdvisorListener>()
    private val sessionHolder = AtomicReference<GraphSession?>(null)
    private val publishLock = Any()
    private val pendingWatchRefresh = AtomicBoolean(false)
    private val ignoreWatchUntilMs = AtomicLong(0)
    private val updatesTabRequested = AtomicBoolean(false)
    private var job: Job? = null
    private var watchJob: Job? = null

    @Volatile
    var state: AdvisorUiState = AdvisorUiState.Idle
        private set

    @Volatile
    var lastReport: ProjectReport? = null
        private set

    var selectedKey: String? = null

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (System.currentTimeMillis() < ignoreWatchUntilMs.get()) return
                    if (events.any(::isWatchedGradleEvent)) {
                        scheduleWatchedRefresh()
                    }
                }
            },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            LocaleChangeListener.TOPIC,
            LocaleChangeListener { requestDependencyHintsUpdate(project) },
        )
    }

    fun addListener(listener: AdvisorListener) {
        listeners += listener
        listener.onState(state)
    }

    fun removeListener(listener: AdvisorListener) {
        listeners -= listener
    }

    fun selectLibrary(key: String) {
        selectedKey = key
        updatesTabRequested.set(true)
        val current = state
        listeners.forEach { it.onState(current) }
    }

    fun consumeUpdatesTabRequest(): Boolean = updatesTabRequested.getAndSet(false)

    fun applyVersion(dependency: DeclaredDependency, newVersion: String): Boolean {
        suppressOwnFileEvents()
        if (!applyLibraryVersion(project, dependency, newVersion)) return false
        acceptAppliedVersion(dependency, newVersion)
        return true
    }

    fun refresh(forceRefresh: Boolean = false) {
        if (!forceRefresh && state is AdvisorUiState.Loading) {
            pendingWatchRefresh.set(true)
            return
        }
        job.abortIfPresent()
        publish(AdvisorUiState.Loading())
        job = scope.launch {
            val result = runCatching {
                val root = project.basePath?.let { Path.of(it) }
                    ?: return@runCatching AdvisorUiState.Error(
                        AdvisorErrorKind.NotGradle,
                        "no_root",
                    )
                if (!isGradleProject(root)) {
                    return@runCatching AdvisorUiState.Error(
                        AdvisorErrorKind.NotGradle,
                    )
                }
                val graph = graph()
                val report = graph.analysis.analyze(
                    root = root,
                    forceRefresh = forceRefresh,
                    onPlan = { plan -> publishLoading(plan = plan) },
                    onProgress = { progress -> publishLoading(progress = progress) },
                )
                AdvisorUiState.Ready(report)
            }
            val next = result.fold(
                onSuccess = { it },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    sessionHolder.get()?.graph?.logger?.error("Project analysis stopped", error)
                    AdvisorUiState.Error(
                        AdvisorErrorKind.Network,
                        error.message,
                    )
                },
            )
            publish(next)
        }
    }

    override fun dispose() {
        watchJob.abortIfPresent()
        job.abortIfPresent()
        scope.abort()
        val closer = sessionHolder.getAndSet(null)?.closer ?: return
        runCatching { closer.close() }
    }

    private fun acceptAppliedVersion(dependency: DeclaredDependency, newVersion: String) {
        val current = lastReport ?: return
        val root = project.basePath?.let { Path.of(it) }
        val patched = applyKnownVersion(current, dependency, newVersion).copy(
            fingerprint = root?.let { runCatching { projectFingerprint(it) }.getOrNull() } ?: current.fingerprint,
            servedFromCache = true,
        )
        graph().analysis.rememberReport(patched)
        publish(AdvisorUiState.Ready(patched))
    }

    private fun suppressOwnFileEvents() {
        ignoreWatchUntilMs.set(System.currentTimeMillis() + WATCH_IGNORE_AFTER_APPLY_MS)
        watchJob.abortIfPresent()
        pendingWatchRefresh.set(false)
    }

    private fun scheduleWatchedRefresh() {
        if (System.currentTimeMillis() < ignoreWatchUntilMs.get()) {
            watchJob.abortIfPresent()
            return
        }
        watchJob.abortIfPresent()
        watchJob = scope.launch {
            delay(WATCH_DEBOUNCE_MS)
            if (System.currentTimeMillis() < ignoreWatchUntilMs.get()) return@launch
            if (state is AdvisorUiState.Loading) {
                pendingWatchRefresh.set(true)
                return@launch
            }
            refresh(forceRefresh = false)
        }
    }

    private fun graph(): CoreGraph {
        sessionHolder.get()?.let { return it.graph }
        val created = createCoreGraph(
            logger = IntelliJLibsHelperLogger(),
            authenticator = PreferExplicitAuthenticator(
                explicit = PasswordSafeAuthenticator(project),
                silentFallback = StudioGitCredentialSource(project)::stored,
            ),
            cacheDirectory = Path.of(
                PathManager.getSystemPath(),
                "libs-helper",
                Integer.toHexString(project.basePath.hashCode()),
            ),
        )
        val session = GraphSession(graph = created, closer = created.resourceCloser)
        return if (sessionHolder.compareAndSet(null, session)) {
            created
        } else {
            runCatching { created.close() }
            checkNotNull(sessionHolder.get()).graph
        }
    }

    private fun publishLoading(plan: ScanPlan? = null, progress: AnalysisProgress? = null) {
        synchronized(publishLock) {
            val current = state as? AdvisorUiState.Loading
            val next = AdvisorUiState.Loading(
                plan = plan ?: current?.plan,
                progress = progress ?: current?.progress,
            )
            state = next
            listeners.forEach { it.onState(next) }
        }
    }

    private fun publish(next: AdvisorUiState) {
        synchronized(publishLock) {
            state = next
            if (next is AdvisorUiState.Ready) {
                lastReport = next.report
            }
            listeners.forEach { it.onState(next) }
        }
        if (next is AdvisorUiState.Ready || next is AdvisorUiState.Error) {
            requestDependencyHintsUpdate(project)
            if (pendingWatchRefresh.getAndSet(false)) {
                scheduleWatchedRefresh()
            }
        }
    }
}

private data class GraphSession(
    val graph: CoreGraph,
    val closer: AutoCloseable,
)

private fun isWatchedGradleEvent(event: VFileEvent): Boolean {
    val path = (event.file?.path ?: event.path).replace('\\', '/')
    if ("/build/" in path || "/.gradle/" in path || "/.idea/" in path) return false
    val name = path.substringAfterLast('/')
    return isDependencyHintFile(name) || name == "gradle.properties"
}

private fun Job?.abortIfPresent() {
    this?.cancel(null)
}

private fun CoroutineScope.abort() {
    coroutineContext[Job]?.cancel(null)
}

private const val WATCH_DEBOUNCE_MS = 1500L
private const val WATCH_IGNORE_AFTER_APPLY_MS = 2500L
