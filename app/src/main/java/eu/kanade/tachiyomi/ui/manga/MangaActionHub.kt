package eu.kanade.tachiyomi.ui.manga

import androidx.appcompat.app.AppCompatActivity
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.base.MaterialMenuSheet
import eu.kanade.tachiyomi.util.isLocal
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
        val items = choices.map { action ->
            MaterialMenuSheet.MenuSheetItem(
                id = action.ordinal,
                drawable = when (action) {
                    MangaActionPlanner.Action.CHAPTERS -> R.drawable.ic_read_outline_24dp
                    MangaActionPlanner.Action.FIND_SOURCE -> R.drawable.ic_search_24dp
                    MangaActionPlanner.Action.LINKED -> R.drawable.ic_bookmark_border_24dp
                    MangaActionPlanner.Action.RELATED -> R.drawable.ic_star_24dp
                    MangaActionPlanner.Action.FIX -> R.drawable.ic_help_outline_24dp
                },
                text = if (action == MangaActionPlanner.Action.CHAPTERS) {
                    if (controller.presenter.isUnifiedActive) "Show original-only chapters"
                    else "Show combined chapters"
                } else action.title,
            )
        }
        MaterialMenuSheet(
            activity,
            items,
            title = "Manga tools",
            subtitle = "Choose what to do with ${manga.title}",
        ) { sheet, selected ->
            when (choices.firstOrNull { it.ordinal == selected }) {
                MangaActionPlanner.Action.CHAPTERS -> {
                    controller.presenter.toggleUnifiedChapters()
                    sheet.dismiss()
                }
                MangaActionPlanner.Action.FIND_SOURCE -> {
                    sheet.dismiss()
                    SmartSourcesDialog.search(controller)
                }
                MangaActionPlanner.Action.LINKED -> SmartSourcesDialog.linked(controller)
                MangaActionPlanner.Action.RELATED -> {
                    DiscoveryDialog.show(
                        activity,
                        controller.viewScope,
                        controller.presenter.db,
                        controller.presenter.sourceManager,
                        controller.presenter.preferences,
                        manga,
                    ) { candidate ->
                        sheet.dismiss()
                        controller.router.pushController(
                            MangaDetailsController(candidate, true).withFadeTransaction(),
                        )
                    }
                }
                MangaActionPlanner.Action.FIX -> {
                    sheet.dismiss()
                    SourceRecoveryDialog.show(controller)
                }
                null -> return@MaterialMenuSheet false
            }
            // Keep manga tools available while inspecting chapter versions or
            // source-health dialogs; entering a different screen dismisses it.
            false
        }.show()
    }
}
