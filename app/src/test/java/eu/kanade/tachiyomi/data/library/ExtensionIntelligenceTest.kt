package eu.kanade.tachiyomi.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionIntelligenceTest {
    private val source = ExtensionIntelligence.Source(
        id = 1234,
        name = "Manga Site",
        lang = "en",
        website = "https://manga.example/reader",
    )
    private fun available(version: Long = 4, repo: String = "https://repo.example/index.json") =
        ExtensionIntelligence.Available(
            pkg = "ext.manga",
            name = "Manga Extension",
            versionCode = version,
            libVersion = 1.5,
            sources = listOf(source),
            repoUrl = repo,
        )
    private fun installed(
        version: Long = 3,
        obsolete: Boolean = false,
        update: Boolean = false,
    ) = ExtensionIntelligence.Installed(
        pkg = "ext.manga",
        name = "Manga Extension",
        versionCode = version,
        libVersion = 1.5,
        sources = listOf(source),
        updateFlag = update,
        obsoleteFlag = obsolete,
        repoUrl = "https://repo.example/index.json",
    )
    private fun find(
        sourceId: Long? = 1234,
        sourceName: String? = "Manga Site",
        sourceHost: String? = null,
        installed: List<ExtensionIntelligence.Installed> = listOf(installed()),
        available: List<ExtensionIntelligence.Available> = listOf(available()),
        languages: Set<String> = setOf("en"),
        hidden: Set<String> = emptySet(),
        untrusted: Set<String> = emptySet(),
    ) = ExtensionIntelligence.find(
        sourceId = sourceId,
        sourceName = sourceName,
        sourceHost = sourceHost,
        installed = installed,
        available = available,
        configuredRepos = setOf("https://repo.example/index.json"),
        enabledLanguages = languages,
        hiddenSourceIds = hidden,
        untrustedPackages = untrusted,
    )

    @Test fun exactSourceIdOutranksOtherNames() {
        assertEquals(200, find().single().score)
    }

    @Test fun missingExtensionListedInIndexIsActionable() {
        val result = find(installed = emptyList()).single()
        assertEquals(ExtensionIntelligence.Status.MISSING, result.status)
        assertTrue(result.fromConfiguredRepo)
    }

    @Test fun versionUpdateIsNotAnAutomaticInstall() {
        assertEquals(ExtensionIntelligence.Status.UPDATE_AVAILABLE, find().single().status)
    }

    @Test fun currentVersionNoLongerShowsUpdate() {
        assertEquals(ExtensionIntelligence.Status.INSTALLED, find(installed = listOf(installed(version = 4))).single().status)
    }

    @Test fun absenceFromLoadedIndexIsNotProofOfDeadWebsite() {
        val other = ExtensionIntelligence.Available(
            pkg = "ext.other", name = "Other", versionCode = 1, libVersion = 1.5,
            sources = listOf(ExtensionIntelligence.Source(89, "Other", "en")),
            repoUrl = "https://repo.example/index.json",
        )
        val result = find(available = listOf(other)).first()
        assertEquals(ExtensionIntelligence.Status.INDEX_NOT_LISTED, result.status)
        assertTrue(result.notes.any { "NOT proof" in it })
    }

    @Test fun emptyIndexCannotMarkInstalledSourceAsObsolete() {
        val result = find(available = emptyList(), installed = listOf(installed(obsolete = true))).single()
        assertEquals(ExtensionIntelligence.Status.INSTALLED, result.status)
        assertTrue(result.notes.any { "index not loaded" in it.lowercase() })
    }

    @Test fun anUntrustedSignerIsNotAutomaticallyTrusted() {
        val result = find(untrusted = setOf("ext.manga")).single()
        assertEquals(ExtensionIntelligence.Status.UNTRUSTED_PACKAGE, result.status)
        assertTrue(result.notes.any { "untrusted" in it.lowercase() })
    }

    @Test fun disabledLanguageAndHiddenSourceWarnExplicitly() {
        assertEquals(ExtensionIntelligence.Status.LANGUAGE_DISABLED, find(languages = emptySet()).single().status)
        assertEquals(ExtensionIntelligence.Status.SOURCE_HIDDEN, find(hidden = setOf("1234")).single().status)
    }

    @Test fun matchingWebsiteIsAdvisoryAndWeakerThanSourceId() {
        val result = find(sourceId = 777, sourceName = "Unknown", sourceHost = "https://manga.example/manga").single()
        assertEquals(100, result.score)
        assertTrue(result.notes.any { "verify manga identity" in it })
    }

    @Test fun shortUnrelatedNamesDoNotBecomeFalseMatches() {
        assertTrue(find(sourceId = 777, sourceName = "IT", sourceHost = null).isEmpty())
    }

    @Test fun configuredRepositoryIsNotASignerTrustGuarantee() {
        assertFalse(ExtensionIntelligence.configured("https://notconfigured.example/index.json", setOf("https://repo.example/index.json")))
        assertTrue(find().single().notes.any { "signer trust" in it.lowercase() })
    }

    @Test fun nonHttpsRepoIsNotPresentedAsSecureSource() {
        assertEquals(null, ExtensionIntelligence.host("http://repo.example/index.json"))
        assertEquals("repo.example", ExtensionIntelligence.host("https://www.repo.example/index.json"))
    }
}
