package com.zaycev.libshelper.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.zaycev.libshelper.core.analytics.DefaultAnalyticsComputer
import com.zaycev.libshelper.core.auth.PreferExplicitAuthenticator
import com.zaycev.libshelper.core.di.CoreGraph
import com.zaycev.libshelper.core.di.createCoreGraph
import com.zaycev.libshelper.core.inventory.isGradleProject
import com.zaycev.libshelper.core.inventory.pathInsideRoot
import com.zaycev.libshelper.core.inventory.projectFingerprint
import com.zaycev.libshelper.core.model.AdvisorErrorKind
import com.zaycev.libshelper.core.model.DeclaredDependency
import com.zaycev.libshelper.core.model.ProjectReport
import com.zaycev.libshelper.core.model.ScanPlan
import com.zaycev.libshelper.core.progress.AnalysisProgress
import com.zaycev.libshelper.core.recommend.applyKnownVersion
import com.zaycev.libshelper.core.recommend.syncRequestedVersions
import com.zaycev.libshelper.ide.apply.applyLibraryVersion
import com.zaycev.libshelper.ide.auth.PasswordSafeAuthenticator
import com.zaycev.libshelper.ide.auth.StudioGitCredentialSource
import com.zaycev.libshelper.ide.i18n.LocaleChangeListener
import com.zaycev.libshelper.ide.inlay.isDependencyHintFile
import com.zaycev.libshelper.ide.inlay.requestDependencyHintsUpdate
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.LocalFileSystem
import com.zaycev.libshelper.ide.diagnostics.LibsHelperDiagnostics
import com.zaycev.libshelper.ide.log.IntelliJLibsHelperLogger
import com.zaycev.libshelper.ide.project.primaryGradleRoot
import kotlin.concurrent.atomics.AtomicReference as AtomicState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class LibsHelperService(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {
    private val listeners = CopyOnWriteArrayList<AdvisorListener>()
    private val sessionHolder = AtomicReference<GraphSession?>(null)
    private val publishLock = Any()
    private val pendingWatchRefresh = AtomicBoolean(false)
    private val ignoreWatchUntilMs = AtomicLong(0)
    private val updatesTabRequested = AtomicBoolean(false)
    private val listQueryRef = AtomicReference("")
    private val listQueryRequested = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val editorSyncTicket = AtomicLong(0)
    private val selectedKeyRef = AtomicReference<String?>(null)
    private var job: Job? = null
    private var watchJob: Job? = null

    private val stateRef = AtomicState<AdvisorUiState>(AdvisorUiState.Idle)
    var state: AdvisorUiState
        get() = stateRef.load()
        private set(value) {
            stateRef.store(value)
        }

    private val reportRef = AtomicState<ProjectReport?>(null)
    var lastReport: ProjectReport?
        get() = reportRef.load()
        private set(value) {
            reportRef.store(value)
        }

    var selectedKey: String?
        get() = selectedKeyRef.get()
        set(value) {
            selectedKeyRef.set(value)
        }

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
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if (!isDependencyHintFile(file.name)) return
                    scheduleEditorVersionSync()
                }
            },
            this,
        )
    }

    fun addListener(listener: AdvisorListener) {
        listeners += listener
        listener.onState(state)
    }

    fun removeListener(listener: AdvisorListener) {
        listeners -= listener
    }

    fun selectLibrary(key: String, query: String = key) {
        selectedKey = key
        listQueryRef.set(query)
        listQueryRequested.set(true)
        updatesTabRequested.set(true)
        val current = state
        listeners.forEach { it.onState(current) }
    }

    fun consumeUpdatesTabRequest(): Boolean = updatesTabRequested.getAndSet(false)

    fun consumeListQuery(): String? = if (listQueryRequested.getAndSet(false)) listQueryRef.get() else null

    @Suppress("InjectDispatcher")
    fun openRelative(relativePath: String, line: Int?, token: String?) {
        val root = primaryGradleRoot(project) ?: return
        scope.launch {
            val file = pathInsideRoot(root, relativePath) ?: return@launch
            val virtual = withContext(Dispatchers.IO) {
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file.toFile())
            } ?: return@launch
            withContext(Dispatchers.EDT) {
                if (project.isDisposed) return@withContext
                val descriptor = readAction {
                    if (line != null && line > 0) {
                        OpenFileDescriptor(project, virtual, line - 1, 0)
                    } else {
                        val text = FileDocumentManager.getInstance().getDocument(virtual)?.text.orEmpty()
                        val offset = token?.takeIf { it.isNotBlank() }?.let { text.indexOf(it) }?.takeIf { it >= 0 } ?: 0
                        OpenFileDescriptor(project, virtual, offset)
                    }
                }
                FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
            }
        }
    }

    fun launchEdt(block: () -> Unit) {
        scope.launch(Dispatchers.EDT) {
            if (!project.isDisposed) block()
        }
    }

    fun enqueueApply(dependency: DeclaredDependency, newVersion: String) {
        scope.launch {
            applyVersion(dependency, newVersion)
        }
    }

    suspend fun applyVersion(dependency: DeclaredDependency, newVersion: String): Boolean {
        suppressOwnFileEvents()
        val applied = applyLibraryVersion(project, dependency, newVersion)
        if (applied) acceptAppliedVersion(dependency, newVersion)
        return applied
    }

    suspend fun refreshAndAwait(forceRefresh: Boolean = false): ProjectReport? {
        if (forceRefresh || state !is AdvisorUiState.Loading) {
            refresh(forceRefresh)
        }
        job?.join()
        return lastReport
    }

    fun refresh(forceRefresh: Boolean = false) {
        if (!forceRefresh && state is AdvisorUiState.Loading) {
            pendingWatchRefresh.set(true)
            return
        }
        job.abortIfPresent()
        val ticket = generation.incrementAndGet()
        publish(AdvisorUiState.Loading())
        LibsHelperDiagnostics.record("analysis", "refresh", forceRefresh.toString())
        job = scope.launch {
            val result = runCatching {
                val root = primaryGradleRoot(project)
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
            if (generation.get() == ticket) publish(next)
        }
    }

    override fun dispose() {
        watchJob.abortIfPresent()
        job.abortIfPresent()
        val closer = sessionHolder.getAndSet(null)?.closer ?: return
        runCatching { closer.close() }
    }

    private fun acceptAppliedVersion(dependency: DeclaredDependency, newVersion: String) {
        if (state is AdvisorUiState.Loading) return
        val ticket = generation.get()
        val current = lastReport ?: return
        val root = primaryGradleRoot(project)
        val patched = applyKnownVersion(current, dependency, newVersion, DefaultAnalyticsComputer()).copy(
            fingerprint = root?.let { runCatching { projectFingerprint(it) }.getOrNull() } ?: current.fingerprint,
            servedFromCache = true,
        )
        graph().analysis.rememberReport(patched)
        if (generation.get() != ticket || state is AdvisorUiState.Loading) return
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
                Integer.toHexString(primaryGradleRoot(project)?.hashCode() ?: 0),
            ),
            analytics = DefaultAnalyticsComputer(),
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

    private fun publish(next: AdvisorUiState, syncEditors: Boolean = true) {
        synchronized(publishLock) {
            state = next
            if (next is AdvisorUiState.Ready) {
                lastReport = next.report
            }
            listeners.forEach { it.onState(next) }
        }
        if (next is AdvisorUiState.Ready || next is AdvisorUiState.Error) {
            requestDependencyHintsUpdate(project)
            if (syncEditors && next is AdvisorUiState.Ready) scheduleEditorVersionSync()
            if (pendingWatchRefresh.getAndSet(false)) {
                scheduleWatchedRefresh()
            }
        }
    }

    private fun scheduleEditorVersionSync() {
        if (project.isDisposed) return
        val ticket = editorSyncTicket.incrementAndGet()
        scope.launch {
            delay(EDITOR_VERSION_SYNC_MS)
            if (editorSyncTicket.get() != ticket) return@launch
            reconcileOpenEditors(ticket)
        }
    }

    private suspend fun reconcileOpenEditors(ticket: Long) {
        if (project.isDisposed || state is AdvisorUiState.Loading) return
        val report = lastReport ?: return
        val files = try {
            readAction { openHintTexts() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            thisLogger().warn("Failed to read open editors for version badges", error)
            return
        }
        if (editorSyncTicket.get() != ticket || state is AdvisorUiState.Loading) return
        val patched = files.fold(report) { current, (relativePath, text) ->
            syncRequestedVersions(current, relativePath, text, DefaultAnalyticsComputer())
        }
        if (patched === report || editorSyncTicket.get() != ticket || state is AdvisorUiState.Loading) return
        publish(AdvisorUiState.Ready(patched), syncEditors = false)
    }

    private fun openHintTexts(): List<Pair<String, String>> {
        val root = primaryGradleRoot(project)?.toAbsolutePath()?.normalize()?.toString()?.replace('\\', '/')
            ?: return emptyList()
        val prefix = root.trimEnd('/') + "/"
        return FileEditorManager.getInstance(project).allEditors.mapNotNull { fileEditor ->
            val editor = (fileEditor as? TextEditor)?.editor ?: return@mapNotNull null
            val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return@mapNotNull null
            if (!isDependencyHintFile(file.name)) return@mapNotNull null
            val path = file.path.replace('\\', '/')
            if (!path.startsWith(prefix)) return@mapNotNull null
            path.removePrefix(prefix) to editor.document.text
        }.distinctBy { it.first }
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

private const val WATCH_DEBOUNCE_MS = 1500L
private const val WATCH_IGNORE_AFTER_APPLY_MS = 2500L
private const val EDITOR_VERSION_SYNC_MS = 100L
