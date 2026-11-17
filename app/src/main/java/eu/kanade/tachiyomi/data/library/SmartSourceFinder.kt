package eu.kanade.tachiyomi.data.library

import android.content.Context
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * SY-style cross-source search with ranking. It only queries already installed/enabled
 * sources, never installs extensions, moves chapters, or automatically changes the library.
 * Title/author matching is a heuristic, not proof that two results are the same series.
 */
object SmartSourceFinder {
    data class Candidate(
        val source: CatalogueSource,
        val manga: SManga,
        val score: Int,
        val chapterCount: Int?,
        val note: String,
    )

    data class Report(
        val candidates: List<Candidate>,
        val searched: Int,
        val failures: Int,
    )

    suspend fun find(
        context: Context,
        original: Manga,
        sourceManager: SourceManager,
        preferences: PreferencesHelper,
        db: DatabaseHelper,
    ): Report = coroutineScope {
        val enabledLang = preferences.enabledLanguages().get()
        val pinned = preferences.pinnedCatalogues().get()
        val hidden = preferences.hiddenSources().get()
        val sources =
            sourceManager.getCatalogueSources()
                .asSequence()
                .filter { it.id != original.source }
                .filter { it.lang in enabledLang }
                .filter { it.id.toString() !in hidden }
                .sortedWith(
                    compareByDescending<CatalogueSource> { it.id.toString() in pinned }
                        .thenBy { SourceHealthLedger.get(context, it.id)?.needsAttention ?: false }
                        .thenBy { it.name },
                )
                .take(24)
                .toList()

        val knownLibrary = withContext(Dispatchers.IO) {
            db.getLibraryMangas().executeAsBlocking()
                .distinctBy { it.id }
                .associateBy { it.source to it.url }
        }
        val originalCount = withContext(Dispatchers.IO) { db.getChapters(original).executeAsBlocking().size }
        val sem = Semaphore(4)
        val failures = java.util.concurrent.atomic.AtomicInteger(0)

        val found = sources.map { source ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    try {
                        val page = withTimeoutOrNull(10_000) {
                            source.getSearchManga(1, original.originalTitle, FilterList())
                        }
                        if (page == null) {
                            failures.incrementAndGet()
                            return@withPermit emptyList()
                        }
                        page.mangas.take(12).mapNotNull { entry ->
                            val matchScore = titleScore(original.originalTitle, entry.title)
                            if (matchScore < 48 || entry.url.isBlank()) return@mapNotNull null

                            val authorScore =
                                if (original.originalAuthor != null && entry.author != null) {
                                    if (titleScore(original.originalAuthor!!, entry.author!!) >= 75) 12 else -14
                                } else {
                                    0
                                }
                            val health = SourceHealthLedger.get(context, source.id)
                            val healthScore = when {
                                health?.needsAttention == true -> -30
                                health != null && health.lastSuccess > health.lastFailure -> 8
                                health != null && health.lastFailure > health.lastSuccess -> -12
                                else -> 0
                            }
                            val known = knownLibrary[source.id to entry.url]
                            val knownScore = if (known != null) 8 else 0
                            val priorityScore = if (source.id.toString() in pinned) 6 else 0
                            val score = matchScore + authorScore + healthScore + knownScore + priorityScore
                            Candidate(
                                source,
                                entry,
                                score,
                                known?.totalChapters,
                                buildString {
                                    append(if (matchScore >= 90) "Exact title" else "Similar title")
                                    if (health?.needsAttention == true) append("; recent update failures")
                                    if (known != null) append("; already in library")
                                },
                            )
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        failures.incrementAndGet()
                        emptyList()
                    }
                }
            }
        }.awaitAll().flatten()
            .distinctBy { Triple(it.source.id, it.manga.url, it.manga.title) }
            .sortedByDescending { it.score }
            .take(12)

        // Verify chapter coverage for only the most promising results. Keep failures separate:
        // some extensions require a login to list chapters but can still be discovered.
        val chaptersSemaphore = Semaphore(2)
        val enriched = found.mapIndexed { index, candidate ->
            async(Dispatchers.IO) {
                if (index >= 6) return@async candidate
                chaptersSemaphore.withPermit {
                    val count = try {
                        withTimeoutOrNull(8_000) {
                            val details = candidate.source.getMangaDetails(candidate.manga)
                            candidate.source.getChapterList(details).size
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        null
                    }
                    if (count == null) candidate else {
                        val coverageScore = when {
                            count == 0 -> -15
                            originalCount > 0 && count < originalCount / 2 -> -10
                            originalCount > 0 && count >= originalCount -> 10
                            count >= 20 -> 5
                            else -> 0
                        }
                        candidate.copy(
                            score = candidate.score + coverageScore,
                            chapterCount = count,
                            note = candidate.note + "; $count chapters",
                        )
                    }
                }
            }
        }.awaitAll().sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.source.name })

        Report(enriched, sources.size, failures.get())
    }

    /**
     * Strong on exact/near-exact titles; ambiguous, short, or unrelated matches score poorly.
     * A verified author match and chapter coverage are considered by the caller.
     */
    fun titleScore(expected: String, candidate: String): Int {
        fun normalized(value: String): String =
            value.lowercase(Locale.ROOT)
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()
                .replace(Regex("\\s+"), " ")
        val a = normalized(expected)
        val b = normalized(candidate)
        if (a.isBlank() || b.isBlank()) return 0
        if (a == b || a.replace(" ", "") == b.replace(" ", "")) return 100
        val at = a.split(" ").toSet()
        val bt = b.split(" ").toSet()
        val overlap = at.intersect(bt).size
        val union = at.union(bt).size
        val tokens = if (union == 0) 0 else (overlap * 88 / union)
        val containment = if (a.length >= 6 && b.length >= 6 && (a in b || b in a)) 74 else 0
        return maxOf(tokens, containment)
    }
}
