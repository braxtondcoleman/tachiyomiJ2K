package eu.kanade.tachiyomi.ui.library

import androidx.appcompat.app.AppCompatActivity
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.library.SourceRecoveryInbox
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.extension.ExtensionIntelligenceDialog
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** A single, discoverable issue center. The list stays open when inspecting a
 * problem; only navigating to another screen closes it.
 */
object IssueCenterDialog {
    fun show(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        db: DatabaseHelper,
        sources: SourceManager,
        openDetails: (Manga) -> Unit,
        openExtensions: () -> Unit,
    ) {
        val issues = SourceRecoveryInbox.list(activity.applicationContext)
        val entries: Array<CharSequence> = (
            listOf(
                "Review library health\nDuplicates, empty chapters and missing extensions",
                "Fix sources & extensions\nUpdates, compatibility and manual reinstall help",
            ) + issues.take(80).map {
                "${it.title}\n${sources.getOrStub(it.sourceId).name}: ${it.category.label}"
            }
        ).map { it as CharSequence }.toTypedArray()
        val dialog = activity.materialAlertDialog()
            .setTitle("Fix issues · ${issues.size} unresolved")
            .setItems(entries, null)
            .setNeutralButton("Auto-fix settings") { _, _ -> AutoFixSettingsDialog.show(activity) }
            .setNegativeButton("Back to tools", null)
            .create()
        dialog.setOnShowListener {
            dialog.listView.setOnItemClickListener { _, _, selected, _ ->
                when (selected) {
                    0 -> LibraryAuditDialog.show(activity, scope, db, sources) {
                        dialog.dismiss()
                        openDetails(it)
                    }
                    1 -> ExtensionIntelligenceDialog.show(activity) {
                        dialog.dismiss()
                        openExtensions()
                    }
                    else -> {
                        val item = issues.getOrNull(selected - 2) ?: return@setOnItemClickListener
                        activity.materialAlertDialog()
                            .setTitle(item.title)
                            .setMessage(
                                "Website: ${sources.getOrStub(item.sourceId).name}\n" +
                                    "Problem: ${item.category.label}\n" +
                                    "Recent error: ${item.detail}\n" +
                                    "Failed checks in a row: ${item.failureStreak}\n\n" +
                                    "Open the manga and choose Manga tools → Fix a reading problem to retry or compare alternatives. This does not delete anything.",
                            )
                            .setPositiveButton("Open manga") { _, _ ->
                                scope.launch {
                                    val manga = try {
                                        withContext(Dispatchers.IO) {
                                            db.getManga(item.mangaUrl, item.sourceId).executeAsBlocking()
                                        }
                                    } catch (error: Exception) {
                                        Timber.w(error, "Failed to open source recovery item")
                                        null
                                    }
                                    if (activity.isFinishing || activity.isDestroyed) return@launch
                                    if (manga == null || manga.id == null) {
                                        activity.materialAlertDialog()
                                            .setMessage("That title is no longer stored. Try the extension tools or update your library.")
                                            .setPositiveButton(android.R.string.ok, null).show()
                                    } else {
                                        dialog.dismiss()
                                        openDetails(manga)
                                    }
                                }
                            }
                            .setNegativeButton("Back to issues", null)
                            .show()
                    }
                }
            }
        }
        dialog.show()
    }
}
