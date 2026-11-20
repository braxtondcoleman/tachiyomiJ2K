package eu.kanade.tachiyomi.ui.library

import androidx.appcompat.app.AppCompatActivity
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.library.MalImportPlanner
import eu.kanade.tachiyomi.data.library.ReadingFeedback
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Review-before-import of MAL reading statuses, never remote or chapter writes. */
object MyAnimeListImportDialog {
    private data class Choice(
        val manga: LibraryManga,
        val match: MalImportPlanner.Match,
        val previous: ReadingFeedback.Entry,
    )

    fun show(controller: LibraryController) {
        val activity = controller.activity as? AppCompatActivity ?: return
        val manager: TrackManager = Injekt.get()
        if (!manager.myAnimeList.isLogged) return
        var job: Job? = null
        val loading = activity.materialAlertDialog()
            .setTitle("Checking MyAnimeList")
            .setMessage("Preparing exact title matches. No changes are being made.")
            .setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() }
            .create()
        loading.setOnCancelListener { job?.cancel() }
        loading.show()

        job = controller.viewScope.launch {
            val preview = try {
                withContext(Dispatchers.IO) {
                    val remote = manager.myAnimeList.listOwnMangaStatuses()
                    val library = controller.presenter.db.getLibraryMangas()
                        .executeAsBlocking().distinctBy { it.id }
                    val planned = MalImportPlanner.plan(
                        library.mapNotNull { manga ->
                            manga.id?.let { MalImportPlanner.Local(it, manga.originalTitle) }
                        },
                        remote.map { MalImportPlanner.Remote(it.mediaId, it.title, it.status) },
                    )
                    val byId = library.associateBy { it.id }
                    val changed = planned.matches.mapNotNull { match ->
                        val manga = byId[match.mangaId] ?: return@mapNotNull null
                        val current = ReadingFeedback.get(manga)
                        if (current.status == match.status) null
                        else Choice(manga, match, current)
                    }
                    Triple(remote.size, planned.ambiguousRemote, changed)
                }
            } catch (cancel: CancellationException) {
                loading.dismiss()
                return@launch
            } catch (error: Exception) {
                Timber.w(error, "Could not load MAL manga list")
                loading.dismiss()
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.materialAlertDialog()
                        .setTitle("MyAnimeList unavailable")
                        .setMessage("Could not load the connected manga list. No local data was changed.")
                        .setPositiveButton(android.R.string.ok, null).show()
                }
                return@launch
            }
            loading.dismiss()
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (preview.third.isEmpty()) {
                activity.materialAlertDialog()
                    .setTitle("MyAnimeList import")
                    .setMessage(
                        "Checked " + preview.first + " MAL entries. No new, uniquely matched statuses to import. " +
                            preview.second + " entries were unmatched or ambiguous; they were skipped."
                    )
                    .setPositiveButton(android.R.string.ok, null).show()
                return@launch
            }
            showReview(controller, preview.first, preview.second, preview.third)
        }
    }

    private fun showReview(
        controller: LibraryController,
        total: Int,
        skipped: Int,
        choices: List<Choice>,
    ) {
        val activity = controller.activity as? AppCompatActivity ?: return
        val visible = choices.take(60)
        val selected = mutableSetOf<Int>()
        val labels: Array<CharSequence> = visible.map { choice ->
            (choice.manga.title + "\nMAL: " + choice.match.status.label +
                " · This app: " + (choice.previous.status?.label ?: "Not set")) as CharSequence
        }.toTypedArray()
        activity.materialAlertDialog()
            .setTitle("Review MAL statuses · " + visible.size + " of " + choices.size)
            .setMultiChoiceItems(labels, BooleanArray(visible.size)) { _, index, checked ->
                if (checked) selected.add(index) else selected.remove(index)
            }
            .setPositiveButton("Import selected") { _, _ ->
                controller.viewScope.launch {
                    val applied = withContext(Dispatchers.IO) {
                        var successes = 0
                        selected.sorted().forEach { i ->
                            val item = visible[i]
                            val sameDropped = item.match.status == ReadingFeedback.Status.DROPPED &&
                                item.previous.status == ReadingFeedback.Status.DROPPED
                            val entry = item.previous.copy(
                                status = item.match.status,
                                reasons = if (sameDropped) item.previous.reasons else emptySet(),
                                customReason = if (sameDropped) item.previous.customReason else null,
                            )
                            try {
                                val id = item.manga.id
                                if (id != null && ReadingFeedback.save(controller.presenter.db, id, entry)) {
                                    successes++
                                }
                            } catch (error: Exception) {
                                Timber.w(error, "Could not import one MAL reading status")
                            }
                        }
                        successes
                    }
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        controller.presenter.getLibrary()
                        activity.materialAlertDialog()
                            .setTitle("MAL import completed")
                            .setMessage(
                                "Saved " + applied + " of " + selected.size + " selected personal statuses. " +
                                    "Read chapters, history, downloads, trackers and online MAL records were not changed.\n\n" +
                                    skipped + " remote entries were unmatched or ambiguous. " +
                                    "Other matches can be reviewed in another import pass."
                            )
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
