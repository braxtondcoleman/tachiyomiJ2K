package eu.kanade.tachiyomi.data.library

import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import java.util.Date

/**
 * Explicit, isolated "read this alternate chapter" bridge into the native reader.
 *
 * ReaderActivity requires a persisted Manga and Chapter ID. This creates *only*
 * the selected non-favorite alternate and chapter if they do not already exist.
 * It never invokes chapter synchronization (which may delete stale chapters),
 * never touches the primary manga or its progress, and never marks anything read.
 * The native reader owns source-specific reading progress after opening.
 */
object UnifiedChapterReadThrough {
    data class Openable(val manga: Manga, val chapter: Chapter)

    @Synchronized
    fun resolve(
        db: DatabaseHelper,
        sources: SourceManager,
        primaryId: Long,
        sourceId: Long,
        mangaUrl: String,
        mangaTitle: String,
        selected: SChapter,
    ): Openable {
        require(mangaUrl.isNotBlank() && selected.url.isNotBlank()) {
            "Cannot open a chapter without a portable source identity"
        }
        val primary = db.getManga(primaryId).executeAsBlocking()
            ?: error("Original library entry no longer exists")
        val isOriginal = primary.source == sourceId && primary.url == mangaUrl
        val confirmed = SmartSourceLinks.get(primary).any { it.sourceId == sourceId && it.url == mangaUrl }
        require(isOriginal || confirmed) { "The alternate source is no longer linked to this manga" }
        val source = sources.get(sourceId) ?: error("The source extension is missing or disabled")
        return db.inTransactionReturn {
            val manga = db.getManga(mangaUrl, sourceId).executeAsBlocking()
                ?: MangaImpl().apply {
                    source = sourceId
                    url = mangaUrl
                    title = mangaTitle
                    favorite = false
                    initialized = true
                }.also { created ->
                    val inserted = db.insertManga(created).executeAsBlocking().insertedId()
                    require(inserted != null && inserted > 0L) { "Could not store selected source manga" }
                    created.id = inserted
                }
            val mangaId = requireNotNull(manga.id) { "Source manga has no local identity" }
            val chapter = db.getChapter(selected.url, mangaId).executeAsBlocking()
                ?: selected.toChapter().apply {
                    manga_id = mangaId
                    date_fetch = Date().time
                    source_order = 0
                    memo = selected.memo
                    // This is a fresh row. Never copy read/bookmark/page state from
                    // another source, even if the chapter number matches.
                    read = false
                    bookmark = false
                    last_page_read = 0
                    pages_left = 0
                    if (source is HttpSource) source.prepareNewChapter(this, manga)
                }.also { created ->
                    val inserted = db.insertChapter(created).executeAsBlocking().insertedId()
                    require(inserted != null && inserted > 0L) { "Could not store selected source chapter" }
                    created.id = inserted
                }
            Openable(manga, chapter)
        }
    }
}
