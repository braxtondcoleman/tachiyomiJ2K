package eu.kanade.tachiyomi.ui.library

import androidx.appcompat.app.AppCompatActivity
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.library.SmartSourceLinks
import eu.kanade.tachiyomi.data.library.SourceRecoveryInbox
import eu.kanade.tachiyomi.data.library.SourceRecoveryPlanner
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** Non-destructive explicit probe, including old titles skipped by normal update rules. */
object LibraryNetworkCheckDialog {
    private data class Probe(val manga: Manga, val chapters: Int?, val error: String?)

    fun show(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        db: DatabaseHelper,
        sources: SourceManager,
    ) {
        var job: Job? = null
        val loading = activity.materialAlertDialog()
            .setTitle("Check older manga")
            .setMessage(
                "Checking up to 80 older titles in small, rate-conscious batches. " +
                    "No chapters, progress, sources or downloads will be changed.",
            )
            .setNegativeButton("Stop") { _, _ -> job?.cancel() }
            .create()
        loading.setOnCancelListener { job?.cancel() }
        loading.show()

        job = scope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val library = db.getLibraryMangas().executeAsBlocking()
                        .distinctBy { it.id }
                        .sortedBy { it.last_update.takeIf { time -> time > 0L } ?: 0L }
                    // Do not overload a failed host: maximum five titles per source.
                    val perSource = mutableMapOf<Long, Int>()
                    val subset = library.filter { manga ->
                        if (manga.url.isBlank()) false else {
                            val used = perSource[manga.source] ?: 0
                            if (used >= 5) false else {
                                perSource[manga.source] = used + 1
                                true
                            }
                        }
                    }.take(80)
                    val limit = Semaphore(2)
                    val probes = subset.map { manga ->
                        async(Dispatchers.IO) {
                            limit.withPermit {
                                val source = sources.get(manga.source)
                                if (source == null) {
                                    return@withPermit Probe(manga, null, "Source unavailable: extension missing")
                                }
                                if (source !is HttpSource) return@withPermit Probe(manga, null, null)
                                try {
                                    val chapters = withTimeoutOrNull(9_000L) {
                                        source.getChapterList(manga)
                                    } ?: return@withPermit Probe(manga, null, "Network timed out")
                                    if (chapters.isEmpty()) Probe(manga, 0, "Chapter parser returned no chapters")
                                    else Probe(manga, chapters.size, null)
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (error: Exception) {
                                    Probe(manga, null, error.message ?: error.javaClass.simpleName)
                                }
                            }
                        }
                    }.awaitAll()
                    val checked = probes.filter { it.error == null && it.chapters != null }
                        .associate { it.manga to requireNotNull(it.chapters) }
                    val failed = probes.mapNotNull { item ->
                        item.error?.let { item.manga to it }
                    }.toMap()
                    SourceRecoveryInbox.recordRun(activity.applicationContext, checked, failed)
                    val linkedFailures = failed.keys.count { SmartSourceLinks.get(it).isNotEmpty() }
                    Triple(subset.size, failed, linkedFailures)
                }
            } catch (cancel: CancellationException) {
                loading.dismiss()
                return@launch
            } catch (error: Exception) {
                Timber.w(error, "Library network health scan failed")
                loading.dismiss()
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.materialAlertDialog().setTitle("Scan unavailable")
                        .setMessage("The scan could not complete. Your manga and chapter records were not modified.")
                        .setPositiveButton(android.R.string.ok, null).show()
                }
                return@launch
            }
            loading.dismiss()
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val (total, failures, linked) = result
            val missing = failures.values.count {
                SourceRecoveryPlanner.classify(it) == SourceRecoveryPlanner.FailureKind.MISSING_TITLE
            }
            activity.materialAlertDialog()
                .setTitle("Library scan results")
                .setMessage(
                    "Checked $total older library entries.\n" +
                        "${failures.size} failed or returned no chapters.\n" +
                        "$missing may have moved or been removed (404).\n" +
                        "$linked failing entries have confirmed alternate links.\n\n" +
                        "Open Fix broken manga to review the recorded failures and sources. " +
                        "Nothing was migrated, deleted or marked read.",
                )
                .setPositiveButton("Done", null).show()
        }
    }
}
