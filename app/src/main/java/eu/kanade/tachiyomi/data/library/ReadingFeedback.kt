package eu.kanade.tachiyomi.data.library

import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Reader-defined status/reasons, never the manga's publication status. */
object ReadingFeedback {
    private const val KEY = "j2k.custom.readingFeedback.v1"

    enum class Status(val label: String) {
        READING("Reading"),
        COMPLETED("Completed"),
        DROPPED("Dropped"),
        ON_HOLD("On Hold"),
        PLAN_TO_READ("Plan to Read"),
    }

    enum class DropReason(val label: String) {
        BORING("Boring"),
        OP_SLOP("OP slop"),
        SEXUAL_VIOLENCE("Too much sexual violence"),
        BAD_WRITING("Bad writing"),
        POOR_PACING("Poor pacing"),
        CHARACTERS("Disliked characters"),
        LOST_INTEREST("Lost interest"),
    }

    data class Entry(
        val status: Status? = null,
        val reasons: Set<DropReason> = emptySet(),
        val customReason: String? = null,
    )

    fun get(manga: Manga): Entry = fromMemo(manga.memo)

    fun fromMemo(memo: JsonObject): Entry {
        val record = memo[KEY] as? JsonObject ?: return Entry()
        val name = (record["status"] as? JsonPrimitive)?.contentOrNull
        val status = Status.values().firstOrNull { it.name == name }
        val reasons = (record["reasons"] as? JsonArray).orEmpty().mapNotNull { value ->
            val reasonName = (value as? JsonPrimitive)?.contentOrNull
            DropReason.values().firstOrNull { it.name == reasonName }
        }.toSet()
        val note = (record["customReason"] as? JsonPrimitive)?.contentOrNull
            ?.trim()?.take(240)?.takeIf { it.isNotEmpty() }
        return if (status == Status.DROPPED) Entry(status, reasons, note) else Entry(status)
    }

    fun withEntry(memo: JsonObject, entry: Entry): JsonObject {
        val data = memo.toMutableMap()
        if (entry.status == null) {
            data.remove(KEY)
        } else {
            val fields = mutableMapOf<String, JsonElement>(
                "status" to JsonPrimitive(entry.status.name),
            )
            if (entry.status == Status.DROPPED) {
                fields["reasons"] = JsonArray(entry.reasons.sortedBy { it.name }.map { JsonPrimitive(it.name) })
                entry.customReason?.trim()?.take(240)?.takeIf { it.isNotEmpty() }?.let {
                    fields["customReason"] = JsonPrimitive(it)
                }
            }
            data[KEY] = JsonObject(fields)
        }
        return JsonObject(data)
    }

    /** Source refresh must not clear a user's reading feedback in the existing memo. */
    fun preserveOnRefresh(newMemo: JsonObject, previousMemo: JsonObject): JsonObject {
        if (KEY in newMemo || KEY !in previousMemo) return newMemo
        return JsonObject(newMemo.toMutableMap().apply { put(KEY, previousMemo.getValue(KEY)) })
    }

    /** Backup-covered local metadata; no chapter, tracker, category or source mutations. */
    fun save(db: DatabaseHelper, mangaId: Long, entry: Entry): Boolean {
        val manga = db.getManga(mangaId).executeAsBlocking() ?: return false
        if (!manga.favorite) return false
        manga.memo = withEntry(manga.memo, entry)
        db.updateMangaMemo(manga).executeAsBlocking()
        return true
    }
}
