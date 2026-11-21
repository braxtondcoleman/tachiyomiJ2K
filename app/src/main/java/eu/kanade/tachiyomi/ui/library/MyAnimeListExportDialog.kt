package eu.kanade.tachiyomi.ui.library

import androidx.appcompat.app.AppCompatActivity
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.library.ReadingFeedback
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Never sends drop reasons, ratings, chapter progress, or unreviewed titles to MAL. */
object MyAnimeListExportDialog {
    private data class Change(val manga: LibraryManga, val track: Track, val target: Int, val before: String)

    private fun malStatus(status: ReadingFeedback.Status) = when (status) {
        ReadingFeedback.Status.READING -> MyAnimeList.READING
        ReadingFeedback.Status.COMPLETED -> MyAnimeList.COMPLETED
        ReadingFeedback.Status.ON_HOLD -> MyAnimeList.ON_HOLD
        ReadingFeedback.Status.DROPPED -> MyAnimeList.DROPPED
        ReadingFeedback.Status.PLAN_TO_READ -> MyAnimeList.PLAN_TO_READ
    }

    private fun remoteName(status: Int): String = when (status) {
        MyAnimeList.READING -> "reading"
        MyAnimeList.COMPLETED -> "completed"
        MyAnimeList.ON_HOLD -> "on_hold"
        MyAnimeList.DROPPED -> "dropped"
        MyAnimeList.PLAN_TO_READ -> "plan_to_read"
        else -> ""
    }

    fun show(controller: LibraryController) {
        val activity = controller.activity as? AppCompatActivity ?: return
        val mal = Injekt.get<TrackManager>().myAnimeList
        if (!mal.isLogged) return
        var job: Job? = null
        val progress = activity.materialAlertDialog()
            .setTitle("Compare MyAnimeList statuses")
            .setMessage("Checking your current MAL list before proposing any changes.")
            .setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() }
            .create()
        progress.setOnCancelListener { job?.cancel() }
        progress.show()
        job = controller.viewScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val remote = mal.listOwnMangaStatuses().associateBy { it.mediaId }
                    val library = controller.presenter.db.getLibraryMangas()
                        .executeAsBlocking().distinctBy { it.id }
                    val mapped = library.mapNotNull { manga ->
                        val local = ReadingFeedback.get(manga).status ?: return@mapNotNull null
                        val track = controller.presenter.db.getTracks(manga).executeAsBlocking()
                            .firstOrNull { it.sync_id == TrackManager.MYANIMELIST && it.media_id > 0L }
                            ?: return@mapNotNull null
                        val remoteStatus = remote[track.media_id]?.status ?: return@mapNotNull null
                        val target = malStatus(local)
                        if (remoteStatus == remoteName(target)) null
                        else Change(manga, track, target, remoteStatus)
                    }
                    // Two library entries may refer to one MAL ID. Never write
                    // an arbitrary winner when their local decisions differ.
                    val grouped = mapped.groupBy { it.track.media_id }
                    val unambiguous = grouped.values.mapNotNull { choices ->
                        choices.singleOrNull()
                    }
                    unambiguous to (mapped.size - unambiguous.size)
                }
            } catch (cancel: CancellationException) {
                progress.dismiss()
                return@launch
            } catch (error: Exception) {
                Timber.w(error, "Could not compare remote MAL statuses")
                progress.dismiss()
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.materialAlertDialog().setTitle("MyAnimeList sync")
                        .setMessage("Could not retrieve your MAL list. No changes were made.")
                        .setPositiveButton(android.R.string.ok, null).show()
                }
                return@launch
            }
            progress.dismiss()
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val changes = result.first
            if (changes.isEmpty()) {
                activity.materialAlertDialog().setTitle("MyAnimeList sync")
                    .setMessage("No uniquely linked status differences to export. " +
                        result.second + " shared/ambiguous links were skipped.")
                    .setPositiveButton(android.R.string.ok, null).show()
                return@launch
            }
            val visible = changes.take(60)
            val selected = mutableSetOf<Int>()
            val labels: Array<CharSequence> = visible.map {
                (it.manga.title + "\nMAL: " + it.before + " → " + remoteName(it.target)) as CharSequence
            }.toTypedArray()
            activity.materialAlertDialog()
                .setTitle("Export reading statuses")
                .setMessage("Only selected reading statuses will change on MAL. Chapter progress, score and personal reasons stay unchanged.")
                .setMultiChoiceItems(labels, BooleanArray(visible.size)) { _, index, checked ->
                    if (checked) selected.add(index) else selected.remove(index)
                }
                .setPositiveButton("Export selected") { _, _ ->
                    controller.viewScope.launch {
                        val count = withContext(Dispatchers.IO) {
                            var succeeded = 0
                            for (i in selected.sorted()) {
                                val change = visible[i]
                                try {
                                    mal.setReadingStatusOnly(change.track.media_id, change.target)
                                    change.track.status = change.target
                                    controller.presenter.db.insertTrack(change.track).executeAsBlocking()
                                    succeeded++
                                    delay(350L)
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (error: Exception) {
                                    Timber.w(error, "MAL status-only export halted")
                                    // Avoid repeated requests after rate limits or auth failures.
                                    break
                                }
                            }
                            succeeded
                        }
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            activity.materialAlertDialog().setTitle("MyAnimeList export")
                                .setMessage("Updated " + count + " of " + selected.size +
                                    " selected statuses. No chapter progress, scores, reasons or downloads were changed.")
                                .setPositiveButton(android.R.string.ok, null).show()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }
}
