package com.zaycev.libshelper.ide.log

import com.intellij.openapi.diagnostic.Logger
import com.zaycev.libshelper.core.log.LibsHelperLogger

internal class IntelliJLibsHelperLogger : LibsHelperLogger {
    private val log = Logger.getInstance("com.zaycev.libshelper")

    override fun debug(message: String) {
        log.debug(message)
    }

    override fun info(message: String) {
        log.info(message)
    }

    override fun warn(message: String, error: Throwable?) {
        if (error == null) log.warn(message) else log.warn(message, error)
    }

    override fun error(message: String, error: Throwable?) {
        if (error == null) log.error(message) else log.error(message, error)
    }
}
