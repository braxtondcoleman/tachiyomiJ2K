package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.network.interceptor.CloudflareChallengeSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebsiteAccessPolicyTest {
    @Test fun onlyCloudflare403And503TriggerInteractiveFallback() {
        assertTrue(CloudflareChallengeSignal.isChallengeResponse(403, "cloudflare", null))
        assertTrue(CloudflareChallengeSignal.isChallengeResponse(503, "CloudFlare-Nginx", null))
        assertTrue(CloudflareChallengeSignal.isChallengeResponse(403, "nginx", "challenge"))
        assertFalse(CloudflareChallengeSignal.isChallengeResponse(429, "cloudflare", null))
        assertFalse(CloudflareChallengeSignal.isChallengeResponse(403, "unrelated server", null))
        assertFalse(CloudflareChallengeSignal.isChallengeResponse(500, "cloudflare", null))
    }

    @Test fun verifiesOnlyHttpsWebsiteWithNoEmbeddedCredentials() {
        assertEquals("manga.example", WebsiteAccessPolicy.host("https://Manga.Example/reader"))
        assertNull(WebsiteAccessPolicy.host("http://manga.example/"))
        assertNull(WebsiteAccessPolicy.host("https://user:pass@manga.example/"))
        assertNull(WebsiteAccessPolicy.host("javascript:alert(1)"))
        assertNull(WebsiteAccessPolicy.host("https://manga.example:8443/chapter"))
        assertNull(WebsiteAccessPolicy.host("file:///private/secret"))
    }

    @Test fun unrelatedOrSpoofedHostsDoNotOpenAutomaticOverlay() {
        assertTrue(WebsiteAccessPolicy.sameSourceHost("https://manga.example", "https://cdn.manga.example/chapter"))
        assertFalse(WebsiteAccessPolicy.sameSourceHost("https://manga.example", "https://manga.example.evil.test/chapter"))
        assertFalse(WebsiteAccessPolicy.sameSourceHost("https://manga.example", "https://another.example/chapter"))
    }

    @Test fun fallsBackToSourceHomeWhenImageHostDiffers() {
        assertEquals(
            "https://manga.example",
            WebsiteAccessPolicy.chooseTarget("https://manga.example", "https://images.external.example/page"),
        )
        assertEquals(
            "https://manga.example/chapter",
            WebsiteAccessPolicy.chooseTarget("https://manga.example", "https://manga.example/chapter"),
        )
    }

    @Test fun onlyNewCookieAutomaticallyClosesAndRetries() {
        val before = "session=a"
        val after = "session=a; cf_clearance=abc123"
        assertTrue(WebsiteAccessPolicy.shouldRetryAutomatically(true, false, before, after))
        assertFalse(WebsiteAccessPolicy.shouldRetryAutomatically(false, false, before, after))
        assertFalse(WebsiteAccessPolicy.shouldRetryAutomatically(true, true, before, after))
        assertFalse(WebsiteAccessPolicy.shouldRetryAutomatically(true, false, after, after))
        assertFalse(WebsiteAccessPolicy.shouldRetryAutomatically(true, false, after, "session=b"))
    }

    @Test fun cookieParsingNeverTreatsSimilarNameAsClearance() {
        assertNull(WebsiteAccessPolicy.clearance("cf_clearance_backup=abc; session=123"))
        assertEquals("xyz", WebsiteAccessPolicy.clearance("token=1; cf_clearance=xyz; other=2"))
    }
    @Test fun aBackgroundChallengeDoesNotInterruptHealthyReading() {
        assertFalse(WebsiteAccessPolicy.shouldOfferOverlay(
            foreground = true,
            sameSource = true,
            sameChapter = true,
            currentPageFailed = false,
            chapterFailed = false,
        ))
        assertFalse(WebsiteAccessPolicy.shouldOfferOverlay(
            foreground = false,
            sameSource = true,
            sameChapter = true,
            currentPageFailed = true,
            chapterFailed = false,
        ))
        assertFalse(WebsiteAccessPolicy.shouldOfferOverlay(
            foreground = true,
            sameSource = false,
            sameChapter = true,
            currentPageFailed = true,
            chapterFailed = false,
        ))
        assertFalse(WebsiteAccessPolicy.shouldOfferOverlay(
            foreground = true,
            sameSource = true,
            sameChapter = false,
            currentPageFailed = true,
            chapterFailed = false,
        ))
    }

    @Test fun currentPageOrCurrentChapterFailureMayOfferInteractiveHelp() {
        assertTrue(WebsiteAccessPolicy.shouldOfferOverlay(
            foreground = true,
            sameSource = true,
            sameChapter = true,
            currentPageFailed = true,
            chapterFailed = false,
        ))
        assertTrue(WebsiteAccessPolicy.shouldOfferOverlay(
            foreground = true,
            sameSource = true,
            sameChapter = true,
            currentPageFailed = false,
            chapterFailed = true,
        ))
    }

}
