package com.zaycev.libshelper.ide.auth

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

private val GIT4IDEA_ID = PluginId.getId("Git4Idea")

internal fun git4IdeaEnabled(): Boolean {
    val plugin = PluginManagerCore.getPlugin(GIT4IDEA_ID) ?: return false
    @Suppress("DEPRECATION")
    return plugin.isEnabled
}

