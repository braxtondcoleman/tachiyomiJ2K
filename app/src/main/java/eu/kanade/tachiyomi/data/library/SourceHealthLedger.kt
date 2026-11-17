package eu.kanade.tachiyomi.data.library

import android.content.Context
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Health is inferred from actual chapter checks, never from speculative reachability probes.
 * Failures don't establish that a site is dead: login, rate limits, and network conditions vary.
 * No credentials or chapter URLs are stored.
 */
object SourceHealthLedger {
    data class Status(
        val successfulChecks: Int,
        val failedChecks: Int,
        val failureStreak: Int,
        val lastSuccess: Long,
        val lastFailure: Long,
        val lastError: String,
    ) {
        val needsAttention get() = failureStreak >= 2
    }

    private const val FILE_NAME = "j2k_custom_source_health.json"
    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun get(context: Context, sourceId: Long): Status? =
        read(context).optJSONObject(sourceId.toString())?.toStatus()

    @Synchronized
    fun recordRun(context: Context, checked: Map<Manga, Int>, failures: Map<Manga, String>) {
        val checkedBySource = checked.keys.groupingBy { it.source }.eachCount()
        val failedBySource = failures.keys.groupingBy { it.source }.eachCount()
        val sourceIds = checkedBySource.keys + failedBySource.keys
        if (sourceIds.isEmpty()) return

        val document = read(context)
        val now = System.currentTimeMillis()
        sourceIds.forEach { id ->
            val successes = checkedBySource[id] ?: 0
            val errors = failedBySource[id] ?: 0
            val previous = document.optJSONObject(id.toString())?.toStatus()
                ?: Status(0, 0, 0, 0, 0, "")
            val mostRecentError = failures.entries.firstOrNull { it.key.source == id }?.value
                ?.replace('\n', ' ')?.take(160).orEmpty()
            val status = Status(
                successfulChecks = previous.successfulChecks + successes,
                failedChecks = previous.failedChecks + errors,
                failureStreak = when {
                    successes > 0 -> 0
                    errors > 0 -> previous.failureStreak + 1
                    else -> previous.failureStreak
                },
                lastSuccess = if (successes > 0) now else previous.lastSuccess,
                lastFailure = if (errors > 0) now else previous.lastFailure,
                lastError = if (errors > 0) mostRecentError else previous.lastError,
            )
            document.put(id.toString(), JSONObject().apply {
                put("ok", status.successfulChecks)
                put("failed", status.failedChecks)
                put("streak", status.failureStreak)
                put("okAt", status.lastSuccess)
                put("failedAt", status.lastFailure)
                put("error", status.lastError)
            })
        }
        try {
            val staging = File(context.filesDir, "$FILE_NAME.tmp")
            staging.writeText(document.toString())
            if (!staging.renameTo(file(context))) {
                staging.copyTo(file(context), overwrite = true)
                staging.delete()
            }
        } catch (e: Exception) {
            Timber.e(e, "Could not save source health")
        }
    }

    fun report(context: Context, sourceManager: SourceManager): String {
        val document = read(context)
        val sourceIds = document.keys().asSequence().mapNotNull { it.toLongOrNull() }.toList()
        if (sourceIds.isEmpty()) return "No source health records yet. Run a library update first."
        return buildString {
            appendLine("Source health based on chapter-update results")
            appendLine("A failed update does not necessarily mean a source is offline.")
            appendLine()
            sourceIds.sortedWith(compareBy({ sourceManager.get(it) != null }, { sourceManager.getOrStub(it).name })).forEach { id ->
                val status = document.optJSONObject(id.toString())?.toStatus() ?: return@forEach
                val source = sourceManager.get(id)
                val label = when {
                    source == null -> "MISSING EXTENSION"
                    status.needsAttention -> "NEEDS ATTENTION"
                    status.lastSuccess > 0 && status.lastSuccess >= status.lastFailure -> "WORKING"
                    status.lastFailure > status.lastSuccess -> "RECENT FAILURE"
                    else -> "UNKNOWN"
                }
                appendLine("[$label] ${sourceManager.getOrStub(id).name}")
                appendLine("  Successful checks: ${status.successfulChecks}, failed: ${status.failedChecks}, failed runs in a row: ${status.failureStreak}")
                if (status.lastError.isNotBlank() && label != "WORKING") appendLine("  Last error: ${status.lastError}")
                appendLine()
            }
        }
    }

    private fun JSONObject.toStatus(): Status = Status(
        successfulChecks = optInt("ok"),
        failedChecks = optInt("failed"),
        failureStreak = optInt("streak"),
        lastSuccess = optLong("okAt"),
        lastFailure = optLong("failedAt"),
        lastError = optString("error"),
    )

    private fun read(context: Context): JSONObject = try {
        JSONObject(file(context).takeIf { it.isFile }?.readText() ?: "{}")
    } catch (e: Exception) {
        Timber.w(e, "Source health history was unreadable")
        JSONObject()
    }
}
