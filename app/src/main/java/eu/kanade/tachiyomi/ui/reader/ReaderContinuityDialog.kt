package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.library.ReaderContinuityPlanner
import eu.kanade.tachiyomi.data.library.RecoveryCoordinator
import eu.kanade.tachiyomi.data.library.SmartSourceLinks
import eu.kanade.tachiyomi.data.library.SourceHealthLedger
import eu.kanade.tachiyomi.data.library.UnifiedChapterReadThrough
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SChapter
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
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Reader-native, user-confirmed continuity. No silent switching, progress
 * transfer, destructive chapter sync, extension installs or manga migration.
 */
object ReaderContinuityDialog {
    private const val MAX_LINKS = 24
    private const val SOURCE_TIMEOUT = 10_000L

    private data class Report(
        val primary: Manga,
        val suggestions: List<ReaderContinuityPlanner.Suggestion>,
        val warnings: List<String>,
    )

    // One automatic rescue per title+chapter per reader session. A page error
    // must not create a back-and-forth loop between two failing extensions.
    private val automaticAttempts = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun tryAutomaticSameChapter(activity: ReaderActivity) {
        val manga = activity.viewModel.manga ?: return
        val chapter = activity.viewModel.getCurrentChapter()?.chapter ?: return
        if (ReaderContinuityPlanner.number(chapter) == null) return
        val key = "${manga.title.lowercase()}|${chapter.chapter_number}"
        synchronized(automaticAttempts) {
            if (key in automaticAttempts) return
            if (automaticAttempts.size >= 200) automaticAttempts.clear()
            automaticAttempts.add(key)
        }
        activity.scope.launch {
            val report = try {
                withContext(Dispatchers.IO) {
                    find(activity, manga, chapter, ReaderContinuityPlanner.Direction.SAME_CHAPTER)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Timber.w(error, "Automatic reader source check failed")
                null
            } ?: return@launch
            if (activity.isFinishing || activity.isDestroyed) return@launch
            // Stored chapter snapshots are not proof a replacement is online.
            if (report.warnings.isNotEmpty() ||
                !RecoveryCoordinator.mayAutoSwitchSameChapter(report.suggestions)
            ) return@launch
            openConfirmed(activity, report.primary, report.suggestions.single(), automatic = true)
        }
    }

    fun showChoice(activity: ReaderActivity) {
        if (activity.viewModel.getCurrentChapter() == null) {
            activity.materialAlertDialog()
                .setMessage("Open a chapter before checking linked alternatives.")
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        activity.materialAlertDialog()
            .setTitle("Continue from linked sources")
            .setItems(arrayOf("Same chapter — try an alternate", "Next chapter on a linked source")) { _, choice ->
                show(activity, if (choice == 0) ReaderContinuityPlanner.Direction.SAME_CHAPTER else ReaderContinuityPlanner.Direction.NEXT_CHAPTER)
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    fun show(activity: ReaderActivity, direction: ReaderContinuityPlanner.Direction) {
        val current = activity.viewModel.manga ?: return
        val chapter = activity.viewModel.getCurrentChapter()?.chapter ?: return
        var job: Job? = null
        val loading = activity.materialAlertDialog()
            .setTitle("Checking confirmed sources")
            .setMessage("Only manga sources you already linked will be checked. You can cancel.")
            .setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() }
            .create()
        loading.setOnCancelListener { job?.cancel() }
        loading.show()
        job = activity.scope.launch {
            val report = try {
                withContext(Dispatchers.IO) { find(activity, current, chapter, direction) }
            } catch (cancel: CancellationException) {
                return@launch
            } catch (error: Exception) {
                Timber.w(error, "Reader continuity failed")
                loading.dismiss()
                activity.materialAlertDialog().setMessage("Could not check linked sources. Reading progress is unchanged.")
                    .setPositiveButton(android.R.string.ok, null).show()
                return@launch
            }
            loading.dismiss()
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (report == null) {
                activity.materialAlertDialog()
                    .setTitle("No confirmed parent manga")
                    .setMessage("This chapter isn't uniquely linked to a favorited manga. Confirm its links from the original library entry first.")
                    .setPositiveButton(android.R.string.ok, null).show()
                return@launch
            }
            val suggestions = report.suggestions
            if (suggestions.isEmpty()) {
                val notes = report.warnings.take(4).joinToString("\n")
                activity.materialAlertDialog()
                    .setTitle("No matching linked chapter")
                    .setMessage("No same-number or immediately following chapter was found on the confirmed sources.\n$notes\n\nNo chapter was changed.")
                    .setPositiveButton(android.R.string.ok, null).show()
                return@launch
            }
            val choices: Array<CharSequence> = suggestions.map { suggestion ->
                val c = suggestion.option
                val flags = buildString {
                    if (c.isPreferred) append(" ★ preferred")
                    if (c.needsAttention) append(" ⚠ source failures")
                    if (suggestion.needsTitleReview) append(" ? title differs")
                }
                "${c.chapter.name}\n${c.sourceName}$flags"
            }.map { it as CharSequence }.toTypedArray()
            activity.materialAlertDialog()
                .setTitle(if (direction == ReaderContinuityPlanner.Direction.NEXT_CHAPTER) "Next linked chapters" else "Same chapter on alternate sources")
                .setItems(choices) { _, index -> confirm(activity, report.primary, suggestions[index]) }
                .setNeutralButton("Source notes") { _, _ ->
                    activity.materialAlertDialog()
                        .setMessage(report.warnings.ifEmpty { listOf("Only user-confirmed sources were considered.") }.joinToString("\n"))
                        .setPositiveButton(android.R.string.ok, null).show()
                }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private suspend fun find(
        activity: ReaderActivity,
        current: Manga,
        chapter: SChapter,
        direction: ReaderContinuityPlanner.Direction,
    ): Report? = coroutineScope {
        val db: DatabaseHelper = Injekt.get()
        val sources: SourceManager = Injekt.get()
        // A Phase B alternate is nonfavorite; locate exactly one parent.
        val primary = if (current.favorite) current else {
            val matches = db.getFavoriteMangas().executeAsBlocking().filter { m ->
                SmartSourceLinks.get(m).any { it.sourceId == current.source && it.url == current.url }
            }
            matches.singleOrNull() ?: return@coroutineScope null
        }
        val preferred = SmartSourceLinks.preferred(primary)
        val links = (listOf(SmartSourceLinks.Link(primary.source, primary.url, primary.title)) +
            SmartSourceLinks.get(primary).take(MAX_LINKS))
            .distinctBy { it.sourceId to it.url }
            .filterNot { it.sourceId == current.source && it.url == current.url }
        val semaphore = Semaphore(3)
        val results = links.map { link ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val source = sources.get(link.sourceId)
                        ?: return@withPermit Pair(emptyList<ReaderContinuityPlanner.Option>(), "${link.title}: extension unavailable")
                    try {
                        val known = db.getManga(link.url, link.sourceId).executeAsBlocking()
                        val cached = known?.let { db.getChapters(it).executeAsBlocking() }.orEmpty()
                        val fetched = withTimeoutOrNull(SOURCE_TIMEOUT) {
                            val manga = known ?: SManga.create().apply {
                                url = link.url
                                title = link.title
                            }
                            source.getChapterList(if (manga.initialized) manga else source.getMangaDetails(manga))
                        }
                        val options = (fetched ?: cached).map { item ->
                            ReaderContinuityPlanner.Option(
                                sourceId = link.sourceId,
                                mangaUrl = link.url,
                                mangaTitle = link.title,
                                sourceName = source.name,
                                chapter = item,
                                isPreferred = preferred == SmartSourceLinks.Preferred(link.sourceId, link.url),
                                isOriginal = primary.source == link.sourceId && primary.url == link.url,
                                needsAttention = SourceHealthLedger.get(activity.applicationContext, link.sourceId)?.needsAttention == true,
                            )
                        }
                        Pair(options, if (fetched == null) "${link.title}: using stored chapter list, not current availability" else null)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        Timber.w(error, "Reader linked source unavailable")
                        Pair(emptyList<ReaderContinuityPlanner.Option>(), "${link.title}: unavailable or requires login")
                    }
                }
            }
        }.awaitAll()
        Report(
            primary = primary,
            suggestions = ReaderContinuityPlanner.suggest(chapter, current.source, direction, results.flatMap { it.first }),
            warnings = results.mapNotNull { it.second },
        )
    }

    private fun confirm(
        activity: ReaderActivity,
        primary: Manga,
        suggestion: ReaderContinuityPlanner.Suggestion,
    ) {
        val target = suggestion.option
        val warning = if (suggestion.needsTitleReview) "\\n\\nWARNING: chapter titles differ; confirm identity." else ""
        activity.materialAlertDialog()
            .setTitle("Open a linked chapter?")
            .setMessage(
                "${target.chapter.name}\\n${target.sourceName}$warning\\n\\n" +
                    "Read progress and downloads remain source-specific. No manga or progress is migrated.",
            )
            .setPositiveButton("Open") { _, _ ->
                openConfirmed(activity, primary, suggestion, automatic = false)
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun openConfirmed(
        activity: ReaderActivity,
        primary: Manga,
        suggestion: ReaderContinuityPlanner.Suggestion,
        automatic: Boolean,
    ) {
        val target = suggestion.option
        activity.scope.launch {
            val result = try {
                Result.success(withContext(Dispatchers.IO) {
                    val db: DatabaseHelper = Injekt.get()
                    val sources: SourceManager = Injekt.get()
                    UnifiedChapterReadThrough.resolve(
                        db, sources, requireNotNull(primary.id),
                        target.sourceId, target.mangaUrl, target.mangaTitle, target.chapter,
                    )
                })
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Result.failure(error)
            }
            result.onSuccess { openable ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    if (automatic) {
                        android.widget.Toast.makeText(
                            activity, "Resuming from alternate: ${target.sourceName}",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                    activity.openLinkedChapter(openable.manga, openable.chapter)
                }
            }.onFailure {
                if (!automatic && !activity.isFinishing && !activity.isDestroyed) {
                    activity.materialAlertDialog()
                        .setMessage("The chosen chapter couldn't be opened. Existing records remain intact.")
                        .setPositiveButton(android.R.string.ok, null).show()
                }
            }
        }
    }
}
