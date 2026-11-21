package eu.kanade.tachiyomi.ui.manga

import androidx.appcompat.app.AppCompatActivity
import eu.kanade.tachiyomi.data.library.AlternativeTitles
import eu.kanade.tachiyomi.data.library.MissingChapterMatcher
import eu.kanade.tachiyomi.data.library.SmartSourceFinder
import eu.kanade.tachiyomi.data.library.SmartSourceLinks
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-tap recovery from the exact "Missing N chapters" row.
 *
 * Search only locally installed, enabled source extensions. Confirm candidate identity and
 * exact numbered chapter coverage before offering a reversible source association. Never
 * install extensions, migrate progress, rewrite original chapters, or blindly link by title.
 */
object MissingChapterRepairDialog {
    private const val MAX_SOURCES = 40
    private const val MAX_NUMBERS = 30
    private const val MAX_CANDIDATES_PER_SOURCE = 4
    private const val MAX_TITLE_QUERIES = 3

    private data class Match(
        val source: CatalogueSource,
        val manga: SManga,
        val missingNumbers: List<Int>,
        val isOriginal: Boolean,
    )

    private data class Report(
        val matches: List<Match>,
        val searchedSources: Int,
        val checkedChapterLists: Int,
        val failedChecks: Int,
        val skippedSources: Int,
        val limited: Boolean,
    )

    fun show(controller: MangaDetailsController, first: Int, last: Int) {
        val activity = controller.activity as? AppCompatActivity ?: return
        if (first < 1 || last < first) return
        val requested = (first..minOf(last, first + MAX_NUMBERS - 1)).toSet()
        if (requested.isEmpty()) return

        var job: Job? = null
        val progress = activity.materialAlertDialog()
            .setTitle("Finding missing chapters")
            .setMessage("Checking installed sources for chapters ${first}–${last}. Nothing in your library will be changed.")
            .setNegativeButton("Stop") { _, _ -> job?.cancel() }
            .create()
        progress.setOnCancelListener { job?.cancel() }
        progress.show()

        job = controller.viewScope.launch {
            val report = try {
                search(controller, requested, last > requested.maxOrNull()!!)
            } catch (cancel: CancellationException) {
                progress.dismiss()
                return@launch
            } catch (error: Exception) {
                progress.dismiss()
                if (controller.activity != null) {
                    activity.materialAlertDialog()
                        .setTitle("Search couldn't finish")
                        .setMessage("The missing chapters were not changed. Try again when your source extensions are available.")
                        .setPositiveButton(android.R.string.ok, null).show()
                }
                return@launch
            }
            progress.dismiss()
            if (controller.activity == null) return@launch
            showResults(controller, requested, report)
        }
    }

