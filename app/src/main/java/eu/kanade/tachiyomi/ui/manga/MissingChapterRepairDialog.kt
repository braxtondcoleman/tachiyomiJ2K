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
    private const val MAX_SOURCES = 24
    private const val MAX_NUMBERS = 30
    private const val MAX_CANDIDATES_PER_SOURCE = 3

    private data class Match(
        val source: CatalogueSource,
        val manga: SManga,
        val missingNumbers: List<Int>,
        val isOriginal: Boolean,
    )

    private data class Report(
        val matches: List<Match>,
        val checked: Int,
        val failed: Int,
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

    private suspend fun search(
        controller: MangaDetailsController,
        requested: Set<Int>,
        limited: Boolean,
    ): Report = coroutineScope {
        val original = controller.presenter.manga
        val preferredNames = listOf(original.originalTitle) +
            AlternativeTitles.fromDescription(original.originalDescription).take(8)
        val enabledLanguages = controller.presenter.preferences.enabledLanguages().get()
        val hidden = controller.presenter.preferences.hiddenSources().get()
        val sources = controller.presenter.sourceManager.getCatalogueSources()
            .asSequence()
            .filter { it.lang in enabledLanguages && it.id.toString() !in hidden }
            .sortedWith(compareBy<CatalogueSource> { if (it.id == original.source) 0 else 1 }.thenBy { it.name })
            .take(MAX_SOURCES)
            .toList()

        val limit = Semaphore(3)
        val probes = sources.map { source ->
            async(Dispatchers.IO) {
                limit.withPermit {
                    try {
                        val page = withTimeoutOrNull(10_000L) {
                            source.getSearchManga(1, original.originalTitle, FilterList())
                        } ?: return@withPermit false to emptyList<Match>()

                        val candidates = page.mangas
                            .filter { candidate ->
                                candidate.url.isNotBlank() &&
                                    preferredNames.maxOf { SmartSourceFinder.titleScore(it, candidate.title) } >= 88
                            }
                            .distinctBy { it.url }
                            .take(MAX_CANDIDATES_PER_SOURCE)

                        if (candidates.isEmpty()) return@withPermit true to emptyList<Match>()
                        var checkedAny = false
                        val found = mutableListOf<Match>()
                        candidates.forEach { candidate ->
                            try {
                                val chapters = withTimeoutOrNull(10_000L) {
                                    val details = source.getMangaDetails(candidate)
                                    source.getChapterList(details)
                                }
                                if (chapters != null) {
                                    checkedAny = true
                                    // A chapter count alone does not prove that specific missing
                                    // chapters exist. Require real numbered chapter entries.
                                    val numbers = MissingChapterMatcher.exactNumbers(
                                        chapters.map { it.chapter_number }, requested,
                                    )

                                    if (numbers.isNotEmpty()) {
                                        found += Match(
                                            source,
                                            candidate,
                                            numbers,
                                            source.id == original.source && candidate.url == original.url,
                                        )
                                    }
                                }
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                // Keep searching other candidates and independent sources.
                            }
                        }
                        checkedAny to found
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        false to emptyList<Match>()
                    }
                }
            }
        }.awaitAll()

        Report(
            matches = probes.flatMap { it.second }.sortedWith(
                compareByDescending<Match> { it.missingNumbers.size }.thenBy { it.source.name },
            ),
            checked = probes.count { it.first },
            failed = probes.count { !it.first },
            limited = limited,
        )
    }

    private fun showResults(controller: MangaDetailsController, requested: Set<Int>, report: Report) {
        val activity = controller.activity as? AppCompatActivity ?: return
        val scope = if (report.limited) "Only the first ${requested.size} chapters in this gap were checked.\n" else ""
        val status = "Checked ${report.checked} installed sources" +
            (if (report.failed > 0) "; ${report.failed} could not be checked" else "") + "."
        if (report.matches.isEmpty()) {
            activity.materialAlertDialog()
                .setTitle("No missing chapters found")
                .setMessage(scope + "None of the checked sources exposed the requested numbered chapters. " +
                    "An unnumbered chapter list or a source that failed to respond may still have them.\n\n" + status)
                .setPositiveButton("Done", null).show()
            return
        }
        val chaptersFound = report.matches.flatMap { it.missingNumbers }.toSet().size
        val rows: Array<CharSequence> = report.matches.map { match ->
            val label = if (match.isOriginal) "Current source · " else ""
            "${label}${match.source.name} · chapters ${match.missingNumbers.joinToString(", ")}\n${match.manga.title}"
        }.map { it as CharSequence }.toTypedArray()
        activity.materialAlertDialog()
            .setTitle("Found ${chaptersFound} chapters · ${report.checked} sources checked")
            .setItems(rows) { _, choice -> showConfirmation(controller, report.matches[choice]) }
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
