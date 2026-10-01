package com.zaycev.libshelper.ide.mcp

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.zaycev.libshelper.core.PLUGIN_VERSION
import com.zaycev.libshelper.ide.auth.blockingCredentials
import com.zaycev.libshelper.ide.diagnostics.LibsHelperDiagnostics
import com.zaycev.libshelper.mcp.DefaultMcpClientSettings
import com.zaycev.libshelper.mcp.LibsHelperMcpHost

@Service(Service.Level.APP)
@State(name = "LibsHelperMcp", storages = [Storage("libsHelperMcp.xml")])
class McpServerService : PersistentStateComponent<McpServerService.State>, Disposable {
    class State {
        var enabled: Boolean = false
        var port: Int = DEFAULT_PORT
        var allowFileChanges: Boolean = false
    }

    private var stored = State()
    private var host: LibsHelperMcpHost? = null
    private var cachedToken: String? = null

    val enabled: Boolean get() = stored.enabled
    val port: Int get() = host?.port ?: stored.port
    val allowFileChanges: Boolean get() = stored.allowFileChanges
    val running: Boolean get() = host != null

    override fun getState(): State = stored

    override fun loadState(state: State) {
        stored = state
        if (stored.enabled) {
            try {
                restart()
            } catch (error: Exception) {
                thisLogger().warn("MCP server did not start", error)
            } catch (error: LinkageError) {
                thisLogger().warn("MCP server did not start", error)
            }
        }
    }

    fun update(enabled: Boolean, port: Int, allowFileChanges: Boolean) {
        stored.enabled = enabled
        stored.port = port.coerceIn(1, MAX_TCP_PORT)
        stored.allowFileChanges = allowFileChanges
        if (enabled) restart() else stop()
    }

    fun reissueToken(): String {
        val token = DefaultMcpClientSettings.newToken()
        storeToken(token)
        if (stored.enabled) restart()
        return token
    }

    fun cursorConfig(): String = DefaultMcpClientSettings.cursor(port, token())

    fun claudeCodeConfig(): String = DefaultMcpClientSettings.claudeCode(port, token())

    fun claudeDesktopConfig(): String = DefaultMcpClientSettings.claudeDesktop(port, token())

    fun restart() {
        stop()
        val created = LibsHelperMcpHost(
            backend = IdeLibsHelperBackend { stored.allowFileChanges },
            token = token(),
            requestedPort = stored.port,
            serverVersion = PLUGIN_VERSION,
        )
        created.start()
        host = created
        LibsHelperDiagnostics.record("mcp", "start", created.port.toString())
    }

    fun stop() {
        host?.stop()
        host = null
    }

    override fun dispose() {
        stop()
    }

    private fun token(): String {
        cachedToken?.takeIf { it.isNotBlank() }?.let { return it }
        val existing = blockingCredentials(null) {
            PasswordSafe.instance[attributes()]?.getPasswordAsString()
        }
        if (!existing.isNullOrBlank()) {
            cachedToken = existing
            return existing
        }
        val created = DefaultMcpClientSettings.newToken()
        storeToken(created)
        return created
    }

    private fun storeToken(token: String) {
        blockingCredentials(null) {
            PasswordSafe.instance[attributes()] = Credentials("libshelper", token)
        }
        cachedToken = token
    }

    private fun attributes() = CredentialAttributes(generateServiceName("LibsHelper", "mcp"), "mcp")

    companion object {
        const val DEFAULT_PORT = 8765

        fun getInstance(): McpServerService =
            ApplicationManager.getApplication().getService(McpServerService::class.java)
    }
}

private const val MAX_TCP_PORT = 65_535
