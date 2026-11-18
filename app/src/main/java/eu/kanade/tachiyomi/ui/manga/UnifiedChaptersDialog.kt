package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import eu.kanade.tachiyomi.data.library.SmartSourceLinks
import eu.kanade.tachiyomi.data.library.SourceHealthLedger
import eu.kanade.tachiyomi.data.library.UnifiedChapterPlanner
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Opt-in unified chapter PREVIEW. No source migration, chapter row inserts,
 * progress synchronization, or changes to existing downloads.
 */
object UnifiedChaptersDialog {
    private const val MAX_LINKS = 8
    private const val MAX_ROWS = 150

    private data class Target(
        val variant: UnifiedChapterPlanner.Variant,
        val manga: Manga?,
        val chapter: Chapter?,
        val sourceName: String,
        val mangaTitle: String,
    )

    private data class Snapshot(val targets: List<Target>, val warning: String? = null)
    private data class Report(val targets: List<Target>, val warnings: List<String>, val extraLinks: Int)

    fun show(controller: MangaDetailsController) {
        val activity = controller.activity ?: return
        val id = controller.presenter.manga.id ?: return
        var job: Job? = null
        val progress = activity.materialAlertDialog()
            .setTitle("Unified chapters — preview")
            .setMessage("Loading confirmed linked sources without modifying your library.")
            .setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() }
            .create()
        progress.setOnCancelListener { job?.cancel() }
        progress.show()

        job = controller.viewScope.launch {
            val report = try {
                withContext(Dispatchers.IO) { load(controller, id, activity.applicationContext) }
            } catch (cancel: CancellationException) {
                return@launch
            } catch (error: Exception) {
                Timber.e(error, "Unified chapter preview failed")
                progress.dismiss()
                controller.activity?.materialAlertDialog()
                    ?.setTitle("Unified chapters")
                    ?.setMessage("Could not load chapter preview; the original chapters are unchanged.")
                    ?.setPositiveButton(android.R.string.ok, null)
                    ?.show()
                return@launch
            }
            progress.dismiss()
            val currentActivity = controller.activity ?: return@launch
            if (report.targets.isEmpty()) {
                currentActivity.materialAlertDialog()
                    .setTitle("Unified chapters")
                    .setMessage("No stored chapters or available linked chapters. Check your extensions and refresh the manga.")
                    .setPositiveButton(android.R.string.ok, null).show()
                return@launch
            }
            val result = UnifiedChapterPlanner.reconcile(report.targets.map { it.variant }, MAX_ROWS)
            val byId = report.targets.associateBy { it.variant.portableId }
            val summary = buildString {
                appendLine("Read-only view: ${result.matchedRows} merged chapter rows.")
                if (result.uncertainRows > 0) appendLine("${result.uncertainRows} uncertain rows kept separate.")
                if (result.possibleMissingNumbers.isNotEmpty()) {
                    appendLine("Possible numbering gaps: ${result.possibleMissingNumbers.take(10).joinToString()}")
                }
                if (report.extraLinks > 0) appendLine("${report.extraLinks} links not scanned; limit $MAX_LINKS.")
                report.warnings.take(4).forEach { appendLine("⚠ $it") }
                if (result.rows.size == MAX_ROWS) appendLine("Showing up to $MAX_ROWS rows.")
                append("Choose a row to see its original source variants.")
            }
            val choices: Array<CharSequence> = result.rows.map { row ->
                val tag = when {
                    row.ambiguous -> " ? uncertain"
                    row.isMerged -> " ✓ ${row.variants.size} sources"
                    else -> ""
                }
                val progressTag = if (row.isRead) " · read" else if (row.isBookmarked) " · bookmarked" else ""
                "${row.variants.first().chapterName}$tag$progressTag"
            }.toTypedArray()
            currentActivity.materialAlertDialog()
                .setTitle("Unified chapters — preview")
                .setMessage(summary)
                .setItems(choices) { _, index ->
                    choose(controller, id, result.rows[index], byId)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private suspend fun load(controller: MangaDetailsController, id: Long, context: Context): Report =
        supervisorScope {
            val db = controller.presenter.db
            val sources = controller.presenter.sourceManager
            val primary = db.getManga(id).executeAsBlocking()
                ?: return@supervisorScope Report(emptyList(), listOf("Manga no longer exists"), 0)
            val links = SmartSourceLinks.get(primary)
                .filterNot { it.sourceId == primary.source && it.url == primary.url }
            val preferred = SmartSourceLinks.preferred(primary)
            val primaryTargets = db.getChapters(primary).executeAsBlocking().map { chapter ->
                target(
                    chapter, primary, primary.source, primary.url, primary.title,
                    sources.getOrStub(primary.source).name, true,
                    preferred == SmartSourceLinks.Preferred(primary.source, primary.url),
                    sources.get(primary.source) != null,
                    SourceHealthLedger.get(context, primary.source)?.needsAttention == true,
                )
            }
            val semaphore = Semaphore(3)
            val snapshots = links.take(MAX_LINKS).map { link ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            val source = sources.get(link.sourceId)
                                ?: return@withPermit Snapshot(emptyList(), "${link.title}: missing extension")
                            val storedManga = db.getManga(link.url, link.sourceId).executeAsBlocking()
                            val storedChapters = storedManga?.let { db.getChapters(it).executeAsBlocking() }.orEmpty()
                            val chapters: List<SChapter> = if (storedChapters.isNotEmpty()) {
                                storedChapters
                            } else {
                                withTimeoutOrNull(10_000) {
                                    val remote = storedManga ?: SManga.create().apply {
                                        url = link.url
                                        title = link.title
                                    }
                                    source.getChapterList(remote)
                                } ?: return@withPermit Snapshot(emptyList(), "${link.title}: timed out")
                            }
                            Snapshot(chapters.map { chapter ->
                                target(
                                    chapter, storedManga, link.sourceId, link.url, link.title,
                                    source.name, false,
                                    preferred == SmartSourceLinks.Preferred(link.sourceId, link.url),
                                    true,
                                    SourceHealthLedger.get(context, link.sourceId)?.needsAttention == true,
                                )
                            })
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (error: Exception) {
                            Timber.w(error, "Unified chapter source failed: %s", link.sourceId)
                            Snapshot(emptyList(), "${link.title}: chapter list failed")
                        }
                    }
                }
            }.awaitAll()
            Report(
                targets = (primaryTargets + snapshots.flatMap { it.targets })
                    .distinctBy { it.variant.portableId },
                warnings = snapshots.mapNotNull { it.warning },
                extraLinks = (links.size - MAX_LINKS).coerceAtLeast(0),
            )
        }

