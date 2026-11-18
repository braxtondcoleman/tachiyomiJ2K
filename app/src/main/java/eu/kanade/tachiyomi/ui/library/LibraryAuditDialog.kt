package eu.kanade.tachiyomi.ui.library

import androidx.appcompat.app.AppCompatActivity
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.library.LibraryAuditPlanner
import eu.kanade.tachiyomi.data.library.SmartSourceLinks
import eu.kanade.tachiyomi.data.library.SourceHealthLedger
import eu.kanade.tachiyomi.data.library.SourceRecoveryInbox
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * A reviewable library audit, not an automatic "merge duplicates" tool.
 * Only explicitly confirmed source LINK actions can write to the database.
 */
object LibraryAuditDialog {
    private data class Snapshot(
        val report: LibraryAuditPlanner.Report,
        val mangas: Map<Long, LibraryManga>,
    )

    fun show(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        db: DatabaseHelper,
        sources: SourceManager,
        openDetails: (Manga) -> Unit,
    ) {
        val progress = activity.materialAlertDialog()
            .setTitle("Library health audit")
            .setMessage("Reviewing saved manga, sources, update failures and confirmed links. Nothing will be changed.")
            .create()
        progress.show()
        scope.launch {
            val data = try {
                withContext(Dispatchers.IO) {
                    val mangas = db.getLibraryMangas().executeAsBlocking()
                        .filter { it.id != null && it.id != Long.MIN_VALUE }
                        .distinctBy { it.id }
                    val failures = SourceRecoveryInbox.list(activity.applicationContext)
                        .map { it.sourceId to it.mangaUrl }.toSet()
                    val health = mangas.map { it.source }.distinct().associateWith {
                        SourceHealthLedger.get(activity.applicationContext, it)
                    }
                    val entries = mangas.map { manga ->
                        LibraryAuditPlanner.Entry(
                            id = requireNotNull(manga.id),
                            sourceId = manga.source,
                            url = manga.url,
                            title = manga.title,
                            originalTitle = manga.originalTitle,
                            author = manga.originalAuthor,
                            read = manga.read,
                            unread = manga.unread,
                            bookmarks = manga.bookmarkCount,
                            sourceInstalled = sources.get(manga.source) != null,
                            sourceNeedsAttention = health[manga.source]?.needsAttention == true,
                            activeUpdateFailure = (manga.source to manga.url) in failures,
                            linkedSources = SmartSourceLinks.get(manga).size,
                        )
                    }
                    Snapshot(LibraryAuditPlanner.audit(entries), mangas.associateBy { requireNotNull(it.id) })
                }
            } catch (error: Exception) {
                Timber.w(error, "Library audit failed")
                progress.dismiss()
                activity.materialAlertDialog()
                    .setMessage("Could not inspect the library. No manga or chapter data was modified.")
                    .setPositiveButton(android.R.string.ok, null).show()
                return@launch
            }
            progress.dismiss()
            if (activity.isFinishing || activity.isDestroyed) return@launch
            sections(activity, scope, db, sources, data, openDetails)
        }
    }