    /**
     * First check the known manga URL on its original and linked sources, without
     * searching for its title. Many extensions use translated titles or don't
     * return the manga in search even though the stored URL still works.
     * Then search installed, enabled sources using a bounded set of names.
     */
    private suspend fun search(
        controller: MangaDetailsController,
        requested: Set<Int>,
        limited: Boolean,
    ): Report = coroutineScope {
        val original = controller.presenter.manga
        val db = controller.presenter.db
        val names = (listOf(original.originalTitle) +
            AlternativeTitles.fromDescription(original.originalDescription).take(4))
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(MAX_TITLE_QUERIES)
        val directLinks = (
            listOf(SmartSourceLinks.Link(original.source, original.url, original.title)) +
                SmartSourceLinks.get(original)
            ).distinctBy { it.sourceId to it.url }.take(25)
        val directIds = directLinks.map { it.sourceId }.toSet()
        val enabledLanguages = controller.presenter.preferences.enabledLanguages().get()
        val hidden = controller.presenter.preferences.hiddenSources().get()
        val pinned = controller.presenter.preferences.pinnedCatalogues().get()
        val available = controller.presenter.sourceManager.getCatalogueSources()
            .filter { source ->
                source.id in directIds ||
                    (source.lang in enabledLanguages && source.id.toString() !in hidden)
            }
            .sortedWith(
                compareBy<CatalogueSource> { if (it.id in directIds) 0 else 1 }
                    .thenByDescending { it.id.toString() in pinned }
                    .thenBy { it.name },
            )
        val skipped = (available.size - MAX_SOURCES).coerceAtLeast(0)
        val sources = available.take(MAX_SOURCES)

        data class SourceResult(
            val matches: List<Match>,
            val checkedLists: Int,
            val failedChecks: Int,
        )

        val limit = Semaphore(4)
        val probes = sources.map { source ->
            async(Dispatchers.IO) {
                limit.withPermit {
                    val seenUrls = mutableSetOf<String>()
                    val matches = mutableListOf<Match>()
                    var checkedLists = 0
                    var failedChecks = 0

                    suspend fun check(candidate: SManga) {
                        if (candidate.url.isBlank() || !seenUrls.add(candidate.url)) return
                        val chapters = try {
                            withTimeoutOrNull(10_000L) {
                                val details = if (candidate.initialized) candidate
                                    else source.getMangaDetails(candidate)
                                source.getChapterList(details)
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            null
                        }
                        if (chapters == null) {
                            failedChecks++
                            return
                        }
                        checkedLists++
                        val numbers = MissingChapterMatcher.exactNumbersFromChapters(
                            chapters.map { it.chapter_number to it.name },
                            requested,
                        )
                        if (numbers.isNotEmpty()) {
                            matches += Match(
                                source = source,
                                manga = candidate,
                                missingNumbers = numbers,
                                isOriginal = source.id == original.source && candidate.url == original.url,
                            )
                        }
                    }

                    // Confirmed links and current source are checked directly, even if
                    // the manga is not returned by the extension's search endpoint.
                    for (link in directLinks.filter { it.sourceId == source.id }) {
                        val remote = if (link.sourceId == original.source && link.url == original.url) {
                            original
                        } else {
                            db.getManga(link.url, link.sourceId).executeAsBlocking()
                                ?: SManga.create().apply {
                                    url = link.url
                                    title = link.title
                                }
                        }
                        check(remote)
                    }

                    // Search different title spellings without treating a fuzzy title
                    // match as proof: the reader must approve any new source link.
                    for (name in names) {
                        if (matches.any { it.missingNumbers.containsAll(requested) }) break
                        val page = try {
                            withTimeoutOrNull(9_000L) {
                                source.getSearchManga(1, name, FilterList())
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            null
                        }
                        if (page == null) {
                            failedChecks++
                            continue
                        }
                        val candidates = page.mangas
                            .filter { entry ->
                                entry.url.isNotBlank() && names.maxOf {
                                    SmartSourceFinder.titleScore(it, entry.title)
                                } >= 68
                            }
                            .distinctBy { it.url }
                            .take(MAX_CANDIDATES_PER_SOURCE)
                        for (candidate in candidates) {
                            check(candidate)
                        }
                    }
                    SourceResult(matches, checkedLists, failedChecks)
                }
            }
        }.awaitAll()

        Report(
            matches = probes.flatMap { it.matches }
                .distinctBy { it.source.id to it.manga.url }
                .sortedWith(compareByDescending<Match> { it.missingNumbers.size }
                    .thenBy { it.source.name }),
            searchedSources = sources.size,
            checkedChapterLists = probes.sumOf { it.checkedLists },
            failedChecks = probes.sumOf { it.failedChecks },
            skippedSources = skipped,
            limited = limited,
        )
    }

    private fun showResults(controller: MangaDetailsController, requested: Set<Int>, report: Report) {
        val activity = controller.activity as? AppCompatActivity ?: return
        val scope = if (report.limited) {
            "Only the first ${requested.size} chapters in this gap were checked.\n"
        } else {
            ""
        }
        val status = "Searched ${report.searchedSources} installed sources and verified " +
            "${report.checkedChapterLists} chapter lists." +
            (if (report.failedChecks > 0) " ${report.failedChecks} requests failed or timed out." else "") +
            (if (report.skippedSources > 0) " ${report.skippedSources} other sources weren't scanned." else "")
        if (report.matches.isEmpty()) {
            activity.materialAlertDialog()
                .setTitle("No verified matches yet")
                .setMessage(
                    scope + "The scan couldn't verify the missing chapters in the sources it checked. " +
                        "That doesn't mean they don't exist: some sites are blocked, " +
                        "use other titles, or don't expose usable chapter numbers.\n\n" + status,
                )
                .setNeutralButton("Broader source search") { _, _ ->
                    SmartSourcesDialog.search(controller)
                }
                .setPositiveButton("Done", null).show()
            return
        }
        val chaptersFound = report.matches.flatMap { it.missingNumbers }.toSet().size
        val rows: Array<CharSequence> = report.matches.map { match ->
            val label = if (match.isOriginal) "Current source · " else ""
            "${label}${match.source.name} · chapters ${match.missingNumbers.joinToString(", ")}\n${match.manga.title}"
        }.map { it as CharSequence }.toTypedArray()
        activity.materialAlertDialog()
            .setTitle("Found ${chaptersFound} chapters · ${report.checkedChapterLists} lists checked")
            .setItems(rows) { _, choice -> showConfirmation(controller, report.matches[choice]) }
            .setNeutralButton("Scan summary") { _, _ ->
                activity.materialAlertDialog().setTitle("Source scan summary")
                    .setMessage(scope + status).setPositiveButton("OK", null).show()
            }
            .setNegativeButton("Done", null).show()
    }

    private fun showConfirmation(controller: MangaDetailsController, match: Match) {
        val activity = controller.activity as? AppCompatActivity ?: return
        val original = controller.presenter.manga
        if (match.isOriginal) {
            activity.materialAlertDialog()
                .setTitle("Chapters found on your current source")
                .setMessage("The website now lists chapter(s) ${match.missingNumbers.joinToString(", ")}. Refresh this manga's chapters to retrieve them.")
                .setPositiveButton("Refresh chapters") { _, _ -> controller.presenter.refreshAll() }
                .setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        if (!original.favorite || original.id == null) {
            activity.materialAlertDialog()
                .setTitle("Add to your library first")
                .setMessage("Found chapters on ${match.source.name}. Save the manga in your library to link this source without replacing any reading progress.")
                .setPositiveButton("OK", null).show()
            return
        }

        activity.materialAlertDialog()
            .setTitle("Fill from ${match.source.name}?")
            .setMessage(
                "Found chapters ${match.missingNumbers.joinToString(", ")} on ${match.manga.title}.\n\n" +
                    "Confirm this is the same series. J2K will add a reversible alternate source " +
                    "and refresh the combined chapter list. Original chapters, downloads and progress stay intact.",
            )
            .setPositiveButton("Use matching source") { _, _ ->
                controller.viewScope.launch {
                    val id = original.id ?: return@launch
                    val memo = withContext(Dispatchers.IO) {
                        val db = controller.presenter.db
                        val current = db.getManga(id).executeAsBlocking()
                            ?: return@withContext null
                        if (!current.favorite) return@withContext null
                        val link = SmartSourceLinks.Link(match.source.id, match.manga.url, match.manga.title)
                        if (SmartSourceLinks.get(current).none { it.sourceId == link.sourceId && it.url == link.url }) {
                            SmartSourceLinks.add(db, id, link)
                        }
                        db.getManga(id).executeAsBlocking()?.memo
                    }
                    if (controller.activity == null) return@launch
                    if (memo == null) {
                        activity.materialAlertDialog()
                            .setTitle("Couldn't link source")
                            .setMessage("No changes were made. Try again from this manga.")
                            .setPositiveButton("OK", null).show()
                    } else {
                        // Refresh the in-memory manga, not just the DB. The presenter uses
                        // memo to discover confirmed alternates for its native chapter rows.
                        original.memo = memo
                        controller.presenter.enableUnifiedChapters()
                        activity.materialAlertDialog()
                            .setTitle("Searching for your chapters")
                            .setMessage("The matching source is linked. J2K is refreshing your combined chapter list; gaps it can match will fill automatically.")
                            .setPositiveButton("Done", null).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
