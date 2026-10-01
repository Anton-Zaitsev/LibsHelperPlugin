package com.zaycev.libshelper.ide.auth

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

private const val CREDENTIAL_QUEUE_CAPACITY = 64

/**
 * PasswordSafe is a non-cancelable slow call. Since 2025.3 a modal-progress
 * worker inherits the caller's read lock, and the platform logs
 * "Non-cancelable slow operations are prohibited inside read action".
 * The store access itself runs on a thread that never received that context.
 * A modal dialog is only the wait, so the EDT can still paint a keychain prompt.
 */
internal fun <T> blockingCredentials(project: Project?, action: () -> T): T {
    val app = ApplicationManager.getApplication()
    if (app.isUnitTestMode) return action()
    if (!app.isDispatchThread && !app.isReadAccessAllowed) return action()

    val pending = worker.submit(action)
    if (!app.isDispatchThread || app.isWriteAccessAllowed) return pending.await()
    return ProgressManager.getInstance().runProcessWithProgressSynchronously(
        ThrowableComputable { pending.await() },
        "LibsHelper",
        false,
        project,
    )
}

private val worker by lazy { CredentialWorker() }

private class CredentialWorker {
    private val tasks = ArrayBlockingQueue<Runnable>(CREDENTIAL_QUEUE_CAPACITY)
    private val thread = Thread(::drain, "libshelper-credentials").apply {
        isDaemon = true
        start()
    }

    fun <T> submit(action: () -> T): CompletableFuture<T> {
        if (Thread.currentThread() === thread) {
            val done = CompletableFuture<T>()
            try {
                done.complete(action())
            } catch (error: Throwable) {
                done.completeExceptionally(error)
            }
            return done
        }
        val pending = CompletableFuture<T>()
        try {
            tasks.put {
                try {
                    pending.complete(action())
                } catch (error: Throwable) {
                    pending.completeExceptionally(error)
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            pending.completeExceptionally(error)
        }
        return pending
    }

    private fun drain() {
        while (!Thread.currentThread().isInterrupted) {
            val task = try {
                tasks.take()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            task.run()
        }
    }
}

private fun <T> CompletableFuture<T>.await(): T {
    try {
        return get()
    } catch (error: ExecutionException) {
        throw error.cause ?: error
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw error
    }
}
