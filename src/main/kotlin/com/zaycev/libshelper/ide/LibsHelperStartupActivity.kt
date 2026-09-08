package com.zaycev.libshelper.ide

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class LibsHelperStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<LibsHelperService>().refresh()
    }
}
