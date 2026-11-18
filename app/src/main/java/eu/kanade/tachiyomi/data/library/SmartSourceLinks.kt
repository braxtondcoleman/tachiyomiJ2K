package eu.kanade.tachiyomi.data.library

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
    data class Link(val sourceId: Long, val url: String, val title: String)

    /**
     * Use portable (source ID, URL) identities rather than a local manga row ID.
     * A backup can be restored on a phone where database row IDs differ.
     */
    fun get(manga: Manga): List<Link> = fromMemo(manga.memo)

    fun fromMemo(memo: JsonObject): List<Link> =
        (memo[KEY] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val sourceId = (obj["source"] as? JsonPrimitive)?.longOrNull
                ?.takeIf { it > 0 } ?: return@mapNotNull null
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
        if (merged.isEmpty()) return currentMemo
        return withLinks(currentMemo, merged)
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
        val entries = json[KEY] ?: return emptyList()
        if (entries !is JsonArray) return listOf("Alternate source list is not an array")
        val seen = mutableSetOf<Pair<Long, String>>()
        val errors = mutableListOf<String>()
        entries.forEachIndexed { index, element ->
            val obj = element as? JsonObject
            val sourceId = (obj?.get("source") as? JsonPrimitive)?.longOrNull
            val url = (obj?.get("url") as? JsonPrimitive)?.contentOrNull
            if (sourceId == null || sourceId <= 0 || url.isNullOrBlank()) {
                errors.add("Invalid alternate-source identity at position ${index + 1}")
            } else if (!seen.add(sourceId to url)) {
                errors.add("Duplicate alternate-source identity at position ${index + 1}")
            } else if (sourceId == ownSource && url == ownUrl) {
                errors.add("Alternate source links to its own manga")
            }
        }
        return errors
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
        save(db, primary, next)
        return true
    }

    private fun save(db: DatabaseHelper, manga: Manga, links: List<Link>) {
        manga.memo = withLinks(manga.memo, links)
        db.updateMangaMemo(manga).executeAsBlocking()
    }
}
