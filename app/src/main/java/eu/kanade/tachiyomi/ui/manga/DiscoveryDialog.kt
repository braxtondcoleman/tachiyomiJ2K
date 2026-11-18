package eu.kanade.tachiyomi.ui.manga

import androidx.appcompat.app.AppCompatActivity
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import eu.kanade.tachiyomi.data.library.DiscoveryFinder
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** User-triggered, explainable suggestions: viewing a candidate never adds it. */
object DiscoveryDialog {
    fun show(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        db: DatabaseHelper,
        sources: SourceManager,
        preferences: PreferencesHelper,
        seed: Manga? = null,
        openDetails: (Manga) -> Unit,
    ) {
        var job: Job? = null
        val progress = activity.materialAlertDialog()
            .setTitle(if (seed == null) "Discover from my library" else "More like ${seed.title}")
            .setMessage("Checking up to 8 enabled sources and ranking locally. Your library profile is not transmitted as a search query.")
            .setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() }
            .create()
        progress.setOnCancelListener { job?.cancel() }
        progress.show()
        job = scope.launch {
            val report = try {
                DiscoveryFinder.find(activity.applicationContext, db, sources, preferences, seed)
            } catch (cancel: CancellationException) {
                return@launch
            } catch (error: Exception) {
                Timber.w(error, "Discovery failed")
                progress.dismiss()
                activity.materialAlertDialog()
                    .setMessage("Discovery failed; no library data was changed.")
                    .setPositiveButton(android.R.string.ok, null).show()
                return@launch
            }
            progress.dismiss()
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (report.results.isEmpty()) {
                activity.materialAlertDialog()
                    .setTitle("No discovery results")
                    .setMessage("Checked ${report.queriedSources} enabled sources; ${report.failedSources} didn't respond.")
                    .setPositiveButton(android.R.string.ok, null).show()
                return@launch
            }
            val items: Array<CharSequence> = report.results.map { c ->
                val label = when {
                    c.localManga != null -> "Already in library"
                    c.suggestion.personalized -> "Related to your interests"
                    else -> "Popular — relevance unknown"
                }
                "${c.manga.title}\n${c.source.name} · $label"
            }.map { it as CharSequence }.toTypedArray()

            // Keep the results open behind each preview. A tap must not
            // dismiss the entire discovery list and force a new network scan.
            val results = activity.materialAlertDialog()
                .setTitle("Discover · ${report.results.size} choices")
                .setItems(items, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            results.setOnShowListener {
                results.listView.setOnItemClickListener { _, _, pos, _ ->
                    val candidate = report.results[pos]
                    val message = buildString {
                        appendLine(candidate.manga.title)
                        appendLine("Source: ${candidate.source.name}")
                        candidate.suggestion.reasons.forEach { appendLine("• $it") }
                        append(
                            if (candidate.localManga != null) "\nAlready in your library."
                            else "\nReview its details before adding it to your library.",
                        )
                    }
                    activity.materialAlertDialog()
                        .setTitle("Recommendation preview")
                        .setMessage(message)
                        .setPositiveButton("Open manga") { _, _ ->
                            scope.launch {
                                val manga = try {
                                    withContext(Dispatchers.IO) {
                                        val existing = db.getManga(
                                            candidate.manga.url,
                                            candidate.source.id,
                                        ).executeAsBlocking()
                                        if (RecommendationOpenGuard.canOpen(existing)) {
                                            existing
                                        } else {
                                            // MangaDetailsController saves/recreates by ID.
                                            // A transient MangaImpl has no row ID and can
                                            // crash after controller recreation/navigation.
                                            val item = MangaImpl().apply {
                                                source = candidate.source.id
                                                url = candidate.manga.url
                                                title = candidate.manga.title
                                                author = candidate.manga.author
                                                artist = candidate.manga.artist
                                                description = candidate.manga.description
                                                genre = candidate.manga.genre
                                                thumbnail_url = candidate.manga.thumbnail_url
                                                initialized = candidate.manga.initialized
                                                favorite = false
                                            }
                                            db.insertManga(item).executeAsBlocking()
                                            db.getManga(item.url, item.source).executeAsBlocking()
                                                ?.takeIf(RecommendationOpenGuard::canOpen)
                                                ?: error("Could not save the recommendation for viewing")
                                        }
                                    }
                                } catch (error: Exception) {
                                    Timber.w(error, "Could not open recommended manga")
                                    if (!activity.isFinishing && !activity.isDestroyed) {
                                        activity.materialAlertDialog()
                                            .setTitle("Could not open manga")
                                            .setMessage(
                                                "This title could not be prepared for the reader. " +
                                                    "Your library wasn't changed. Try another source or title.",
                                            )
                                            .setPositiveButton(android.R.string.ok, null)
                                            .show()
                                    }
                                    return@launch
                                }
                                if (activity.isFinishing || activity.isDestroyed) return@launch
                                results.dismiss()
                                openDetails(manga)
                            }
                        }
                        .setNegativeButton("Back to results", null)
                        .show()
                }
            }
            results.show()
        }
    }
}
