package eu.kanade.tachiyomi.ui.library

import androidx.appcompat.app.AppCompatActivity
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.base.MaterialMenuSheet
import eu.kanade.tachiyomi.ui.extension.ExtensionIntelligenceDialog
import eu.kanade.tachiyomi.ui.manga.DiscoveryDialog
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.ui.source.BrowseController
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.view.withFadeTransaction

/**
 * User-focused entry point to new library capabilities.
 * Keeps daily Library reading uncluttered while making advanced features
 * discoverable without navigating into technical Settings categories.
 */
object LibraryToolsDialog {
    fun show(controller: LibraryController) {
        val activity = controller.activity as? AppCompatActivity ?: return
        val actions = listOf(
            MaterialMenuSheet.MenuSheetItem(0, R.drawable.ic_help_outline_24dp, text = "Fix broken manga"),
            MaterialMenuSheet.MenuSheetItem(2, R.drawable.ic_search_24dp, text = "Find manga"),
            MaterialMenuSheet.MenuSheetItem(6, R.drawable.ic_refresh_24dp, text = "Sync MyAnimeList"),
            MaterialMenuSheet.MenuSheetItem(3, R.drawable.ic_library_outline_24dp, text = "Review library health"),
            MaterialMenuSheet.MenuSheetItem(4, R.drawable.ic_extension_update_24dp, text = "Sources and extensions"),
            MaterialMenuSheet.MenuSheetItem(1, R.drawable.ic_tune_24dp, text = "Automatic repair settings"),
            MaterialMenuSheet.MenuSheetItem(5, R.drawable.ic_info_outline_24dp, text = "Help"),
        )
        MaterialMenuSheet(
            activity,
            actions,
            title = "Library tools",
            subtitle = "Fix problems, manage automatic recovery and discover manga",
        ) { sheet, choice ->
            when (choice) {
                0 -> {
                    // A modal must not be displayed behind a still-expanded
                    // Material bottom sheet. Close it before opening issue review.
                    sheet.dismiss()
                    IssueCenterDialog.show(
                        activity,
                        controller.viewScope,
                        controller.presenter.db,
                        controller.presenter.sourceManager,
                        openDetails = { manga ->
                            controller.router.pushController(
                                MangaDetailsController(manga, true).withFadeTransaction(),
                            )
                        },
                        openExtensions = {
                            controller.router.pushController(BrowseController().withFadeTransaction())
                        },
                    )
                }
                1 -> { sheet.dismiss(); AutoFixSettingsDialog.show(activity) }
                6 -> { sheet.dismiss(); MyAnimeListSyncDialog.show(controller) }
                2 -> { sheet.dismiss(); DiscoveryDialog.show(
                    activity,
                    controller.viewScope,
                    controller.presenter.db,
                    controller.presenter.sourceManager,
                    controller.preferences,
                ) { manga ->
                    controller.router.pushController(
                        MangaDetailsController(manga, true).withFadeTransaction(),
                    )
                } }
                3 -> { sheet.dismiss(); LibraryAuditDialog.show(
                    activity,
                    controller.viewScope,
                    controller.presenter.db,
                    controller.presenter.sourceManager,
                ) { manga ->
                    controller.router.pushController(
                        MangaDetailsController(manga, true).withFadeTransaction(),
                    )
                } }
                4 -> { sheet.dismiss(); ExtensionIntelligenceDialog.show(activity) {
                    controller.router.pushController(BrowseController().withFadeTransaction())
                } }
                5 -> { sheet.dismiss(); activity.materialAlertDialog()
                    .setTitle("Library shortcuts")
                    .setMessage(
                        "Long-press a manga cover to choose Reading, Completed, Dropped, On Hold or Plan to Read. Dropped reasons are optional and private.\n\n" +
                        "Use Library tools → Sync MyAnimeList to refresh linked tracker entries or review a conservative, optional status import.\n\n" +
                        "Fix broken manga shows recorded errors. Open a manga's tools for alternate sources and chapter versions.\n\n" +
                        "Advanced: automatic repair settings only control safe retries and website-verification offers; they do not silently migrate sources."
                    )
                    .setPositiveButton("Done", null)
                    .show() }
                else -> return@MaterialMenuSheet false
            }
            // Every command opens one new screen; never stack modals on a sheet.
            false
        }.show()
    }
}
