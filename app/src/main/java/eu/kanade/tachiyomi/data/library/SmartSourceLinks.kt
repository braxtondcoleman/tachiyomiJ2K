package eu.kanade.tachiyomi.data.library

import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Non-destructive source associations stored in the existing manga memo JSON, which
 * the J2K .tachibk backup format already includes. No new DB tables or schema changes.
 *
 * This links alternate sources for convenient navigation; it does NOT yet merge chapter
 * timelines or transfer read progress across source IDs.
 */
object SmartSourceLinks {
    private const val KEY = "j2k.custom.linkedSources.v1"
    private const val PREFERRED_KEY = "j2k.custom.preferredSource.v1"
    data class Link(val sourceId: Long, val url: String, val title: String)
    data class Preferred(val sourceId: Long, val url: String)

    /** A source preference is only a ranking hint; it never migrates chapters. */
    fun preferred(manga: Manga): Preferred? = preferredFromMemo(manga.memo)

    fun preferredFromMemo(memo: JsonObject): Preferred? {
        val obj = memo[PREFERRED_KEY] as? JsonObject ?: return null
        val id = (obj["source"] as? JsonPrimitive)?.longOrNull ?: return null
        val url = (obj["url"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: return null
        return Preferred(id, url)
    }

    /**
     * Use portable (source ID, URL) identities rather than a local manga row ID.
     * A backup can be restored on a phone where database row IDs differ.
     */
    fun get(manga: Manga): List<Link> = fromMemo(manga.memo)

    fun fromMemo(memo: JsonObject): List<Link> =
        (memo[KEY] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            // Tachiyomi source IDs are signed Longs; a negative ID may be valid.
            val sourceId = (obj["source"] as? JsonPrimitive)?.longOrNull
                ?: return@mapNotNull null
            val url = (obj["url"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = (obj["title"] as? JsonPrimitive)?.contentOrNull ?: url
            Link(sourceId, url, title)
        }.distinctBy { it.sourceId to it.url }

    /**
     * Restoring a backup over an existing manga must keep current links AND import
     * links found only in the backup. Keep unrelated memo fields from the existing
     * manga, which may contain newer extension-specific metadata.
     */
    fun mergeOnRestore(currentMemo: JsonObject, backupMemo: JsonObject): JsonObject {
        val merged = (fromMemo(currentMemo) + fromMemo(backupMemo))
            .distinctBy { it.sourceId to it.url }
        val currentPreference = preferredFromMemo(currentMemo)
        val restoredPreference = preferredFromMemo(backupMemo)
        val base = if (merged.isEmpty()) currentMemo else withLinks(currentMemo, merged)
        // Respect the current explicit choice, or import the backed-up choice
        // when no current selection exists. Never choose based on row IDs.
        return if (currentPreference == null && restoredPreference != null) {
            withPreferred(base, restoredPreference)
        } else {
            base
        }
    }

    /**
     * Reject malformed link records before restoring; unrelated/legacy metadata
     * is intentionally ignored. Missing extensions don't invalidate a backup.
     */
    fun backupLinkIssues(memo: String, ownSource: Long, ownUrl: String): List<String> {
        val json = try {
            Json.parseToJsonElement(memo) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return listOf("Manga memo is not a valid JSON object")
        val entries = json[KEY]
        if (entries == null) {
            return if (json.containsKey(PREFERRED_KEY) && preferredFromMemo(json) == null) {
                listOf("Invalid preferred-source identity")
            } else {
                emptyList()
            }
        }
        if (entries !is JsonArray) return listOf("Alternate source list is not an array")
        val seen = mutableSetOf<Pair<Long, String>>()
        val errors = mutableListOf<String>()
        entries.forEachIndexed { index, element ->
            val obj = element as? JsonObject
            val sourceId = (obj?.get("source") as? JsonPrimitive)?.longOrNull
            val url = (obj?.get("url") as? JsonPrimitive)?.contentOrNull
            if (sourceId == null || url.isNullOrBlank()) {
                errors.add("Invalid alternate-source identity at position ${index + 1}")
            } else if (!seen.add(sourceId to url)) {
                errors.add("Duplicate alternate-source identity at position ${index + 1}")
            } else if (sourceId == ownSource && url == ownUrl) {
                errors.add("Alternate source links to its own manga")
            }
        }
        if (json.containsKey(PREFERRED_KEY) && preferredFromMemo(json) == null) {
            errors.add("Invalid preferred-source identity")
        }
        return errors
    }

    /**
     * Always call before restoring any library rows. Failing before transactions
     * prevents partly-restored libraries when portable source links are corrupt.
     */
    fun requireValidBackupEntries(entries: List<BackupManga>) {
        entries.forEach { manga ->
            val issues = backupLinkIssues(manga.memo, manga.source, manga.url)
            if (issues.isNotEmpty()) {
                throw IllegalStateException(
                    "Invalid linked-source metadata for ${manga.title.take(90)}: ${issues.first()}",
                )
            }
        }
    }

    private fun withLinks(memo: JsonObject, links: List<Link>): JsonObject {
        val array = JsonArray(links.map { link ->
            JsonObject(mapOf(
                "source" to JsonPrimitive(link.sourceId),
                "url" to JsonPrimitive(link.url),
                "title" to JsonPrimitive(link.title),
            ))
        })
        return JsonObject(memo.toMutableMap().apply { put(KEY, array) })
    }

    private fun withPreferred(memo: JsonObject, preferred: Preferred?): JsonObject {
        val entries = memo.toMutableMap()
        if (preferred == null) {
            entries.remove(PREFERRED_KEY)
        } else {
            entries[PREFERRED_KEY] = JsonObject(mapOf(
                "source" to JsonPrimitive(preferred.sourceId),
                "url" to JsonPrimitive(preferred.url),
            ))
        }
        return JsonObject(entries)
    }

    /**
     * Explicitly prefer a confirmed source, or the manga's original source.
     * The preference is included in the existing memo backup and can be undone.
     */
    fun setPreferred(db: DatabaseHelper, primaryId: Long, preferred: Preferred): Boolean {
        val manga = db.getManga(primaryId).executeAsBlocking() ?: return false
        if (!manga.favorite) return false
        val original = manga.source == preferred.sourceId && manga.url == preferred.url
        val linked = get(manga).any { it.sourceId == preferred.sourceId && it.url == preferred.url }
        if (!original && !linked) return false
        if (preferredFromMemo(manga.memo) == preferred) return false
        manga.memo = withPreferred(manga.memo, preferred)
        db.updateMangaMemo(manga).executeAsBlocking()
        return true
    }

    fun add(db: DatabaseHelper, primaryId: Long, alternate: Link): Boolean {
        val primary = db.getManga(primaryId).executeAsBlocking() ?: return false
        if (!primary.favorite) return false
        if (primary.source == alternate.sourceId && primary.url == alternate.url) return false

        val old = get(primary)
        if (old.any { it.sourceId == alternate.sourceId && it.url == alternate.url }) return false
        save(db, primary, old + alternate)
        return true
    }

    fun remove(db: DatabaseHelper, primaryId: Long, alternate: Link): Boolean {
        val primary = db.getManga(primaryId).executeAsBlocking() ?: return false
        val old = get(primary)
        val next = old.filterNot { it.sourceId == alternate.sourceId && it.url == alternate.url }
        if (old.size == next.size) return false
        primary.memo = withLinks(primary.memo, next)
        if (preferredFromMemo(primary.memo) == Preferred(alternate.sourceId, alternate.url)) {
            primary.memo = withPreferred(primary.memo, null)
        }
        db.updateMangaMemo(primary).executeAsBlocking()
        return true
    }

    private fun save(db: DatabaseHelper, manga: Manga, links: List<Link>) {
        manga.memo = withLinks(manga.memo, links)
        db.updateMangaMemo(manga).executeAsBlocking()
    }
}
