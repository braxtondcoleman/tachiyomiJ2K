package eu.kanade.tachiyomi.ui.manga

import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.library.SmartSourceLinks
import eu.kanade.tachiyomi.data.library.SourceHealthLedger
import eu.kanade.tachiyomi.data.library.SourceRecoveryInbox
import eu.kanade.tachiyomi.data.library.SourceRecoveryPlanner
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import androidx.appcompat.app.AppCompatActivity
import eu.kanade.tachiyomi.ui.extension.ExtensionIntelligenceDialog
import eu.kanade.tachiyomi.ui.source.BrowseController
import eu.kanade.tachiyomi.ui.source.browse.BrowseSourceController
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Recovery is user-directed: no speculative source probes, automatic extension
 * installation, deletion, migration, or cross-source reading-progress transfer.
 */
object SourceRecoveryDialog {
    private data class StoredSource(
        val sourceId: Long,
        val url: String,
        val title: String,
        val original: Boolean,
        val coverage: SourceRecoveryPlanner.Coverage,
        val warning: String? = null,
    )

    fun show(controller: MangaDetailsController) {
        val activity = controller.activity ?: return
        val opts: Array<CharSequence> = arrayOf(
            "Failure details and source health",
            "Chapter coverage & replacement preview",
            "Find possible replacement sources",
            "Retry this manga's chapter update",
            "Browse unified chapters",
            "Open source / extension manager",
            "Diagnose extension and find compatible source",
        )
        activity.materialAlertDialog()
            .setTitle("Source recovery")
            .setItems(opts) { _, choice ->
                when (choice) {
                    0 -> showStatus(controller)
                    1 -> showCoverage(controller)
                    2 -> SmartSourcesDialog.search(controller)
                    3 -> retry(controller)
                    4 -> UnifiedChaptersDialog.show(controller)
                    5 -> openSourceTools(controller)
                    6 -> showExtensionIntelligence(controller)
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showExtensionIntelligence(controller: MangaDetailsController) {
        val activity = controller.activity as? AppCompatActivity ?: return
        val sourceId = controller.presenter.manga.source
        val sourceName = controller.presenter.sourceManager.getOrStub(sourceId).name
        ExtensionIntelligenceDialog.show(activity, sourceId, sourceName) {
            controller.router.pushController(BrowseController().withFadeTransaction())
        }
    }

    private fun openSourceTools(controller: MangaDetailsController) {
        val source = controller.presenter.sourceManager.get(controller.presenter.manga.source)
        if (source is CatalogueSource) {
            controller.router.pushController(BrowseSourceController(source).withFadeTransaction())
        } else {
            // Extension discovery only; never auto-install an APK.
            controller.router.pushController(BrowseController().withFadeTransaction())
        }
    }

    private fun showStatus(controller: MangaDetailsController) {
        val activity = controller.activity ?: return
        val manga = controller.presenter.manga
        val record = SourceRecoveryInbox.forManga(activity.applicationContext, manga.source, manga.url)
        val health = SourceHealthLedger.get(activity.applicationContext, manga.source)
        val installed = controller.presenter.sourceManager.get(manga.source) != null
        val failure = record?.category ?: SourceRecoveryPlanner.FailureKind.UNKNOWN
        val summary = buildString {
            appendLine("Source: ${controller.presenter.sourceManager.getOrStub(manga.source).name}")
            appendLine("Extension: ${if (installed) "installed" else "not currently available"}")
            appendLine("Issue: ${if (!installed) SourceRecoveryPlanner.FailureKind.MISSING_EXTENSION.label else if (record?.active == true) failure.label else "No active failure recorded"}")
            if (record != null) {
                appendLine("Update attempts logged: ${record.attempts}")
                if (record.lastFailureAt > 0) appendLine("Last failure: ${DateFormat.getDateTimeInstance().format(Date(record.lastFailureAt))}")
                if (record.lastSuccessAt > 0) appendLine("Last successful check: ${DateFormat.getDateTimeInstance().format(Date(record.lastSuccessAt))}")
                appendLine("Failed attempts in a row: ${record.failureStreak}")
                if (record.active) appendLine("Last error: ${record.detail}")
                if (record.history.isNotEmpty()) appendLine("Recent checks: ${record.history.joinToString(" → ")}")
            } else {
                appendLine("No per-title recovery record yet. Run a library update to collect evidence.")
            }
            if (health != null) {
                appendLine("Whole-source history: ${health.successfulChecks} successful / ${health.failedChecks} failed title checks.")
                appendLine("Consecutive failed runs: ${health.failureStreak} (not a dead-site verdict).")
            }
            if (failure == SourceRecoveryPlanner.FailureKind.RATE_LIMIT) {
                appendLine("Rate limited: avoid repeated immediate retries and honor the site's wait period.")
            }
            appendLine()
            append("The original manga, downloads, and reading history are untouched.")
        }
        activity.materialAlertDialog()
            .setTitle("Recovery: ${manga.title}")
            .setMessage(summary)
            .setPositiveButton("Find alternatives") { _, _ -> SmartSourcesDialog.search(controller) }
            .setNeutralButton("Coverage") { _, _ -> showCoverage(controller) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun coverageFrom(chapters: List<Chapter>, installed: Boolean, lastSuccess: Long, downloaded: Int?) =
        SourceRecoveryPlanner.Coverage(
            state = when {
                !installed -> SourceRecoveryPlanner.CoverageState.EXTENSION_UNAVAILABLE
                chapters.isEmpty() -> SourceRecoveryPlanner.CoverageState.UNKNOWN
                else -> SourceRecoveryPlanner.CoverageState.STORED_ONLY
            },
            total = chapters.size,
            numbered = chapters.mapNotNull {
                it.chapter_number.takeIf { n -> n.isFinite() && n >= 1f }
            }.toSet(),
            read = chapters.count { it.read },
            bookmarked = chapters.count { it.bookmark },
            inProgress = chapters.count { it.last_page_read > 0 && !it.read },
            lastSuccessAt = lastSuccess,
            downloads = downloaded,
        )

    private fun showCoverage(controller: MangaDetailsController) {
        val activity = controller.activity ?: return
        val id = controller.presenter.manga.id ?: return
        controller.viewScope.launch {
            val stored = withContext(Dispatchers.IO) {
                val db = controller.presenter.db
                val sources = controller.presenter.sourceManager
                val downloads: DownloadManager = Injekt.get()
                val main = db.getManga(id).executeAsBlocking() ?: return@withContext emptyList<StoredSource>()
                val links = listOf(
                    SmartSourceLinks.Link(main.source, main.url, main.title),
                ) + SmartSourceLinks.get(main).take(24)
                links.distinctBy { it.sourceId to it.url }.map { link ->
                    val manga = db.getManga(link.url, link.sourceId).executeAsBlocking()
                    val chapters = manga?.let { db.getChapters(it).executeAsBlocking() }.orEmpty()
                    val health = SourceHealthLedger.get(activity.applicationContext, link.sourceId)
                    StoredSource(
                        sourceId = link.sourceId,
                        url = link.url,
                        title = link.title,
                        original = main.source == link.sourceId && main.url == link.url,
                        coverage = coverageFrom(
                            chapters,
                            sources.get(link.sourceId) != null,
                            health?.lastSuccess ?: 0,
                            manga?.let { downloads.getDownloadCount(it) },
                        ),
                    )
                }
            }
            if (stored.isEmpty()) return@launch
            presentCoverage(controller, stored)
        }
    }

    private fun presentCoverage(controller: MangaDetailsController, stored: List<StoredSource>) {
        val activity = controller.activity ?: return
        val items: Array<CharSequence> = stored.map { s ->
            val state = when (s.coverage.state) {
                SourceRecoveryPlanner.CoverageState.STORED_ONLY -> "stored snapshot"
                SourceRecoveryPlanner.CoverageState.FETCHED -> "fresh chapter list"
                SourceRecoveryPlanner.CoverageState.UNKNOWN -> "coverage unknown"
                SourceRecoveryPlanner.CoverageState.EXTENSION_UNAVAILABLE -> "extension missing"
            }
            val warning = s.warning?.let { " · $it" }.orEmpty()
            "${if (s.original) "★ Original · " else ""}${s.title}\n${s.coverage.total} chapters · $state$warning"
        }.map { it as CharSequence }.toTypedArray()
        activity.materialAlertDialog()
            .setTitle("Chapter coverage · original and linked")
            .setItems(items) { _, index ->
                preview(controller, stored.first(), stored[index])
            }
            .setPositiveButton("Check live") { _, _ -> checkLive(controller, stored) }
            .setNeutralButton("Find replacements") { _, _ -> SmartSourcesDialog.search(controller) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Explicit, bounded and cancellable current-coverage check. This does not
     * write or sync chapter rows, and does not record speculative results as
     * library-update health history. One request per confirmed linked manga.
     */
    private fun checkLive(controller: MangaDetailsController, stored: List<StoredSource>) {
        val activity = controller.activity ?: return
        var job: Job? = null
        val progress = activity.materialAlertDialog()
            .setTitle("Checking confirmed chapter lists")
            .setMessage("Checking up to 25 sources. No manga or chapter records will be changed.")
            .setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() }
            .create()
        progress.setOnCancelListener { job?.cancel() }
        progress.show()
        job = controller.viewScope.launch {
            val updated = try {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val limiter = Semaphore(3)
                        stored.map { item ->
                            async {
                                limiter.withPermit {
                                    val source = controller.presenter.sourceManager.get(item.sourceId)
                                        ?: return@withPermit item.copy(
                                            coverage = item.coverage.copy(
                                                state = SourceRecoveryPlanner.CoverageState.EXTENSION_UNAVAILABLE,
                                            ),
                                            warning = "extension unavailable",
                                        )
                                    try {
                                        val chapters = withTimeoutOrNull(12_000L) {
                                            val fromDb = controller.presenter.db
                                                .getManga(item.url, item.sourceId).executeAsBlocking()
                                            val remote = fromDb ?: SManga.create().apply {
                                                url = item.url
                                                title = item.title
                                            }
                                            val details = if (remote.initialized) remote else source.getMangaDetails(remote)
                                            source.getChapterList(details)
                                        }
                                        if (chapters == null) {
                                            item.copy(warning = "timeout; availability unknown")
                                        } else {
                                            item.copy(
                                                coverage = item.coverage.copy(
                                                    state = SourceRecoveryPlanner.CoverageState.FETCHED,
                                                    total = chapters.size,
                                                    numbered = chapters.mapNotNull { chapter ->
                                                        chapter.chapter_number.takeIf { it.isFinite() && it >= 1f }
                                                    }.toSet(),
                                                    lastSuccessAt = System.currentTimeMillis(),
                                                ),
                                                warning = null,
                                            )
                                        }
                                    } catch (cancel: CancellationException) {
                                        throw cancel
                                    } catch (error: Exception) {
                                        item.copy(
                                            warning = SourceRecoveryPlanner.classify(
                                                error.message ?: error.javaClass.simpleName,
                                            ).label,
                                        )
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
            } catch (cancel: CancellationException) {
                return@launch
            } finally {
                progress.dismiss()
            }
            if (controller.activity != null) presentCoverage(controller, updated)
        }
    }

    private fun preview(
        controller: MangaDetailsController,
        original: StoredSource,
        other: StoredSource,
    ) {
        val activity = controller.activity ?: return
        val comparison = SourceRecoveryPlanner.compare(original.coverage, other.coverage)
        val summary = buildString {
            appendLine("Original: ${original.title}")
            appendLine("Chapters (${if (original.coverage.isFresh) "fresh fetched" else "stored"}): ${original.coverage.total}; read: ${original.coverage.read}; bookmarks: ${original.coverage.bookmarked}; in progress: ${original.coverage.inProgress}; downloaded: ${original.coverage.downloads?.toString() ?: "unknown"}")
            appendLine()
            appendLine("Candidate: ${other.title}")
            appendLine("Chapters (${if (other.coverage.isFresh) "fresh fetched" else "stored"}): ${other.coverage.total}; read: ${other.coverage.read}; bookmarks: ${other.coverage.bookmarked}; in progress: ${other.coverage.inProgress}; downloaded: ${other.coverage.downloads?.toString() ?: "unknown"}")
            appendLine()
            appendLine(comparison.summary)
            appendLine("Download cache counts are advisory; downloaded files are never modified in this preview.")
            appendLine("A fetched chapter list is one moment's snapshot, not proof of series identity or future availability.")
            append("Changing preference does NOT migrate, delete, or rewrite chapters or reading progress.")
        }
        val builder = activity.materialAlertDialog()
            .setTitle("Non-destructive source switch preview")
            .setMessage(summary)
            .setNeutralButton("Unified chapters") { _, _ -> UnifiedChaptersDialog.show(controller) }
            .setNegativeButton(android.R.string.cancel, null)
        if (!other.original && comparison.canSuggest) {
            builder.setPositiveButton("Prefer source") { _, _ ->
                activity.materialAlertDialog()
                    .setTitle("Confirm source preference")
                    .setMessage("Prefer ${other.title} when choosing a linked chapter? The original source, all records and downloads remain. This is NOT a manga migration.")
                    .setPositiveButton("Save preference") { _, _ ->
                        val primaryId = controller.presenter.manga.id ?: return@setPositiveButton
                        controller.viewScope.launch {
                            val changed = withContext(Dispatchers.IO) {
                                SmartSourceLinks.setPreferred(
                                    controller.presenter.db,
                                    primaryId,
                                    SmartSourceLinks.Preferred(other.sourceId, other.url),
                                )
                            }
                            controller.activity?.materialAlertDialog()
                                ?.setMessage(if (changed) "Source preference saved. The original chapter list remains intact." else "Preference unchanged; check that this source is still linked.")
                                ?.setPositiveButton(android.R.string.ok, null)
                                ?.show()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        builder.show()
    }

    private fun retry(controller: MangaDetailsController) {
        val activity = controller.activity ?: return
        val current = controller.presenter.manga
        val record = SourceRecoveryInbox.forManga(activity.applicationContext, current.source, current.url)
        val isLimited = record?.active == true && record.category == SourceRecoveryPlanner.FailureKind.RATE_LIMIT
        activity.materialAlertDialog()
            .setTitle("Retry this title only?")
            .setMessage(
                if (isLimited) {
                    "This title was rate limited. Retrying immediately could extend the block. Wait for the site's retry window before manually trying again."
                } else {
                    "Queue only ${current.title} for a new chapter check. No other manga will be selected and no extension will be installed."
                },
            )
            .setPositiveButton(if (isLimited) "Retry anyway" else "Queue update") { _, _ ->
                controller.viewScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        val selection = controller.presenter.db.getLibraryMangas().executeAsBlocking()
                            .firstOrNull { it.id == current.id }
                        if (selection == null) {
                            "Only a favorited library manga can be retried here."
                        } else {
                            val queued = LibraryUpdateJob.startNow(
                                activity.applicationContext,
                                target = LibraryUpdateJob.Target.CHAPTERS,
                                mangaToUse = listOf(selection),
                            )
                            if (queued) "Chapter update queued." else "An update is already active; this manga may be added to the running queue."
                        }
                    }
                    controller.activity?.materialAlertDialog()
                        ?.setMessage(result)
                        ?.setPositiveButton(android.R.string.ok, null)
                        ?.show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