    private fun sections(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        db: DatabaseHelper,
        sources: SourceManager,
        data: Snapshot,
        openDetails: (Manga) -> Unit,
    ) {
        val report = data.report
        val entries: Array<CharSequence> = arrayOf(
            "Possible duplicates · ${report.groups.size} groups",
            "Missing extensions · ${report.missingExtensions.size} titles",
            "Unresolved update failures · ${report.failedUpdates.size} titles",
            "Sources needing attention · ${report.sourcesNeedingAttention.size} titles",
            "No stored chapter counts · ${report.emptyChapterLists.size} titles",
            "Confirmed alternate links · ${report.confirmedLinks} links",
        )
        activity.materialAlertDialog()
            .setTitle("Library health · ${report.uniqueEntries} titles")
            .setItems(entries) { _, choice ->
                when (choice) {
                    0 -> groups(activity, scope, db, sources, data, openDetails)
                    1 -> listEntries(activity, sources, data, report.missingExtensions, "Missing extension", openDetails)
                    2 -> listEntries(activity, sources, data, report.failedUpdates, "Unresolved update failures", openDetails)
                    3 -> listEntries(activity, sources, data, report.sourcesNeedingAttention, "Source health warnings", openDetails)
                    4 -> listEntries(activity, sources, data, report.emptyChapterLists, "No stored chapters", openDetails)
                    5 -> listEntries(activity, sources, data, report.linkedEntries, "Confirmed links", openDetails)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun listEntries(
        activity: AppCompatActivity,
        sources: SourceManager,
        data: Snapshot,
        entries: List<LibraryAuditPlanner.Entry>,
        title: String,
        openDetails: (Manga) -> Unit,
    ) {
        if (entries.isEmpty()) {
            activity.materialAlertDialog().setTitle(title)
                .setMessage("No matching library titles in this audit.")
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val shown = entries.take(150)
        val items: Array<CharSequence> = shown.map { it.title + "\n" + sources.getOrStub(it.sourceId).name }.map { it as CharSequence }.toTypedArray()
        activity.materialAlertDialog()
            .setTitle("$title · ${entries.size}")
            .setItems(items) { _, index ->
                val manga = data.mangas[shown[index].id]
                if (manga != null) openDetails(manga)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun groups(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        db: DatabaseHelper,
        sources: SourceManager,
        data: Snapshot,
        openDetails: (Manga) -> Unit,
    ) {
        val groups = data.report.groups.take(150)
        if (groups.isEmpty()) {
            activity.materialAlertDialog().setTitle("Duplicate review")
                .setMessage("No high-confidence exact-title, compatible-author duplicate groups were found. Similar or translated names may require manual review.")
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val names: Array<CharSequence> = groups.map { group ->
            "${group.title}\n${group.copies.size} copies on different sources · no automatic merge"
        }.map { it as CharSequence }.toTypedArray()
        activity.materialAlertDialog()
            .setTitle("Possible duplicate groups")
            .setItems(names) { _, index ->
                group(activity, scope, db, sources, data, groups[index], openDetails)
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun group(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        db: DatabaseHelper,
        sources: SourceManager,
        data: Snapshot,
        group: LibraryAuditPlanner.Group,
        openDetails: (Manga) -> Unit,
    ) {
        val copies = group.copies
        val options: Array<CharSequence> = copies.map { copy ->
            val label = if (copy.id == group.suggestedPrimary.id) "★ Suggested primary · " else ""
            "$label${sources.getOrStub(copy.sourceId).name}\n${copy.read} read · ${copy.unread} unread · ${copy.bookmarks} bookmarks"
        }.map { it as CharSequence }.toTypedArray()
        activity.materialAlertDialog()
            .setTitle(group.title)
            .setItems(options) { _, i ->
                inspectCopy(activity, scope, db, sources, data, group, copies[i], openDetails)
            }
            .setNeutralButton("Why grouped?") { _, _ ->
                activity.materialAlertDialog().setTitle("Duplicate evidence")
                    .setMessage(group.explanation + "\n\nNothing is merged or deleted automatically. Read history and downloads stay with each individual source.")
                    .setPositiveButton(android.R.string.ok, null).show()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun inspectCopy(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        db: DatabaseHelper,
        sources: SourceManager,
        data: Snapshot,
        group: LibraryAuditPlanner.Group,
        selected: LibraryAuditPlanner.Entry,
        openDetails: (Manga) -> Unit,
    ) {
        val primary = group.suggestedPrimary
        val target = data.mangas[selected.id] ?: return
        scope.launch {
        val downloaded: Int? = withContext(Dispatchers.IO) {
            try { Injekt.get<DownloadManager>().getDownloadCount(target) }
            catch (_: Exception) { null }
        }
        if (activity.isFinishing || activity.isDestroyed) return@launch
        val summary = buildString {
            appendLine(target.title)
            appendLine("Source: ${sources.getOrStub(selected.sourceId).name}")
            appendLine("Read: ${selected.read}, unread: ${selected.unread}, bookmarks: ${selected.bookmarks}")
            appendLine("Cached downloads: ${downloaded?.toString() ?: "not measured"}")
            appendLine("Confirmed linked sources: ${selected.linkedSources}")
            appendLine()
            appendLine("Suggested primary: ${primary.title} on ${sources.getOrStub(primary.sourceId).name}")
            append("No manga is removed; no read history, categories, bookmarks or downloaded files are transferred.")
        }
        val dialog = activity.materialAlertDialog()
            .setTitle("Review library copy")
            .setMessage(summary)
            .setPositiveButton("Open manga") { _, _ -> openDetails(target) }
            .setNegativeButton(android.R.string.cancel, null)
        if (selected.id != primary.id && LibraryAuditPlanner.canLink(primary, selected)) {
            dialog.setNeutralButton("Review source link") { _, _ ->
                activity.materialAlertDialog()
                    .setTitle("Confirm alternate source link")
                    .setMessage("Do both entries represent the SAME series?\n\n" +
                        "${primary.title} (${sources.getOrStub(primary.sourceId).name})\n" +
                        "${selected.title} (${sources.getOrStub(selected.sourceId).name})\n\n" +
                        "This only adds a removable source link to the suggested primary. Both remain in your library. No migration, deletion or progress copy.")
                    .setPositiveButton("Confirm link") { _, _ ->
                        scope.launch {
                            val linked = withContext(Dispatchers.IO) {
                                SmartSourceLinks.add(
                                    db, primary.id,
                                    SmartSourceLinks.Link(selected.sourceId, selected.url, selected.title),
                                )
                            }
                            activity.materialAlertDialog()
                                .setMessage(if (linked) "Alternate source linked; both original manga remain unchanged." else "No change; the source may already be linked.")
                                .setPositiveButton(android.R.string.ok, null).show()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }
        dialog.show()
        }
    }
}
