package eu.kanade.tachiyomi.data.library

import java.util.Locale

/**
 * Pure decision support for source recovery. No network, DB writes or migrations.
 * A failed update is evidence of a failed attempt, NOT that a website is dead.
 */
object SourceRecoveryPlanner {
    enum class FailureKind(val label: String) {
        MISSING_EXTENSION("Missing or disabled extension"),
        LOGIN("Login, access or Cloudflare challenge"),
        RATE_LIMIT("Rate limited"),
        SERVER("Temporary server problem"),
        NETWORK("Network or timeout"),
        PARSER("Extension parsing or site layout"),
        MISSING_TITLE("Title may have moved or been removed"),
        UNKNOWN("Unclassified update failure"),
    }

    fun classify(reason: String, installed: Boolean = true): FailureKind {
        if (!installed) return FailureKind.MISSING_EXTENSION
        val s = reason.lowercase(Locale.ROOT)
        return when {
            listOf("source unavailable", "extension not installed", "extension missing", "sourcenotfound", "unknown source", "no such source", "unsupported source")
                .any { it in s } -> FailureKind.MISSING_EXTENSION
            Regex("""\b429\b""").containsMatchIn(s) || "too many requests" in s ||
                "rate limit" in s || "retry-after" in s -> FailureKind.RATE_LIMIT
            listOf("cloudflare", "captcha", "login", "sign in", "unauthorized", "forbidden", "http 401", "http 403", "http 407", "status code 401", "status code 403")
                .any { it in s } -> FailureKind.LOGIN
            Regex("""(?:http|status code|response code)[^\d]{0,8}5\d\d\b""").containsMatchIn(s) ||
                Regex("""\b50[0234]\b""").containsMatchIn(s) || "server error" in s -> FailureKind.SERVER
            listOf("timeout", "timed out", "connectexception", "unknownhost", "dns", "socket", "network", "offline", "ssl", "connection reset", "unreachable")
                .any { it in s } -> FailureKind.NETWORK
            listOf("selector", "parsing", "parse error", "jsondat", "unexpected token", "element not found", "chapter parse", "html structure")
                .any { it in s } -> FailureKind.PARSER
            listOf("404", "not found", "deleted", "removed title", "manga unavailable", "series removed")
                .any { it in s } -> FailureKind.MISSING_TITLE
            else -> FailureKind.UNKNOWN
        }
    }

    enum class CoverageState { STORED_ONLY, FETCHED, UNKNOWN, EXTENSION_UNAVAILABLE }

    data class Coverage(
        val state: CoverageState,
        val total: Int,
        val numbered: Set<Float>,
        val read: Int = 0,
        val bookmarked: Int = 0,
        val inProgress: Int = 0,
        val downloads: Int? = null,
        val lastSuccessAt: Long = 0L,
    ) {
        val isFresh: Boolean get() = state == CoverageState.FETCHED
    }

    data class Comparison(
        val originalCount: Int,
        val alternateCount: Int,
        val sharedNumbers: Int,
        val possibleAdditional: Int,
        val possibleMissingFromAlternate: Int,
        val verified: Boolean,
        val canSuggest: Boolean,
        val summary: String,
    )

    fun compare(original: Coverage, alternate: Coverage): Comparison {
        val shared = original.numbered.intersect(alternate.numbered).size
        val additional = (alternate.numbered - original.numbered).size
        val missing = (original.numbered - alternate.numbered).size
        // A fresh list may still have unrecognized/special chapter numbers.
        // Call numbered coverage verified only when EVERY chapter has a unique,
        // recognized number in both fresh lists. Otherwise gaps stay tentative.
        val verified = original.isFresh && alternate.isFresh &&
            original.total > 0 && alternate.total > 0 &&
            original.numbered.size == original.total &&
            alternate.numbered.size == alternate.total
        val good = alternate.state != CoverageState.EXTENSION_UNAVAILABLE && alternate.total > 0
        return Comparison(
            originalCount = original.total,
            alternateCount = alternate.total,
            sharedNumbers = shared,
            possibleAdditional = additional,
            possibleMissingFromAlternate = missing,
            verified = verified,
            canSuggest = good,
            summary = buildString {
                append(if (verified) "Current fetched chapter lists" else "Stored or incomplete chapter snapshots")
                append("; $shared matching chapter numbers")
                append("; $additional numbers only seen on the alternate")
                append("; $missing numbers only seen on the original")
                if (!verified) append(". Presence is not verified; missing numbers are NOT confirmed missing online")
            },
        )
    }

    enum class Confidence { STRONG, REVIEW, REJECT }
    data class Recommendation(
        val confidence: Confidence,
        val reasons: List<String>,
        val mayLink: Boolean,
    )

    /**
     * A title match alone, especially a short title, cannot safely justify
     * migrating reading progress. Even STRONG is only a manual link suggestion.
     */
    fun recommend(
        titleScore: Int,
        originalTitle: String,
        conflictingAuthors: Boolean,
        matchingNumberedChapters: Int?,
        sourceAvailable: Boolean,
    ): Recommendation {
        val reasons = mutableListOf<String>()
        if (!sourceAvailable) return Recommendation(Confidence.REJECT, listOf("Extension unavailable"), false)
        if (conflictingAuthors) return Recommendation(Confidence.REJECT, listOf("Author mismatch"), false)
        if (titleScore < 60) return Recommendation(Confidence.REJECT, listOf("Unconvincing title match"), false)
        if (originalTitle.trim().length < 5 && matchingNumberedChapters == null) {
            return Recommendation(Confidence.REVIEW, listOf("Short title; chapter identity unverified"), false)
        }
        if (titleScore < 90) reasons.add("Similar rather than exact title")
        if (matchingNumberedChapters == null) reasons.add("Chapter overlap not checked")
        if (matchingNumberedChapters == 0) reasons.add("No matching chapter numbers verified")
        val strong = titleScore >= 90 && (matchingNumberedChapters ?: 0) >= 3
        if (strong) reasons.add("Title and at least three chapters overlap")
        return Recommendation(
            confidence = if (strong) Confidence.STRONG else Confidence.REVIEW,
            reasons = reasons.ifEmpty { listOf("Needs manual identity confirmation") },
            mayLink = true,
        )
    }
}
