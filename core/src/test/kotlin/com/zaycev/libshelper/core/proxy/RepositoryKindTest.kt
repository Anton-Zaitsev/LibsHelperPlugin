package com.zaycev.libshelper.core.proxy

import com.zaycev.libshelper.core.model.RepositoryKind
import com.zaycev.libshelper.core.model.RepositoryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RepositoryKindTest {
    @Test
    fun classifiesOfficialAndProxy() {
        assertEquals(RepositoryKind.Official, detectRepositoryKind("https://repo1.maven.org/maven2/"))
        assertEquals(RepositoryKind.Official, detectRepositoryKind("https://dl.google.com/dl/android/maven2/"))
        assertEquals(RepositoryKind.Official, detectRepositoryKind("https://plugins.gradle.org/m2/"))
        assertEquals(
            RepositoryKind.ProxyMirror,
            detectRepositoryKind("https://nexus.company.local/repository/maven-public/"),
        )
        assertEquals(
            RepositoryKind.ProxyMirror,
            detectRepositoryKind("https://artifactory.example.com/artifactory/libs-release"),
        )
    }

    @Test
    fun detectsTypeFromNexusProxyPath() {
        assertEquals(
            RepositoryType.Google,
            detectRepositoryType("https://nexus.company.local/repository/maven-proxy-google/"),
        )
        assertEquals(
            RepositoryType.PluginPortal,
            detectRepositoryType("https://nexus.company.local/repository/maven-proxy-gradle-plugins/"),
        )
        assertEquals(
            RepositoryType.MavenCentral,
            detectRepositoryType("https://nexus.company.local/repository/maven-proxy/"),
        )
        assertEquals(
            RepositoryType.MavenCentral,
            detectRepositoryType("https://nexus.company.local/repository/maven-gitverse-proxy/"),
        )
        assertEquals(
            RepositoryType.Maven,
            detectRepositoryType("https://nexus.company.local/repository/maven-proxy-jitpack/"),
        )
    }

    @Test
    fun parsesHttpProxyFromGradleProperties() {
        val props = parseGradleProperties(
            """
            org.gradle.jvmargs=-Xmx2g
            systemProp.https.proxyHost=proxy.office.local
            systemProp.https.proxyPort=3128
            systemProp.http.nonProxyHosts=localhost|*.office.local
            """.trimIndent(),
        )
        val proxy = parseHttpProxy(props)
        assertNotNull(proxy)
        assertEquals("proxy.office.local", proxy.host)
        assertEquals(3128, proxy.port)
        assertNull(parseHttpProxy(mapOf("foo" to "bar")))
    }
}
