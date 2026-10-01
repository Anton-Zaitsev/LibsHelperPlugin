package com.zaycev.libshelper.ide.auth

internal fun git4IdeaEnabled(): Boolean = try {
    Class.forName("git4idea.GitUtil")
    true
} catch (_: ClassNotFoundException) {
    false
}

