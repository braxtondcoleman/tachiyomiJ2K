package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import android.widget.Toast
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import eu.kanade.tachiyomi.data.library.SmartSourceLinks
import eu.kanade.tachiyomi.data.library.SourceHealthLedger
import eu.kanade.tachiyomi.data.library.UnifiedChapterPlanner
import eu.kanade.tachiyomi.data.library.UnifiedChapterReadThrough
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.manga.chapter.ChapterItem
import eu.kanade.tachiyomi.util.system.materialAlertDialog
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
    private const val MAX_LINKS = 24
    private const val PAGE_SIZE = 150
    private const val MAX_INDEXED_ROWS = 10000

    internal data class Target(
        val variant: UnifiedChapterPlanner.Variant,
        val manga: Manga?,
        val chapter: Chapter?,
        val rawChapter: SChapter,
        val sourceName: String,
        val mangaTitle: String,
    )

    private data class Snapshot(val targets: List<Target>, val warning: String? = null)
    internal data class Report(val targets: List<Target>, val warnings: List<String>, val extraLinks: Int)

    /** A source-aware logical chapter row shown by the normal native chapter adapter. */
    internal data class NativeInfo(
        val row: UnifiedChapterPlanner.Row,
        val targets: List<Target>,
        val chosen: Target,
    )

    /** One logical row per confirmed equivalent chapter, including uncached remote chapters. */
    internal suspend fun nativeChapters(controller: MangaDetailsController): List<ChapterItem> {
        val primary = controller.presenter.manga
        val id = primary.id ?: return emptyList()
        val report = load(controller, id, controller.presenter.preferences.context)
        val result = UnifiedChapterPlanner.reconcile(report.targets.map { it.variant }, MAX_INDEXED_ROWS)
        val targets = report.targets.associateBy { it.variant.portableId }

        return result.rows.mapNotNull { row ->
            val selected = (row.recommended?.let { targets[it.portableId] }
                ?: row.variants.firstNotNullOfOrNull { targets[it.portableId] })
                ?: return@mapNotNull null
            val stored = selected.chapter
            // The native adapter needs a stable row ID, but remote candidates are
            // NEVER inserted into the database just to display a chapter.
            val displayed = stored ?: selected.rawChapter.toChapter().apply {
                val key = listOf(selected.variant.sourceId, selected.variant.mangaUrl, selected.variant.chapterUrl)
                    .joinToString("|")
                id = -(key.hashCode().toLong() and 0x7fffffffL) - 1L
                manga_id = selected.manga?.id ?: primary.id
            }
            ChapterItem(displayed, selected.manga ?: primary).also { item ->
                item.unifiedInfo = NativeInfo(
                    row,
                    row.variants.mapNotNull { targets[it.portableId] },
                    selected,
                )
                item.isLocked = controller.presenter.isLockedFromSearch
            }
        }
    }

    /** Normal tap opens the selected, source-correct chapter without a second dialog. */
    internal fun openNative(controller: MangaDetailsController, item: ChapterItem) {
        val info = item.unifiedInfo ?: return
        val source = info.chosen
        val notice = if (source.variant.isPrimary) null else {
            val primaryListed = info.row.variants.any { it.isPrimary }
            if (primaryListed) "Loading from alternate: ${source.sourceName}"
            else "Not listed on primary · loading ${source.sourceName}"
        }
        open(controller, source, notice)
    }

    /** Long press exposes versions without forcing a separate unified browser. */
    internal fun chooseNative(controller: MangaDetailsController, item: ChapterItem) {
        val info = item.unifiedInfo ?: return
        val id = controller.presenter.manga.id ?: return
        choose(controller, id, info.row, info.targets.associateBy { it.variant.portableId })
    }

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
            val result = UnifiedChapterPlanner.reconcile(report.targets.map { it.variant }, MAX_INDEXED_ROWS)
            val byId = report.targets.associateBy { it.variant.portableId }
            showPage(controller, id, report, result, byId, 0)
        }
    }

    private fun showPage(
        controller: MangaDetailsController,
        mangaId: Long,
        report: Report,
        result: UnifiedChapterPlanner.Result,
        byId: Map<Triple<Long, String, String>, Target>,
        requestedPage: Int,
    ) {
        val activity = controller.activity ?: return
        val lastPage = ((result.rows.size - 1).coerceAtLeast(0) / PAGE_SIZE)
        val page = requestedPage.coerceIn(0, lastPage)
        val start = page * PAGE_SIZE
        val currentRows = result.rows.drop(start).take(PAGE_SIZE)
        val summary = buildString {
            appendLine("Unified chapters: ${result.matchedRows} matched rows; page ${page + 1} of ${lastPage + 1}.")
            if (result.uncertainRows > 0) appendLine("${result.uncertainRows} uncertain rows kept separate.")
            if (result.possibleMissingNumbers.isNotEmpty()) {
                appendLine("Possible numbering gaps: ${result.possibleMissingNumbers.take(10).joinToString()}")
            }
            if (report.extraLinks > 0) appendLine("${report.extraLinks} linked sources not scanned (limit $MAX_LINKS).")
            report.warnings.take(4).forEach { appendLine("⚠ $it") }
            if (result.rows.size == MAX_INDEXED_ROWS) appendLine("First $MAX_INDEXED_ROWS rows indexed.")
            append("Select a chapter to choose its actual source; changes to progress stay source-specific.")
        }
        val options: Array<CharSequence> = (
            currentRows.map { row ->
                val state = when {
                    row.ambiguous -> " ? uncertain"
                    row.isMerged -> " ✓ ${row.variants.size} sources"
                    else -> ""
                }
                val progress = if (row.isRead) " · read" else if (row.isBookmarked) " · bookmarked" else ""
                "${row.variants.first().chapterName}$state$progress"
            } + "ⓘ Coverage summary, source errors and gaps"
        ).map { it as CharSequence }.toTypedArray()
        val dialog = activity.materialAlertDialog()
            .setTitle("Unified chapters · ${page + 1}/${lastPage + 1}")
            .setItems(options) { _, position ->
                if (position == currentRows.size) {
                    activity.materialAlertDialog()
                        .setTitle("Unified chapter coverage")
                        .setMessage(summary)
                        .setPositiveButton("Back to chapters") { _, _ ->
                            showPage(controller, mangaId, report, result, byId, page)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    choose(controller, mangaId, currentRows[position], byId)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (page < lastPage) {
            dialog.setNeutralButton("Older") { _, _ ->
                showPage(controller, mangaId, report, result, byId, page + 1)
            }
        }
        if (page > 0) {
            dialog.setPositiveButton("Newer") { _, _ ->
                showPage(controller, mangaId, report, result, byId, page - 1)
            }
        }
        dialog.show()
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
                            val storedManga = db.getManga(link.url, link.sourceId).executeAsBlocking()
                            val storedChapters = storedManga?.let { db.getChapters(it).executeAsBlocking() }.orEmpty()
                            // Preserve locally stored chapters even when an extension dies.
                            val chapters: List<SChapter> = if (storedChapters.isNotEmpty()) {
                                storedChapters
                            } else {
                                if (source == null) {
                                    return@withPermit Snapshot(emptyList(), "${link.title}: missing extension")
                                }
                                withTimeoutOrNull(10_000) {
                                    val remote = storedManga ?: SManga.create().apply {
                                        url = link.url
                                        title = link.title
                                    }
                                    val details = if (remote.initialized) remote else source.getMangaDetails(remote)
                                    source.getChapterList(details)
                                } ?: return@withPermit Snapshot(emptyList(), "${link.title}: timed out")
                            }
                            Snapshot(chapters.map { chapter ->
                                target(
                                    chapter, storedManga, link.sourceId, link.url, link.title,
                                    source?.name ?: sources.getOrStub(link.sourceId).name, false,
                                    preferred == SmartSourceLinks.Preferred(link.sourceId, link.url),
                                    source != null,
                                    SourceHealthLedger.get(context, link.sourceId)?.needsAttention == true,
                                )
                            }, warning = if (source == null) "${link.title}: extension missing; showing stored chapters" else null)
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
                isAvailable = installed,
                needsAttention = needsAttention,
                read = local?.read == true,
                bookmarked = local?.bookmark == true,
                lastPageRead = local?.last_page_read ?: 0,
            ),
            manga = manga,
            chapter = local,
            rawChapter = chapter,
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
                " · read on demand"
            }
            "$recommended${target.sourceName}$health$route\n${target.variant.chapterName}"
        }.map { it as CharSequence }.toTypedArray()
        activity.materialAlertDialog()
            .setTitle(row.variants.first().chapterName)
            .setItems(options) { _, which ->
                val selected = targets[which]
                activity.materialAlertDialog()
                    .setTitle(selected.sourceName)
                    .setMessage("Open: ${selected.variant.chapterName}\nOr remember this source preference for this manga.")
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

    private fun open(controller: MangaDetailsController, target: Target, notice: String? = null) {
        val activity = controller.activity ?: return
        if (!target.variant.isAvailable) {
            activity.materialAlertDialog()
                .setMessage("This source is unavailable. Enable its extension before reading.")
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        // Reading a chapter is the only write in the unified view, and requires
        // the user to explicitly choose and open that exact source chapter.
        val primaryId = controller.presenter.manga.id ?: return
        controller.viewScope.launch {
            val openable = try {
                withContext(Dispatchers.IO) {
                    UnifiedChapterReadThrough.resolve(
                        db = controller.presenter.db,
                        sources = controller.presenter.sourceManager,
                        primaryId = primaryId,
                        sourceId = target.variant.sourceId,
                        mangaUrl = target.variant.mangaUrl,
                        mangaTitle = target.mangaTitle,
                        selected = target.rawChapter,
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Timber.w(error, "Could not open unified chapter")
                controller.activity?.materialAlertDialog()
                    ?.setTitle("Chapter unavailable")
                    ?.setMessage("Unable to open this source chapter: ${error.message ?: "Unknown error"}")
                    ?.setPositiveButton(android.R.string.ok, null)
                    ?.show()
                return@launch
            }
            controller.activity?.let { current ->
                if (notice != null) Toast.makeText(current, notice, Toast.LENGTH_SHORT).show()
                current.startActivity(ReaderActivity.newIntent(current, openable.manga, openable.chapter))
            }
        }
    }
}
