package eu.kanade.tachiyomi.data.library

import android.content.Context
import eu.kanade.tachiyomi.data.database.models.Manga
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Private, bounded, per-title recovery history based exclusively on real library
 * chapter-update results. No remote probes, credentials, or chapter URLs.
 *
 * Entries are keyed by the same portable (source ID, manga URL) identities as
 * linked sources; DB row IDs can change after restoring a backup.
 */
object SourceRecoveryInbox {
    data class Entry(
        val sourceId: Long,
        val mangaUrl: String,
        val title: String,
        val category: SourceRecoveryPlanner.FailureKind,
        val active: Boolean,
        val attempts: Int,
        val failureStreak: Int,
        val lastFailureAt: Long,
        val lastSuccessAt: Long,
        val detail: String,
        val history: List<String>,
    )

    private const val FILE = "j2k_custom_recovery_inbox.json"
    private const val MAX_ENTRIES = 300
    private const val MAX_HISTORY = 5
    private fun file(context: Context) = File(context.filesDir, FILE)

    @Synchronized
    fun recordRun(context: Context, checked: Map<Manga, Int>, failed: Map<Manga, String>) {
        if (checked.isEmpty() && failed.isEmpty()) return
        val store = readJson(context)
        val current = store.optJSONArray("entries") ?: JSONArray()
        val entries = (0 until current.length()).mapNotNull { current.optJSONObject(it) }.toMutableList()
        val now = System.currentTimeMillis()
        // A failure in this run wins over a success when an entry appears in both.
        val failedIds = failed.keys.map { it.source to it.url }.toSet()
        fun entryFor(manga: Manga): JSONObject {
            val existing = entries.firstOrNull {
                it.optLong("source") == manga.source && it.optString("url") == manga.url
            }
            if (existing != null) return existing
            return JSONObject().apply {
                put("source", manga.source)
                put("url", manga.url)
                put("title", manga.title.replace('\n', ' ').take(150))
                put("attempts", 0)
                put("streak", 0)
                put("active", false)
            }.also { entries.add(it) }
        }
        // Use the actual success results; never assume a failed source recovered.
        checked.keys.filterNot { (it.source to it.url) in failedIds }.forEach { manga ->
            entryFor(manga).apply {
                put("title", manga.title.replace('\n', ' ').take(150))
                put("attempts", optInt("attempts") + 1)
                put("streak", 0)
                put("lastSuccess", now)
                put("active", false)
                val recent = optJSONArray("history") ?: JSONArray()
                val next = JSONArray().put("OK")
                for (i in 0 until minOf(recent.length(), MAX_HISTORY - 1)) next.put(recent.optString(i))
                put("history", next)
            }
        }
        failed.forEach { (manga, error) ->
            val category = RecoveryCoordinator.plan(
                RecoveryCoordinator.Origin.LIBRARY, error,
            ).failure
            entryFor(manga).apply {
                put("title", manga.title.replace('\n', ' ').take(150))
                put("attempts", optInt("attempts") + 1)
                put("streak", optInt("streak") + 1)
                put("lastFailure", now)
                put("active", true)
                put("kind", category.name)
                // Do not persist raw request URLs, query tokens or headers.
                put("detail", cleanReason(error))
                val recent = optJSONArray("history") ?: JSONArray()
                val next = JSONArray().put(category.name)
                for (i in 0 until minOf(recent.length(), MAX_HISTORY - 1)) next.put(recent.optString(i))
                put("history", next)
            }
        }
        val kept = entries.sortedByDescending {
            maxOf(it.optLong("lastFailure"), it.optLong("lastSuccess"))
        }.take(MAX_ENTRIES)
        try {
            val staging = File(context.filesDir, "$FILE.tmp")
            staging.writeText(JSONObject().put("entries", JSONArray(kept)).toString())
            if (!staging.renameTo(file(context))) {
                staging.copyTo(file(context), overwrite = true)
                staging.delete()
            }
        } catch (e: Exception) {
            // Recovery history should not interrupt a normal library update.
            Timber.w(e, "Could not save source recovery inbox")
        }
    }

    private fun cleanReason(raw: String): String =
        raw.replace(Regex("""https?://\S+""", RegexOption.IGNORE_CASE), "[address withheld]")
            .replace(Regex("""(?i)(token|api[_-]?key|password|cookie|authorization)[=: ]+\S+"""), "[secret withheld]")
            .replace(Regex("""\s+"""), " ").trim().take(200)

    @Synchronized
    fun list(context: Context, includeResolved: Boolean = false): List<Entry> {
        val array = readJson(context).optJSONArray("entries") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val o = array.optJSONObject(index) ?: return@mapNotNull null
            val source = o.optLong("source")
            val url = o.optString("url")
            if (url.isBlank()) return@mapNotNull null
            val active = o.optBoolean("active", false)
            if (!includeResolved && !active) return@mapNotNull null
            val category = runCatching {
                SourceRecoveryPlanner.FailureKind.valueOf(o.optString("kind"))
            }.getOrDefault(SourceRecoveryPlanner.FailureKind.UNKNOWN)
            val historyArray = o.optJSONArray("history")
            Entry(
                sourceId = source,
                mangaUrl = url,
                title = o.optString("title"),
                category = category,
                active = active,
                attempts = o.optInt("attempts"),
                failureStreak = o.optInt("streak"),
                lastFailureAt = o.optLong("lastFailure"),
                lastSuccessAt = o.optLong("lastSuccess"),
                detail = o.optString("detail"),
                history = if (historyArray == null) emptyList() else
                    (0 until historyArray.length()).map { historyArray.optString(it) },
            )
        }.sortedWith(compareByDescending<Entry> { it.active }.thenByDescending { it.lastFailureAt })
    }

    fun forManga(context: Context, sourceId: Long, mangaUrl: String): Entry? =
        list(context, includeResolved = true).firstOrNull {
            it.sourceId == sourceId && it.mangaUrl == mangaUrl
        }

    private fun readJson(context: Context): JSONObject = try {
        JSONObject(file(context).takeIf { it.isFile }?.readText() ?: "{}")
    } catch (e: Exception) {
        Timber.w(e, "Recovery inbox was unreadable")
        JSONObject()
    }
}
