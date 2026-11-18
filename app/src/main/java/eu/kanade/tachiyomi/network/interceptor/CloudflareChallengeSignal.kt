package eu.kanade.tachiyomi.network.interceptor

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Locale

/**
 * Foreground-only UI hint emitted AFTER the app's normal, invisible
 * Cloudflare challenge attempt has failed. No CAPTCHA clicks or bypassing.
 *
 * Sends only the site host, not page URLs, headers, credentials or cookies.
 * A per-host cooldown prevents multiple preloaded pages from repeatedly
 * opening browser sheets or triggering challenge loops.
 */
object CloudflareChallengeSignal {
    data class Failure(val host: String)

    private const val COOLDOWN_MS = 120_000L
    private val lastNoticeByHost = mutableMapOf<String, Long>()
    private val mutableFailures = MutableSharedFlow<Failure>(extraBufferCapacity = 8)
    val failures = mutableFailures.asSharedFlow()

    fun isChallengeResponse(code: Int, server: String?, mitigation: String?): Boolean {
        if (code != 403 && code != 503) return false
        return server.orEmpty().contains("cloudflare", ignoreCase = true) ||
            mitigation.equals("challenge", ignoreCase = true)
    }

    @Synchronized
    fun reportFailure(host: String) {
        val normalized = host.trim().lowercase(Locale.ROOT)
        if (normalized.isBlank()) return
        val now = System.currentTimeMillis()
        val old = lastNoticeByHost[normalized] ?: 0L
        if (now - old < COOLDOWN_MS) return
        lastNoticeByHost[normalized] = now
        mutableFailures.tryEmit(Failure(normalized))
    }
}