    private fun target(
        chapter: SChapter,
        manga: Manga?,
        sourceId: Long,
        mangaUrl: String,
        mangaTitle: String,
        sourceName: String,
        isPrimary: Boolean,
        isPreferred: Boolean,
        installed: Boolean,
        needsAttention: Boolean,
    ): Target {
        val local = chapter as? Chapter
        return Target(
            variant = UnifiedChapterPlanner.Variant(
                sourceId = sourceId,
                mangaUrl = mangaUrl,
                chapterUrl = chapter.url,
                chapterName = chapter.name,
                chapterNumber = chapter.chapter_number,
                scanlator = chapter.scanlator,
                isPrimary = isPrimary,
                isPreferred = isPreferred,
                isAvailable = installed || (local?.id != null),
                needsAttention = needsAttention,
                read = local?.read == true,
                bookmarked = local?.bookmark == true,
                lastPageRead = local?.last_page_read ?: 0,
            ),
            manga = manga,
            chapter = local,
            sourceName = sourceName,
            mangaTitle = mangaTitle,
        )
    }

    private fun choose(
        controller: MangaDetailsController,
        mangaId: Long,
        row: UnifiedChapterPlanner.Row,
        byId: Map<Triple<Long, String, String>, Target>,
    ) {
        val activity = controller.activity ?: return
        val targets = row.variants.mapNotNull { byId[it.portableId] }
        if (targets.isEmpty()) return
        val suggested = row.recommended?.portableId
        val options: Array<CharSequence> = targets.map { target ->
            val recommended = if (target.variant.portableId == suggested) "★ Suggested · " else ""
            val health = if (target.variant.needsAttention) " · recent failures" else ""
            val route = if (target.chapter?.id != null && target.manga?.id != null) {
                " · open chapter"
            } else {
                " · open manga page"
            }
            "$recommended${target.sourceName}$health$route\\n${target.variant.chapterName}"
        }.toTypedArray()
        activity.materialAlertDialog()
            .setTitle(row.variants.first().chapterName)
            .setMessage("Choose the actual source. Reading progress stays with its original chapter.")
            .setItems(options) { _, which ->
                val selected = targets[which]
                activity.materialAlertDialog()
                    .setTitle(selected.sourceName)
                    .setMessage("Open: ${selected.variant.chapterName}\\nOr remember this source preference for this manga.")
                    .setPositiveButton("Open") { _, _ -> open(controller, selected) }
                    .setNeutralButton("Prefer source") { _, _ ->
                        controller.viewScope.launch {
                            val saved = withContext(Dispatchers.IO) {
                                SmartSourceLinks.setPreferred(
                                    controller.presenter.db,
                                    mangaId,
                                    SmartSourceLinks.Preferred(selected.variant.sourceId, selected.variant.mangaUrl),
                                )
                            }
                            if (saved) {
                                controller.activity?.materialAlertDialog()
                                    ?.setMessage("Preference saved in this manga's backup-covered metadata.")
                                    ?.setPositiveButton(android.R.string.ok, null)
                                    ?.show()
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun open(controller: MangaDetailsController, target: Target) {
        val activity = controller.activity ?: return
        if (!target.variant.isAvailable) {
            activity.materialAlertDialog().setMessage("Source unavailable; enable its extension first.")
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val manga = target.manga
        val chapter = target.chapter
        if (manga?.id != null && chapter?.id != null) {
            activity.startActivity(ReaderActivity.newIntent(activity, manga, chapter))
        } else {
            // Avoid inserting a temporary chapter just to make it readable.
            val destination = manga ?: MangaImpl().apply {
                source = target.variant.sourceId
                url = target.variant.mangaUrl
                title = target.mangaTitle
            }
            controller.router.pushController(MangaDetailsController(destination, true).withFadeTransaction())
        }
    }
}
