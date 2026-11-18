package eu.kanade.tachiyomi.data.library

import android.content.Context

/**
 * Safe recovery controls shared by the reader and background library updates.
 * Stored separately from manga data so toggling a control never resets
 * favorites, reading progress, downloads or linked sources.
 */
object RecoveryControls {
    private const val STORE = "j2k_custom_recovery_controls"
    private const val RETRY_TEMPORARY = "retry_temporary_connections"
    private const val SHOW_VERIFICATION = "offer_website_verification"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /** Existing bounded library retry behavior remains on by default. */
    fun retryTemporaryConnections(context: Context): Boolean =
        prefs(context).getBoolean(RETRY_TEMPORARY, true)

    fun setRetryTemporaryConnections(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(RETRY_TEMPORARY, enabled).apply()
    }

    /** The normal invisible Cloudflare handshake remains part of J2K. */
    fun offerWebsiteVerification(context: Context): Boolean =
        prefs(context).getBoolean(SHOW_VERIFICATION, true)

    fun setOfferWebsiteVerification(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(SHOW_VERIFICATION, enabled).apply()
    }
}
