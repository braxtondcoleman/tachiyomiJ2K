package eu.kanade.tachiyomi.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRecoveryPlannerTest {
    @Test fun classifiesHttp429WithoutTreatingSiteAsDead() {
        assertEquals(SourceRecoveryPlanner.FailureKind.RATE_LIMIT, SourceRecoveryPlanner.classify("HTTP 429 Too Many Requests"))
    }

    @Test fun classifiesServerProblemsSeparatelyFromTimeouts() {
        assertEquals(SourceRecoveryPlanner.FailureKind.SERVER, SourceRecoveryPlanner.classify("HTTP 503 Service Unavailable"))
        assertEquals(SourceRecoveryPlanner.FailureKind.NETWORK, SourceRecoveryPlanner.classify("SocketTimeoutException: timed out"))
    }

    @Test fun distinguishesLoginCloudflareAndParserFailure() {
        assertEquals(SourceRecoveryPlanner.FailureKind.LOGIN, SourceRecoveryPlanner.classify("Cloudflare challenge required"))
        assertEquals(SourceRecoveryPlanner.FailureKind.LOGIN, SourceRecoveryPlanner.classify("HTTP 403 Forbidden"))
        assertEquals(SourceRecoveryPlanner.FailureKind.PARSER, SourceRecoveryPlanner.classify("Chapter parsing failed"))
    }

    @Test fun missingExtensionsDoNotBecomeWebsiteOutages() {
        assertEquals(SourceRecoveryPlanner.FailureKind.MISSING_EXTENSION, SourceRecoveryPlanner.classify("HTTP 503", installed = false))
        assertEquals(SourceRecoveryPlanner.FailureKind.MISSING_EXTENSION, SourceRecoveryPlanner.classify("Source unavailable or unsupported (ID: 44)"))
    }

    @Test fun removedTitleAndUnknownStayDistinct() {
        assertEquals(SourceRecoveryPlanner.FailureKind.MISSING_TITLE, SourceRecoveryPlanner.classify("HTTP 404 Not Found"))
        assertEquals(SourceRecoveryPlanner.FailureKind.UNKNOWN, SourceRecoveryPlanner.classify("Unrecognized"))
    }

    private fun coverage(
        state: SourceRecoveryPlanner.CoverageState,
        numbers: Set<Float>,
    ) = SourceRecoveryPlanner.Coverage(state = state, total = numbers.size, numbered = numbers)

    @Test fun storedCountsNeverClaimLiveChapterAvailability() {
        val first = coverage(SourceRecoveryPlanner.CoverageState.STORED_ONLY, setOf(1f, 2f, 3f))
        val other = coverage(SourceRecoveryPlanner.CoverageState.STORED_ONLY, setOf(1f, 3f, 4f))
        val preview = SourceRecoveryPlanner.compare(first, other)
        assertEquals(2, preview.sharedNumbers)
        assertEquals(1, preview.possibleAdditional)
        assertEquals(1, preview.possibleMissingFromAlternate)
        assertFalse(preview.verified)
        assertTrue(preview.summary.contains("NOT confirmed missing online"))
    }

    @Test fun onlyTwoFreshFetchedListsMakeComparisonVerified() {
        val first = coverage(SourceRecoveryPlanner.CoverageState.FETCHED, setOf(1f, 2f))
        val other = coverage(SourceRecoveryPlanner.CoverageState.FETCHED, setOf(1f, 2f, 3f))
        assertTrue(SourceRecoveryPlanner.compare(first, other).verified)
        assertFalse(SourceRecoveryPlanner.compare(first.copy(state = SourceRecoveryPlanner.CoverageState.UNKNOWN), other).verified)
    }

    @Test fun offlineExtensionCannotBeRecommendedAsReady() {
        val original = coverage(SourceRecoveryPlanner.CoverageState.STORED_ONLY, setOf(1f, 2f))
        val alternate = coverage(SourceRecoveryPlanner.CoverageState.EXTENSION_UNAVAILABLE, setOf(1f, 2f, 3f))
        assertFalse(SourceRecoveryPlanner.compare(original, alternate).canSuggest)
    }

    @Test fun mismatchedAuthorsOrLowTitlesAreRejected() {
        val author = SourceRecoveryPlanner.recommend(100, "My manga", true, 7, true)
        val lowTitle = SourceRecoveryPlanner.recommend(40, "My manga", false, 7, true)
        assertEquals(SourceRecoveryPlanner.Confidence.REJECT, author.confidence)
        assertEquals(SourceRecoveryPlanner.Confidence.REJECT, lowTitle.confidence)
        assertFalse(author.mayLink)
    }

    @Test fun shortTitlesWithoutChapterProofNeedReviewBeforeLink() {
        val result = SourceRecoveryPlanner.recommend(100, "IT", false, null, true)
        assertEquals(SourceRecoveryPlanner.Confidence.REVIEW, result.confidence)
        assertFalse(result.mayLink)
    }

    @Test fun searchChapterCountsDoNotMagicallyBecomeMatchingChapterProof() {
        val result = SourceRecoveryPlanner.recommend(100, "A Long Manga Name", false, null, true)
        assertEquals(SourceRecoveryPlanner.Confidence.REVIEW, result.confidence)
        assertTrue(result.reasons.contains("Chapter overlap not checked"))
    }

    @Test fun verifiedChapterOverlapSupportsOnlyManualRecommendation() {
        val result = SourceRecoveryPlanner.recommend(100, "A Long Manga Name", false, 3, true)
        assertEquals(SourceRecoveryPlanner.Confidence.STRONG, result.confidence)
        assertTrue(result.mayLink)
    }

    @Test fun disabledSourceMustNeverGetReplacementRecommendation() {
        assertEquals(
            SourceRecoveryPlanner.Confidence.REJECT,
            SourceRecoveryPlanner.recommend(100, "A Long Manga Name", false, 9, false).confidence,
        )
    }
}
