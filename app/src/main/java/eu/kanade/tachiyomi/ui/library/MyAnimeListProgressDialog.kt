package eu.kanade.tachiyomi.ui.library

import androidx.appcompat.app.AppCompatActivity
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.library.AdditiveProgressPlanner
import eu.kanade.tachiyomi.data.library.SmartSourceLinks
import eu.kanade.tachiyomi.data.track.TrackManager
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

/**
 * Explicit, append-only manga chapter progress synchronization to an already
 * linked MAL entry. Does not add/delete series or change status, score,
 * downloads, chapter flags, or review data.
 */
object MyAnimeListProgressDialog {
    private const val MAX_LIBRARY = 2500
    private const val MAX_PREVIEW = 100

    private fun readChapterHighWater(db: DatabaseHelper, manga: Manga): Int =
        db.getChapters(manga).executeAsBlocking()
            .filter { it.read && it.chapter_number.isFinite() &&
                it.chapter_number >= 1f && it.chapter_number <= 100000f }
            .maxOfOrNull { it.chapter_number.toInt() } ?: 0

    /**
     * Linked source entries may be unfavorited: read states still belong to
     * their own rows. Never sum chapter numbers or copy read flags.
     */
    private fun highWaterIncludingLinks(db: DatabaseHelper, manga: Manga): Int {
        var highest = readChapterHighWater(db, manga)
        for (link in SmartSourceLinks.get(manga).take(24)) {
            val alternate = db.getManga(link.url, link.sourceId).executeAsBlocking()
                ?: continue
            highest = maxOf(highest, readChapterHighWater(db, alternate))
        }
        return highest
    }

    fun show(controller: LibraryController) {
        val activity = controller.activity as? AppCompatActivity ?: return
        val mal = Injekt.get<TrackManager>().myAnimeList
        if (!mal.isLogged) {
            activity.materialAlertDialog()
                .setTitle("MyAnimeList")
                .setMessage("Connect your account in Settings → Tracking before syncing reading progress.")
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }

        var job: Job? = null
        val progress = activity.materialAlertDialog()
            .setTitle("Compare chapter progress")
            .setMessage("Checking your already-linked manga. No online changes are being made.")
            .setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() }
            .create()
        progress.setOnCancelListener { job?.cancel() }
        progress.show()

        job = controller.viewScope.launch {
            val result = try {
                val remote = withContext(Dispatchers.IO) {
                    mal.listOwnMangaStatuses().associateBy { it.mediaId }
                }
                withContext(Dispatchers.IO) {
                    val db = controller.presenter.db
                    val library = db.getLibraryMangas().executeAsBlocking()
                        .filter { it.id != null }.distinctBy { it.id }.take(MAX_LIBRARY)
                    val entries = library.mapNotNull { manga ->
                        val track = db.getTracks(manga).executeAsBlocking().firstOrNull {
                            it.sync_id == TrackManager.MYANIMELIST && it.media_id > 0L
                        } ?: return@mapNotNull null
                        val remoteEntry = remote[track.media_id] ?: return@mapNotNull null
                        val local = highWaterIncludingLinks(db, manga)
                        if (local == 0) return@mapNotNull null
                        AdditiveProgressPlanner.KnownProgress(
                            track.media_id, manga.title, remoteEntry.chaptersRead, local,
                        )
                    }
                    Triple(
                        AdditiveProgressPlanner.preview(entries),
                        library.size,
                        remote.size,
                    )
                }
            } catch (cancel: CancellationException) {
                progress.dismiss()
                return@launch
            } catch (error: Exception) {
                Timber.w(error, "MAL additive progress comparison failed")
                progress.dismiss()
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.materialAlertDialog()
                        .setTitle("Progress sync unavailable")
                        .setMessage("Could not compare local chapters with MyAnimeList. No records were changed.")
                        .setPositiveButton(android.R.string.ok, null).show()
                }
                return@launch
            }
            progress.dismiss()
            if (activity.isFinishing || activity.isDestroyed) return@launch

            val changes = result.first
            if (changes.isEmpty()) {
                activity.materialAlertDialog()
                    .setTitle("Reading progress is up to date")
                    .setMessage(
                        "No higher local read-chapter numbers were found among already-linked MyAnimeList entries. " +
                            "Checked up to ${result.second} local manga and ${result.third} MAL entries. " +
                            "Nothing was removed or overwritten.",
                    )
                    .setPositiveButton("Done", null).show()
                return@launch
            }

            val visible = changes.take(MAX_PREVIEW)
            val selected = mutableSetOf<Int>()
            val labels: Array<CharSequence> = visible.map {
                ("${it.title}\nMAL: ${it.oldRead} → ${it.newRead} completed chapters") as CharSequence
            }.toTypedArray()
            activity.materialAlertDialog()
                .setTitle("Add my reading progress · ${visible.size} titles")
                .setMultiChoiceItems(labels, BooleanArray(visible.size)) { _, index, checked ->
                    if (checked) selected.add(index) else selected.remove(index)
                }
                .setPositiveButton("Add selected progress") { _, _ ->
                    controller.viewScope.launch {
                        val count = withContext(Dispatchers.IO) {
                            val db = controller.presenter.db
                            val selectedIds = selected.map { visible[it].mediaId }.toSet()
                            // Scan track ownership once, not for every MAL write.
                            val ownersByMedia = mutableMapOf<Long, MutableList<Manga>>()
                            db.getLibraryMangas().executeAsBlocking()
                                .filter { it.id != null }.distinctBy { it.id }.take(MAX_LIBRARY)
                                .forEach { owner ->
                                    db.getTracks(owner).executeAsBlocking()
                                        .filter { it.sync_id == TrackManager.MYANIMELIST &&
                                            it.media_id in selectedIds }
                                        .forEach { track ->
                                            ownersByMedia.getOrPut(track.media_id) { mutableListOf() }
                                                .add(owner)
                                        }
                                }
                            var successes = 0
                            for (index in selected.sorted()) {
                                val change = visible[index]
                                try {
                                    // Recheck remote right before each write.
                                    // If another device progressed, preserve that higher number.
                                    val synced = mal.addProgressOnly(change.mediaId, change.newRead)
                                    if (synced == null) continue
                                    successes++
                                    ownersByMedia[change.mediaId].orEmpty().forEach { owner ->
                                        db.getTracks(owner).executeAsBlocking()
                                            .filter { it.sync_id == TrackManager.MYANIMELIST &&
                                                it.media_id == change.mediaId }
                                            .forEach { track ->
                                                if (track.last_chapter_read < synced.toFloat()) {
                                                    track.last_chapter_read = synced.toFloat()
                                                    db.insertTrack(track).executeAsBlocking()
                                                }
                                            }
                                    }
                                    delay(350L)
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (error: Exception) {
                                    Timber.w(error, "MAL additive progress sync stopped")
                                    break
                                }
                            }
                            successes
                        }
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            activity.materialAlertDialog()
                                .setTitle("Progress sync completed")
                                .setMessage(
                                    "Added or preserved progress for $count of ${selected.size} selected titles. " +
                                        "Manga, read chapter flags, scores, reading statuses, tags and downloads were not removed or reset. " +
                                        "Only titles already linked to MAL were eligible.",
                                )
                                .setPositiveButton("Done", null).show()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }
}
