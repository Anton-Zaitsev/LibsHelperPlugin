package com.zaycev.libshelper.ide.log

import com.intellij.openapi.diagnostic.Logger
import com.zaycev.libshelper.core.log.DefaultTextRedactor
import com.zaycev.libshelper.core.log.LibsHelperLogger
import com.zaycev.libshelper.ide.diagnostics.LibsHelperDiagnostics
import com.zaycev.libshelper.ide.i18n.LibsHelperSettings

internal class IntelliJLibsHelperLogger : LibsHelperLogger {
    override fun debug(message: String) = write("analysis", message, null, debug = true)

    override fun info(message: String) = write("analysis", message, null, debug = false)

    override fun warn(message: String, error: Throwable?) = write("analysis", message, error, debug = false)

    override fun error(message: String, error: Throwable?) = write("analysis", message, error, debug = false, failure = true)

    fun network(message: String) = write("network", message, null, debug = false)

    fun auth(message: String) = write("auth", message, null, debug = false)

    fun mcp(message: String) = write("mcp", message, null, debug = false)

    private fun write(
        category: String,
        message: String,
        error: Throwable?,
        debug: Boolean,
        failure: Boolean = false,
    ) {
        val safe = DefaultTextRedactor.redact(message)
        val log = Logger.getInstance("com.zaycev.libshelper.$category")
        val verbose = runCatching { LibsHelperSettings.getInstance().verboseLog }.getOrDefault(false)
        when {
            failure && error != null -> log.error(safe, error)
            failure -> log.error(safe)
            error != null && !debug -> log.warn(safe, error)
            debug && verbose -> log.debug(safe)
            debug -> Unit
            else -> log.info(safe)
        }
        LibsHelperDiagnostics.log(category, safe)
    }
}
