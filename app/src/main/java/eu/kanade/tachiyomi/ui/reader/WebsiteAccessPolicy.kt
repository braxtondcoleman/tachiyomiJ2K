package eu.kanade.tachiyomi.ui.reader

import java.net.URI
import java.util.Locale

/**
 * User-assisted Cloudflare/login verification can only open the already
 * installed source's HTTPS site. This is a navigation safeguard, not a
 * replacement for source-extension trust or server-side protection.
 */
object WebsiteAccessPolicy {
    fun host(url: String?): String? {
        val address = url?.trim() ?: return null
        return try {
            val uri = URI(address)
            if (!uri.scheme.equals("https", ignoreCase = true) ||
                !uri.userInfo.isNullOrEmpty() || (uri.port != -1 && uri.port != 443)
            ) {
                null
            } else {
                uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun sameSourceHost(baseUrl: String, candidate: String?): Boolean {
        val base = host(baseUrl) ?: return false
        val requested = host(candidate) ?: return false
        // Exact site, a subdomain of it, or the site base itself.
        return requested == base || requested.endsWith(".$base")
    }

    fun chooseTarget(baseUrl: String, chapterUrl: String?): String? = when {
        sameSourceHost(baseUrl, chapterUrl) -> chapterUrl
        host(baseUrl) != null -> baseUrl
        else -> null
    }

    fun clearance(cookieHeader: String?): String? =
        cookieHeader.orEmpty().split(';').map { it.trim() }.firstNotNullOfOrNull { part ->
            if (part.substringBefore('=', "").equals("cf_clearance", ignoreCase = true)) {
                part.substringAfter('=', "").takeIf { it.isNotBlank() }
            } else null
        }

    fun newlyCleared(before: String?, after: String?): Boolean {
        val newest = clearance(after) ?: return false
        return newest != clearance(before)
    }

    fun shouldRetryAutomatically(
        isCurrentReader: Boolean,
        userCanceled: Boolean,
        before: String?,
        after: String?,
    ): Boolean = isCurrentReader && !userCanceled && newlyCleared(before, after)
}
