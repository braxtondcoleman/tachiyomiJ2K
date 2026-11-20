package eu.kanade.tachiyomi.ui.library

import androidx.appcompat.app.AppCompatActivity
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.library.SourceRecoveryInbox
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.extension.ExtensionIntelligenceDialog
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Non-blocking Fix issues entry. Never perform file I/O or resolve unknown
 * extension names on the UI thread; dialogs are not shown after host disposal.
 */
object IssueCenterDialog {
    private data class IssueRow(
        val entry: SourceRecoveryInbox.Entry,
        val sourceName: String,
    )

    fun show(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        db: DatabaseHelper,
        sources: SourceManager,
        openDetails: (Manga) -> Unit,
        openExtensions: () -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        scope.launch {
            val rows = try {
                withContext(Dispatchers.IO) {
                    SourceRecoveryInbox.list(activity.applicationContext)
                        .take(80)
                        .map { entry ->
                            IssueRow(entry, sources.getOrStub(entry.sourceId).name)
                        }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Timber.e(error, "Could not open Fix issues: recovery history read failed")
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.materialAlertDialog()
                        .setTitle("Fix issues unavailable")
                        .setMessage("Could not read the saved recovery history. Your library is unchanged. You can still run the Library health audit.")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                return@launch
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            try {
                open(activity, scope, db, sources, rows, openDetails, openExtensions)
            } catch (error: Exception) {
                Timber.e(error, "Fix issues dialog failed to attach")
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.materialAlertDialog()
                        .setTitle("Could not open Fix issues")
                        .setMessage("The repair screen encountered an error. Your manga are unchanged.")
                        .setPositiveButton(android.R.string.ok, null).show()
                }
            }
        }
    }

    private fun open(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        db: DatabaseHelper,
        sources: SourceManager,
        rows: List<IssueRow>,
        openDetails: (Manga) -> Unit,
        openExtensions: () -> Unit,
    ) {
        val entries: Array<CharSequence> = (
            listOf(
                "Scan older manga for broken links\nCheck up to 80 entries skipped by normal updates",
                "Review library health\nDuplicates, missing extensions and empty chapters",
                "Fix sources and extensions\nCompatibility, updates and manual reinstall help",
            ) + rows.map { "${it.entry.title}\n${it.sourceName}: ${it.entry.category.label}" }
        ).map { it as CharSequence }.toTypedArray()
        val dialog = activity.materialAlertDialog()
            .setTitle("Fix issues · ${rows.size} recorded problems")
            .setItems(entries, null)
            .setNeutralButton("Automatic repair settings") { _, _ ->
                AutoFixSettingsDialog.show(activity)
            }
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnShowListener {
            dialog.listView?.setOnItemClickListener { _, _, selected, _ ->
                if (activity.isFinishing || activity.isDestroyed) return@setOnItemClickListener
                try {
                    when (selected) {
                        0 -> LibraryNetworkCheckDialog.show(activity, scope, db, sources)
                        1 -> LibraryAuditDialog.show(activity, scope, db, sources) { manga ->
                            dialog.dismiss()
                            openDetails(manga)
                        }
                        2 -> ExtensionIntelligenceDialog.show(activity) {
                            dialog.dismiss()
                            openExtensions()
                        }
                        else -> {
                            val row = rows.getOrNull(selected - 3) ?: return@setOnItemClickListener
                            inspect(activity, scope, db, row, dialog::dismiss, openDetails)
                        }
                    }
                } catch (error: Exception) {
                    Timber.e(error, "Fix issues action failed: %s", selected)
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        activity.materialAlertDialog()
                            .setTitle("Could not open repair tool")
                            .setMessage("This action could not open. Your library and chapter progress were not changed.")
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun inspect(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        db: DatabaseHelper,
        row: IssueRow,
        closeParent: () -> Unit,
        openDetails: (Manga) -> Unit,
    ) {
        val record = row.entry
        activity.materialAlertDialog()
            .setTitle(record.title)
            .setMessage(
                "Website: ${row.sourceName}\n" +
                    "Problem: ${record.category.label}\n" +
                    "Recent error: ${record.detail}\n" +
                    "Failed checks in a row: ${record.failureStreak}\n\n" +
                    "Open the manga, then choose Manga tools → Fix a reading problem. No manga will be deleted.",
            )
            .setPositiveButton("Open manga") { _, _ ->
                scope.launch {
                    val manga = try {
                        withContext(Dispatchers.IO) {
                            db.getManga(record.mangaUrl, record.sourceId).executeAsBlocking()
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        Timber.w(error, "Could not load recovery manga")
                        null
                    }
                    if (activity.isFinishing || activity.isDestroyed) return@launch
                    if (manga?.id == null) {
                        activity.materialAlertDialog()
                            .setMessage("This manga is no longer stored. Review your extensions or Library health instead.")
                            .setPositiveButton(android.R.string.ok, null).show()
                    } else {
                        closeParent()
                        openDetails(manga)
                    }
                }
            }
            .setNegativeButton("Back to issues", null)
            .show()
    }
}
