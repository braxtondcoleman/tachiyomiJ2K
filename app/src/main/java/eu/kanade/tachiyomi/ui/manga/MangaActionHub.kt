package eu.kanade.tachiyomi.ui.manga

import androidx.appcompat.app.AppCompatActivity
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.view.withFadeTransaction

/**
 * A single easy-to-find entry into all user-facing title-level manga tools.
 * Technical migration operations remain separately available in Advanced.
 */
object MangaActionHub {
    fun show(controller: MangaDetailsController) {
        val activity = controller.activity as? AppCompatActivity ?: return
        val manga = controller.presenter.manga
        val choices = MangaActionPlanner.options(manga.favorite, manga.isLocal())
        val labels: Array<CharSequence> = choices.map { MangaActionPlanner.row(it) as CharSequence }.toTypedArray()
        activity.materialAlertDialog()
            .setTitle("Manga tools · ${manga.title}")
            .setItems(labels) { _, index ->
                when (choices[index]) {
                    MangaActionPlanner.Action.CHAPTERS -> UnifiedChaptersDialog.show(controller)
                    MangaActionPlanner.Action.FIND_SOURCE -> SmartSourcesDialog.search(controller)
                    MangaActionPlanner.Action.LINKED -> SmartSourcesDialog.linked(controller)
                    MangaActionPlanner.Action.RELATED -> {
                        DiscoveryDialog.show(
                            activity,
                            controller.viewScope,
                            controller.presenter.db,
                            controller.presenter.sourceManager,
                            controller.presenter.preferences,
                            manga,
                        ) { selected ->
                            controller.router.pushController(
                                MangaDetailsController(selected, true).withFadeTransaction(),
                            )
                        }
                    }
                    MangaActionPlanner.Action.FIX -> SourceRecoveryDialog.show(controller)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
