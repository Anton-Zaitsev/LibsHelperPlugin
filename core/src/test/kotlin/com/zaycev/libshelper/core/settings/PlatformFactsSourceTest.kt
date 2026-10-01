package com.zaycev.libshelper.core.settings

import com.zaycev.libshelper.core.auth.RepositoryAuth
import com.zaycev.libshelper.core.model.HttpProxySettings
import com.zaycev.libshelper.core.network.HttpFailure
import com.zaycev.libshelper.core.network.HttpGetResult
import com.zaycev.libshelper.core.network.MetadataGateway
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformFactsSourceTest {
    @Test
    fun parsersFeedFactsWithoutABundledTable() {
        val xml = """
            <remotePackage path="platforms;android-35">
              <description>Android 15</description>
              <api-level>35</api-level>
            </remotePackage>
            <remotePackage path="ndk;28.0.1"/>
            <remotePackage path="ndk;27.0.1"/>
        """.trimIndent()
        val facts = platformFactsOf(xml, """{"version":"21.0.1","version":"17.0.9"}""")
        assertEquals(35, facts.playTargetApi)
        assertEquals(listOf("28.0.1", "27.0.1"), facts.ndkStable)
        assertEquals(21, facts.jdkCurrent)
        assertTrue(17 in facts.jdkLts)
    }

    @Test
    fun failedFetchesLeaveFactsEmpty() = runTest {
        val gateway = object : MetadataGateway {
            override suspend fun get(
                url: String,
                httpProxy: HttpProxySettings?,
                credentials: RepositoryAuth?,
                allowAuthPrompt: Boolean,
            ): HttpGetResult = HttpGetResult.Failure(HttpFailure.HttpStatus("https://example.test", 503), 1)
        }
        val facts = NetworkPlatformFactsSource(gateway).load(null)
        assertTrue(facts.stableApis.isEmpty())
        assertEquals(null, facts.jdkCurrent)
    }
}
