package com.zaycev.libshelper.ide.inlay

data class DependencyHintSettings(
    var showOutdated: Boolean = true,
    var showCurrent: Boolean = true,
    var showPrerelease: Boolean = true,
)
