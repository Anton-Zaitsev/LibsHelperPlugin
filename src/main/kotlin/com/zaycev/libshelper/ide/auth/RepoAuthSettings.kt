package com.zaycev.libshelper.ide.auth

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.zaycev.libshelper.core.auth.RepositoryAuthScheme

@Service(Service.Level.PROJECT)
@State(
    name = "LibsHelperRepoAuth",
    storages = [Storage("libsHelperRepoAuth.xml")],
)
class RepoAuthSettings : PersistentStateComponent<RepoAuthSettings.State> {
    class State {
        val profiles: MutableList<Profile> = mutableListOf()
    }

    class Profile {
        var host: String = ""
        var scheme: String = RepositoryAuthScheme.Basic.name
        var username: String = ""
        var headerName: String = ""
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        state = loaded
    }

    fun profiles(): List<Profile> = state.profiles.toList()

    fun upsert(host: String, scheme: RepositoryAuthScheme, username: String, headerName: String) {
        val existing = state.profiles.firstOrNull { it.host == host }
        val profile = existing ?: Profile().also { state.profiles.add(it) }
        profile.host = host
        profile.scheme = scheme.name
        profile.username = username
        profile.headerName = headerName
    }

    fun remove(host: String) {
        state.profiles.removeIf { it.host == host }
    }

    companion object {
        fun getInstance(project: Project): RepoAuthSettings = project.service()
    }
}
