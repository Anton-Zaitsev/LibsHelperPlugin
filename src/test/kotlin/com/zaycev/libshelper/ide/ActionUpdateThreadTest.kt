package com.zaycev.libshelper.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.zaycev.libshelper.ide.diagnostics.ReportProblemAction
import com.zaycev.libshelper.ide.inlay.LibsHelperInlayMenuGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ActionUpdateThreadTest {
    @Test
    fun pluginActionsUpdateOnTheBackgroundThread() {
        val actions = listOf(
            CheckInLibsHelperAction(),
            RefreshLibsHelperAction(),
            ReportProblemAction(),
            LibsHelperInlayMenuGroup(),
        )
        actions.forEach { action ->
            assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread, action.javaClass.name)
        }
    }
}
