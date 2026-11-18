package eu.kanade.tachiyomi.ui.manga

import android.app.Activity
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import eu.kanade.tachiyomi.data.library.SmartSourceFinder
import eu.kanade.tachiyomi.data.library.SmartSourceLinks
import eu.kanade.tachiyomi.data.library.SourceHealthLedger
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * J2K-native UI for SY-inspired source discovery. Never changes favorite, read history,
 * downloads or original manga source: a link is just an alternative navigation option.
 */
object SmartSourcesDialog {
    fun search(controller: MangaDetailsController) {
        val activity = controller.activity ?: return
        var job: Job? = null
        val progress =
            activity.materialAlertDialog()
                .setTitle(R.string.smart_sources_title)
                .setMessage(R.string.smart_sources_scanning)
                .setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() }
                .create()
        progress.setOnCancelListener { job?.cancel() }
        progress.show()

        job = controller.viewScope.launch {
            val report = try {
                SmartSourceFinder.find(
                    activity.applicationContext,
                    controller.presenter.manga,
                    controller.presenter.sourceManager,
                    controller.presenter.preferences,
                    controller.presenter.db,
                )
            } catch (e: CancellationException) {
                return@launch
            } catch (e: Exception) {
                Timber.e(e, "Smart source scan failed")
                progress.dismiss()
                controller.activity?.toast(R.string.smart_sources_failed)
                return@launch
            }
            progress.dismiss()
            val currentActivity = controller.activity ?: return@launch
            if (report.candidates.isEmpty()) {
                currentActivity.materialAlertDialog()
                    .setTitle(R.string.smart_sources_title)
                    .setMessage(
                        currentActivity.getString(R.string.smart_sources_none) +
                            "\n\nSearched ${report.searched} sources; ${report.failures} did not respond.",
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            val choices: Array<CharSequence> =
                report.candidates.mapIndexed { index, result ->
                    val tag = if (index == 0) "★ Best match  " else ""
                    val count = result.chapterCount?.let { " · $it chapters" } ?: ""
                    "$tag${result.manga.title}\n${result.source.name} · score ${result.score}$count"
                }.map { it as CharSequence }.toTypedArray()

            currentActivity.materialAlertDialog()
                .setTitle(R.string.smart_sources_title)
                .setMessage("Searched ${report.searched} installed sources. Ranked matches are suggestions, not verified identity.")
                .setItems(choices) { _, index -> candidate(controller, report.candidates[index]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun candidate(controller: MangaDetailsController, result: SmartSourceFinder.Candidate) {
        val activity = controller.activity ?: return
        val details = buildString {
            appendLine(result.manga.title)
            appendLine("Source: ${result.source.name}")
            appendLine("Relevance score: ${result.score}")
            appendLine(result.note)
            appendLine()
            append("Linking keeps both originals and their reading history separate.")
        }
        activity.materialAlertDialog()
            .setTitle(R.string.smart_sources_candidate)
            .setMessage(details)
            .setPositiveButton(R.string.smart_sources_open) { _, _ ->
                open(controller, result.source.id, result.manga.url, result.manga.title, result.manga)
            }
            .setNeutralButton(R.string.smart_sources_link) { _, _ ->
                val primaryId = controller.presenter.manga.id
                if (primaryId == null || !controller.presenter.manga.favorite) {
                    controller.activity?.toast(R.string.smart_sources_favorite_first)
                    return@setNeutralButton
                }
                controller.viewScope.launch {
                    val linked = withContext(Dispatchers.IO) {
                        SmartSourceLinks.add(
                            controller.presenter.db,
                            primaryId,
                            SmartSourceLinks.Link(result.source.id, result.manga.url, result.manga.title),
                        )
                    }
                    controller.activity?.toast(
                        if (linked) R.string.smart_sources_linked else R.string.smart_sources_already_linked,
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun linked(controller: MangaDetailsController) {
        val activity = controller.activity ?: return
        val primaryId = controller.presenter.manga.id
        if (primaryId == null) {
            activity.toast(R.string.smart_sources_favorite_first)
            return
        }
        controller.viewScope.launch {
            val links = withContext(Dispatchers.IO) {
                val primary = controller.presenter.db.getManga(primaryId).executeAsBlocking()
                    ?: return@withContext emptyList()
                SmartSourceLinks.get(primary)
            }.sortedWith(
                compareBy<SmartSourceLinks.Link> { controller.presenter.sourceManager.get(it.sourceId) == null }
                    .thenBy {
                        SourceHealthLedger.get(activity.applicationContext, it.sourceId)?.failureStreak ?: 0
                    },
            )
            if (links.isEmpty()) {
                activity.materialAlertDialog()
                    .setTitle(R.string.smart_sources_linked_title)
                    .setMessage(R.string.smart_sources_no_links)
                    .setPositiveButton(R.string.smart_sources_search) { _, _ -> search(controller) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return@launch
            }
            val items: Array<CharSequence> =
                links.mapIndexed { i, link ->
                    val source = controller.presenter.sourceManager.getOrStub(link.sourceId)
                    val warning = if (controller.presenter.sourceManager.get(link.sourceId) == null) " ⚠ Missing extension" else ""
                    "${if (i == 0) "★ " else ""}${link.title}\n${source.name}$warning"
                }.map { it as CharSequence }.toTypedArray()
            activity.materialAlertDialog()
                .setTitle(R.string.smart_sources_linked_title)
                .setMessage(R.string.smart_sources_linked_summary)
                .setItems(items) { _, which ->
                    val link = links[which]
                    activity.materialAlertDialog()
                        .setTitle(link.title)
                        .setPositiveButton(R.string.smart_sources_open) { _, _ ->
                            open(controller, link.sourceId, link.url, link.title, null)
                        }
                        .setNeutralButton(R.string.smart_sources_unlink) { _, _ ->
                            controller.viewScope.launch {
                                val removed = withContext(Dispatchers.IO) {
                                    SmartSourceLinks.remove(controller.presenter.db, primaryId, link)
                                }
                                if (removed) controller.activity?.toast(R.string.smart_sources_unlinked)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                .setNeutralButton("Unified chapters") { _, _ ->
                    UnifiedChaptersDialog.show(controller)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun open(
        controller: MangaDetailsController,
        sourceId: Long,
        url: String,
        title: String,
        candidate: eu.kanade.tachiyomi.source.model.SManga?,
    ) {
        controller.viewScope.launch {
            val manga: Manga = withContext(Dispatchers.IO) {
                controller.presenter.db.getManga(url, sourceId).executeAsBlocking()
            } ?: MangaImpl().apply {
                source = sourceId
                this.url = url
                this.title = title
                author = candidate?.author
                artist = candidate?.artist
                description = candidate?.description
                genre = candidate?.genre
                thumbnail_url = candidate?.thumbnail_url
                initialized = candidate?.initialized ?: false
            }
            if (controller.activity != null) {
                controller.router.pushController(MangaDetailsController(manga, true).withFadeTransaction())
            }
        }
    }
}
