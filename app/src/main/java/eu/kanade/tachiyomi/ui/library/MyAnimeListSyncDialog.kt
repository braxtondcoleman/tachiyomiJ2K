package eu.kanade.tachiyomi.ui.library

import androidx.appcompat.app.AppCompatActivity
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.ui.base.MaterialMenuSheet
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Explicit refresh for already-linked MyAnimeList entries, with a clear scope. */
object MyAnimeListSyncDialog {
    fun show(controller: LibraryController) {
        val activity = controller.activity as? AppCompatActivity ?: return
        val tracker = Injekt.get<TrackManager>().myAnimeList
        if (!tracker.isLogged) {
            activity.materialAlertDialog()
                .setTitle("MyAnimeList sync")
                .setMessage("Connect MyAnimeList in Settings → Tracking first.")
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val items = listOf(
            MaterialMenuSheet.MenuSheetItem(0, text = "Refresh linked tracking"),
            MaterialMenuSheet.MenuSheetItem(1, text = "Import MAL reading statuses"),
            MaterialMenuSheet.MenuSheetItem(2, text = "Export my reading statuses to MAL"),
        )
        MaterialMenuSheet(activity, items, title = "MyAnimeList",
            subtitle = "Choose exactly what to sync") { _, choice ->
            when (choice) {
                0 -> refreshLinked(controller)
                1 -> MyAnimeListImportDialog.show(controller)
                2 -> MyAnimeListExportDialog.show(controller)
            }
            true
        }.show()
    }

    private fun refreshLinked(controller: LibraryController) {
        val activity = controller.activity as? AppCompatActivity ?: return
        val manager: TrackManager = Injekt.get()
        if (!manager.myAnimeList.isLogged) {
            activity.materialAlertDialog()
                .setTitle("MyAnimeList sync")
                .setMessage("Connect MyAnimeList from Settings → Tracking before syncing.")
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        controller.viewScope.launch {
            val result = withContext(Dispatchers.IO) {
                val library = controller.presenter.db.getLibraryMangas()
                    .executeAsBlocking().distinctBy { it.id }
                val linked = library.filter { manga ->
                    controller.presenter.db.getTracks(manga).executeAsBlocking().any {
                        it.sync_id == TrackManager.MYANIMELIST
                    }
                }
                library.size to linked
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val count = result.first
            val linked: List<LibraryManga> = result.second
            activity.materialAlertDialog()
                .setTitle("Sync MyAnimeList")
                .setMessage(
                    linked.size.toString() + " of " + count.toString() + " manga are already linked to MyAnimeList.\n\n" +
                        "Refresh pulls tracker status, rating and chapter count for these linked titles. " +
                        "Unlinked manga are NOT matched or imported automatically, and no chapter read flags or downloads are changed."
                )
                .setPositiveButton("Refresh linked") { _, _ ->
                    controller.viewScope.launch {
                        val queued = withContext(Dispatchers.IO) {
                            if (linked.isEmpty()) false else LibraryUpdateJob.startNow(
                                activity.applicationContext,
                                target = LibraryUpdateJob.Target.TRACKING,
                                mangaToUse = linked,
                            )
                        }
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            activity.materialAlertDialog()
                                .setMessage(
                                    if (linked.isEmpty()) "No manga are linked to MyAnimeList yet."
                                    else if (queued) "MyAnimeList tracking refresh queued."
                                    else "Another library update is running. Try again afterwards."
                                )
                                .setPositiveButton(android.R.string.ok, null).show()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
