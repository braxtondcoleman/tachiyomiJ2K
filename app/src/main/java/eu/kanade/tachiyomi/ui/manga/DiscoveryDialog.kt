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
            activity.materialAlertDialog()
                .setTitle("Discover · ${report.results.size} choices")
                .setItems(items) { _, pos ->
                    val c = report.results[pos]
                    val message = buildString {
                        appendLine(c.manga.title)
                        appendLine("Source: ${c.source.name}")
                        c.suggestion.reasons.forEach { appendLine("• $it") }
                        append(if (c.localManga != null) "\nAlready in your library." else "\nReview its details before adding it.")
                    }
                    activity.materialAlertDialog()
                        .setTitle("Recommendation preview")
                        .setMessage(message)
                        .setPositiveButton("Open manga") { _, _ ->
                            scope.launch {
                                val manga = withContext(Dispatchers.IO) {
                                    db.getManga(c.manga.url, c.source.id).executeAsBlocking()
                                } ?: MangaImpl().apply {
                                    source = c.source.id
                                    url = c.manga.url
                                    title = c.manga.title
                                    author = c.manga.author
                                    artist = c.manga.artist
                                    description = c.manga.description
                                    genre = c.manga.genre
                                    thumbnail_url = c.manga.thumbnail_url
                                    initialized = c.manga.initialized
                                }
                                if (!activity.isFinishing && !activity.isDestroyed) openDetails(manga)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }
}
