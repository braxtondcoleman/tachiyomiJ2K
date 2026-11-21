package eu.kanade.tachiyomi.data.library

import android.content.Context
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.FilterList
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
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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

    private data class SourceCandidate(
        val source: CatalogueSource,
        val manga: SManga,
        val order: Int,
        val malVotes: Int = 0,
    )

    private fun info(manga: Manga, read: Int, healthy: Boolean = true): DiscoveryPlanner.MangaInfo {
        val feedback = ReadingFeedback.get(manga)
        return DiscoveryPlanner.MangaInfo(
            manga.source,
            manga.url,
            manga.originalTitle,
            manga.originalAuthor,
            DiscoveryPlanner.genres(manga.originalGenre),
            readChapters = read,
            inLibrary = true,
            sourceHealthy = healthy,
            feedbackStatus = feedback.status,
            dropReasons = feedback.reasons,
        )
    }

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

        // MAL recommends titles related to a particular manga, not to the whole
        // account. Use at most two explicitly linked library entries as seeds.
        val malSeeds = if (seed != null) listOf(seed) else
            library.sortedByDescending { it.read }.take(6)
        val malService = Injekt.get<TrackManager>().myAnimeList
        val malIds = if (malService.isLogged) withContext(Dispatchers.IO) {
            malSeeds.mapNotNull { manga ->
                db.getTracks(manga).executeAsBlocking()
                    .firstOrNull { it.sync_id == TrackManager.MYANIMELIST }?.media_id
            }.distinct().take(2)
        } else emptyList()
        val recommendations = malIds.map { id ->
            async(Dispatchers.IO) {
                try {
                    withTimeoutOrNull(10_000) { malService.recommendationsFor(id) }.orEmpty()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    Timber.w(error, "MAL recommendations unavailable for a tracked title")
                    emptyList()
                }
            }
        }.awaitAll().flatten().distinctBy { it.mediaId }
            .sortedByDescending { it.votes }.take(6)

        // Title searches are on installed sources only; never import an unknown
        // manga as an alternate link or install an extension.
        val malLimiter = Semaphore(3)
        val malFound = recommendations.flatMap { recommendation ->
            sources.take(2).map { source ->
                async(Dispatchers.IO) {
                    malLimiter.withPermit {
                        try {
                            withTimeoutOrNull(8_000) {
                                source.getSearchManga(1, recommendation.title, FilterList())
                            }?.mangas.orEmpty()
                                .filter {
                                    it.url.isNotBlank() &&
                                        SmartSourceFinder.titleScore(recommendation.title, it.title) >= 90
                                }.take(1).map {
                                    SourceCandidate(source, it, 0, maxOf(1, recommendation.votes))
                                }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (error: Exception) {
                            Timber.w(error, "MAL candidate search failed for %s", source.name)
                            emptyList()
                        }
                    }
                }
            }
        }.awaitAll().flatten()

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
                                SourceCandidate(source, manga, index)
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
        val hydrated = (malFound + popular).distinctBy { it.source.id to it.manga.url }
            .mapIndexed { index, candidate ->
                async(Dispatchers.IO) {
                    if (index >= 24) return@async candidate
                    enrichment.withPermit {
                        val details = try {
                            withTimeoutOrNull(7_000L) { candidate.source.getMangaDetails(candidate.manga) }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            null
                        }
                        candidate.copy(manga = details ?: candidate.manga)
                    }
                }
            }.awaitAll()
        val knownTitles = profile.titles
        val candidates = hydrated
            .distinctBy { it.source.id to it.manga.url }
            .filter { it.manga.url.isNotBlank() && it.manga.title.isNotBlank() }
            .filterNot { DiscoveryPlanner.normalize(it.manga.title) in knownTitles }
        val items = candidates.map { candidate ->
            val source = candidate.source
            val manga = candidate.manga
            val health = SourceHealthLedger.get(context, source.id)
            DiscoveryPlanner.MangaInfo(
                source.id,
                manga.url,
                manga.title,
                manga.author,
                DiscoveryPlanner.genres(manga.genre),
                inLibrary = false,
                sourceHealthy = health?.needsAttention != true,
                popularityIndex = candidate.order,
                malRecommendationVotes = candidate.malVotes,
            )
        }
        val ranked = DiscoveryPlanner.recommend(profile, items, seedInfo, limit = 40)
        val byId = candidates.associateBy { it.source.id to it.manga.url }
        val remote = ranked.mapNotNull { suggestion ->
            val raw = byId[suggestion.manga.sourceId to suggestion.manga.url] ?: return@mapNotNull null
            Candidate(raw.manga, raw.source, suggestion)
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
