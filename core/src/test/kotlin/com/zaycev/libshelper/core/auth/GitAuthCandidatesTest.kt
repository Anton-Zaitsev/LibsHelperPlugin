package com.zaycev.libshelper.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitAuthCandidatesTest {
    @Test
    fun skipsOfficialCatalogsEvenIfProjectHasGitRemotes() {
        val urls = gitAuthCandidateUrls(
            mavenHost = "repo1.maven.org",
            projectGitRemoteUrls = listOf("https://git.company.local/mobile/app.git"),
        )
        assertTrue(urls.isEmpty())
        assertTrue(
            gitAuthCandidateUrls(
                mavenHost = "dl.google.com",
                projectGitRemoteUrls = listOf("https://github.com/acme/app.git"),
            ).isEmpty(),
        )
    }

    @Test
    fun reusesSameCompanyGitRemoteForNexusHost() {
        val urls = gitAuthCandidateUrls(
            mavenHost = "nexus.acme.example",
            projectGitRemoteUrls = listOf(
                "https://git.acme.example/platform/ui.git",
                "https://github.com/acme/oss.git",
            ),
        )
        assertEquals(
            listOf(
                "https://git.acme.example/platform/ui.git",
                "https://nexus.acme.example/",
            ),
            urls,
        )
    }

    @Test
    fun mapsGithubPackagesToGithubRemote() {
        val urls = gitAuthCandidateUrls(
            mavenHost = "maven.pkg.github.com",
            projectGitRemoteUrls = listOf("https://github.com/acme/lib.git"),
        )
        assertEquals(
            listOf("https://github.com/acme/lib.git", "https://maven.pkg.github.com/"),
            urls,
        )
    }

    @Test
    fun doesNotMixUnrelatedGitHosts() {
        val urls = gitAuthCandidateUrls(
            mavenHost = "gitlab.example.com",
            projectGitRemoteUrls = listOf("https://github.com/acme/lib.git"),
        )
        assertEquals(listOf("https://gitlab.example.com/"), urls)
    }

    @Test
    fun keepsOneCandidatePerGitHost() {
        val urls = gitAuthCandidateUrls(
            mavenHost = "nexus.company.local",
            projectGitRemoteUrls = listOf(
                "https://git.company.local/one.git",
                "https://git.company.local/two.git",
                "git@git.company.local:three.git",
            ),
        )
        assertEquals(1, urls.count { it.contains("git.company.local") })
        assertTrue(urls.last() == "https://nexus.company.local/")
    }

    @Test
    fun stripsEmbeddedCredentialsAndConvertsSsh() {
        assertEquals(
            "https://git.example.com/group/repo.git",
            httpUrlForGitAuth("https://user:s3cret@git.example.com/group/repo.git"),
        )
        assertEquals(
            "https://git.example.com/group/repo.git",
            httpUrlForGitAuth("git@git.example.com:group/repo.git"),
        )
        assertEquals(
            "https://git.example.com/group/repo.git",
            httpUrlForGitAuth("ssh://git@git.example.com/group/repo.git"),
        )
        assertNull(httpUrlForGitAuth("file:///tmp/repo.git"))
    }

    @Test
    fun studioGitAuthUsesBasicOrBearer() {
        val basic = studioGitAuthOf("ci", "token")
        assertEquals(RepositoryAuthScheme.Basic, basic?.scheme)
        assertEquals("ci", basic?.username)
        assertEquals("token", basic?.secret)
        val bearer = studioGitAuthOf("  ", "ghp_1")
        assertEquals(RepositoryAuthScheme.Bearer, bearer?.scheme)
        assertEquals("ghp_1", bearer?.secret)
        assertNull(studioGitAuthOf("ci", " "))
    }

    @Test
    fun registrableDomainHandlesCompoundSuffix() {
        assertEquals("company.ru", registrableDomain("nexus.company.ru"))
        assertEquals("example.co.uk", registrableDomain("git.example.co.uk"))
        assertEquals("github.com", registrableDomain("maven.pkg.github.com"))
    }
}
