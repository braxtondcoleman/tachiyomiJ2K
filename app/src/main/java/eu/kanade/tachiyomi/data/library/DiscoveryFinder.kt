package eu.kanade.tachiyomi.data.library

import android.content.Context
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-demand only. Fetches popular lists from installed enabled extensions,
 * enriches a small number with public manga metadata, then ranks locally.
 * Nothing about the user's library/profile is transmitted as a search query.
 */
object DiscoveryFinder {
    data class Candidate(
        val manga: SManga,
        val source: CatalogueSource,
        val suggestion: DiscoveryPlanner.Suggestion,
        val localManga: Manga? = null,
    )

    data class Report(
        val results: List<Candidate>,
        val queriedSources: Int,
        val failedSources: Int,
        val personalizedCount: Int,
    )

    private fun info(manga: Manga, read: Int, healthy: Boolean = true) =
        DiscoveryPlanner.MangaInfo(
            manga.source,
            manga.url,
            manga.originalTitle,
            manga.originalAuthor,
            DiscoveryPlanner.genres(manga.originalGenre),
            readChapters = read,
            inLibrary = true,
            sourceHealthy = healthy,
        )

    suspend fun find(
        context: Context,
        db: DatabaseHelper,
        sourceManager: SourceManager,
        preferences: PreferencesHelper,
        seed: Manga? = null,
    ): Report = coroutineScope {
        val library = withContext(Dispatchers.IO) {
            db.getLibraryMangas().executeAsBlocking().distinctBy { it.id }
        }
        val profileEntries = library.map { info(it, it.read) }
        val profile = DiscoveryPlanner.profile(profileEntries)
        val seedInfo = seed?.let { manga ->
            info(manga, library.firstOrNull { it.id == manga.id }?.read ?: 0)
        }
        val pinned = preferences.pinnedCatalogues().get()
        val enabled = preferences.enabledLanguages().get()
        val hidden = preferences.hiddenSources().get()
        val favoredSources = library.groupingBy { it.source }.eachCount()
        val sources = sourceManager.getCatalogueSources()
            .filter { it.lang in enabled && it.id.toString() !in hidden }
            .sortedWith(
                compareByDescending<CatalogueSource> { it.id.toString() in pinned }
                    .thenByDescending { favoredSources[it.id] ?: 0 }
                    .thenBy { SourceHealthLedger.get(context, it.id)?.needsAttention == true }
                    .thenBy { it.name },
            ).take(8)

        val failed = AtomicInteger()
        val limiter = Semaphore(3)
        val popular = sources.map { source ->
            async(Dispatchers.IO) {
                limiter.withPermit {
                    try {
                        val page = withTimeoutOrNull(9_000L) { source.getPopularManga(1) }
                        if (page == null) {
                            failed.incrementAndGet()
                            emptyList()
                        } else {
                            page.mangas.take(12).mapIndexed { index, manga ->
                                Triple(source, manga, index)
                            }
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        failed.incrementAndGet()
                        emptyList()
                    }
                }
            }
        }.awaitAll().flatten()

        // Obtain public descriptive metadata for a bounded selection; generic
        // popular tiles without genres are labelled "relevance unverified".
        val enrichment = Semaphore(2)
        val hydrated = popular.mapIndexed { index, (source, manga, order) ->
            async(Dispatchers.IO) {
                if (index >= 24) return@async Triple(source, manga, order)
                enrichment.withPermit {
                    val details = try {
                        withTimeoutOrNull(7_000L) { source.getMangaDetails(manga) }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        null
                    }
                    Triple(source, details ?: manga, order)
                }
            }
        }.awaitAll()
        val knownTitles = profile.titles
        val candidates = hydrated
            .distinctBy { (source, manga, _) -> source.id to manga.url }
            .filter { (_, manga, _) -> manga.url.isNotBlank() && manga.title.isNotBlank() }
            .filterNot { (_, manga, _) ->
                DiscoveryPlanner.normalize(manga.title) in knownTitles
            }
        val items = candidates.map { (source, manga, order) ->
            val health = SourceHealthLedger.get(context, source.id)
            DiscoveryPlanner.MangaInfo(
                source.id,
                manga.url,
                manga.title,
                manga.author,
                DiscoveryPlanner.genres(manga.genre),
                inLibrary = false,
                sourceHealthy = health?.needsAttention != true,
                popularityIndex = order,
            )
        }
        val ranked = DiscoveryPlanner.recommend(profile, items, seedInfo, limit = 40)
        val byId = candidates.associateBy { (source, manga, _) -> source.id to manga.url }
        val remote = ranked.mapNotNull { suggestion ->
            val raw = byId[suggestion.manga.sourceId to suggestion.manga.url] ?: return@mapNotNull null
            Candidate(raw.second, raw.first, suggestion)
        }
        val related = if (seed != null) {
            val libItems = library.filterNot { it.id == seed.id }
                .map { manga -> info(manga, manga.read) }
            val score = DiscoveryPlanner.recommend(profile, libItems, seedInfo, limit = 12, includeOwned = true)
                .filter { it.personalized }
            score.mapNotNull { s ->
                val manga = library.firstOrNull { it.source == s.manga.sourceId && it.url == s.manga.url }
                    ?: return@mapNotNull null
                val source = sourceManager.get(manga.source) as? CatalogueSource ?: return@mapNotNull null
                Candidate(manga, source, s, manga)
            }
        } else emptyList()
        Report(
            results = (related + remote).take(50),
            queriedSources = sources.size,
            failedSources = failed.get(),
            personalizedCount = (related + remote).count { it.suggestion.personalized },
        )
    }
}
