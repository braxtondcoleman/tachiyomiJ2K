package eu.kanade.tachiyomi.data.library

import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    fun get(manga: Manga): List<Link> =
        (manga.memo[KEY] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val sourceId = obj["source"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val url = obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: url
            Link(sourceId, url, title)
        }.distinctBy { it.sourceId to it.url }

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
        val array = JsonArray(links.map { link ->
            JsonObject(mapOf(
                "source" to JsonPrimitive(link.sourceId),
                "url" to JsonPrimitive(link.url),
                "title" to JsonPrimitive(link.title),
            ))
        })
        manga.memo = JsonObject(manga.memo.toMutableMap().apply { put(KEY, array) })
        db.updateMangaMemo(manga).executeAsBlocking()
    }
}
