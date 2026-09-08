package com.zaycev.libshelper.core.log

internal class NoOpLibsHelperLogger : LibsHelperLogger {
    override fun debug(message: String) = Unit
    override fun info(message: String) = Unit
    override fun warn(message: String, error: Throwable?) = Unit
    override fun error(message: String, error: Throwable?) = Unit
}
