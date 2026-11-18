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
            MaterialMenuSheet.MenuSheetItem(0, R.drawable.ic_help_outline_24dp, text = "Fix issues"),
            MaterialMenuSheet.MenuSheetItem(1, R.drawable.ic_tune_24dp, text = "Auto-fix settings"),
            MaterialMenuSheet.MenuSheetItem(2, R.drawable.ic_search_24dp, text = "Find manga for me"),
            MaterialMenuSheet.MenuSheetItem(3, R.drawable.ic_library_outline_24dp, text = "Check my library"),
            MaterialMenuSheet.MenuSheetItem(4, R.drawable.ic_extension_update_24dp, text = "Extensions and sources"),
            MaterialMenuSheet.MenuSheetItem(5, R.drawable.ic_info_outline_24dp, text = "How does this work?"),
        )
        MaterialMenuSheet(
            activity,
            actions,
            title = "Library tools",
            subtitle = "Fix problems, manage automatic recovery and discover manga",
        ) { sheet, choice ->
            when (choice) {
                0 -> IssueCenterDialog.show(
                    activity,
                    controller.viewScope,
                    controller.presenter.db,
                    controller.presenter.sourceManager,
                    openDetails = { manga ->
                        sheet.dismiss()
                        controller.router.pushController(
                            MangaDetailsController(manga, true).withFadeTransaction(),
                        )
                    },
                    openExtensions = {
                        sheet.dismiss()
                        controller.router.pushController(BrowseController().withFadeTransaction())
                    },
                )
                1 -> AutoFixSettingsDialog.show(activity)
                2 -> DiscoveryDialog.show(
                    activity,
                    controller.viewScope,
                    controller.presenter.db,
                    controller.presenter.sourceManager,
                    controller.preferences,
                ) { manga ->
                    sheet.dismiss()
                    controller.router.pushController(
                        MangaDetailsController(manga, true).withFadeTransaction(),
                    )
                }
                3 -> LibraryAuditDialog.show(
                    activity,
                    controller.viewScope,
                    controller.presenter.db,
                    controller.presenter.sourceManager,
                ) { manga ->
                    sheet.dismiss()
                    controller.router.pushController(
                        MangaDetailsController(manga, true).withFadeTransaction(),
                    )
                }
                4 -> ExtensionIntelligenceDialog.show(activity) {
                    sheet.dismiss()
                    controller.router.pushController(BrowseController().withFadeTransaction())
                }
                5 -> activity.materialAlertDialog()
                    .setTitle("Your manga reading flow")
                    .setMessage(
                        "1. Library: open a manga and choose a chapter to read.\n\n" +
                            "2. Manga tools: use All chapter versions, Find another source, or Fix a reading problem.\n\n" +
                            "3. Library tools → Fix issues: see all recorded source and extension problems in one place.\n\n" +
                            "4. Library tools → Auto-fix settings: switch safe connection retries and Cloudflare verification prompts on or off. Changes save immediately.\n\n" +
                            "The tools menu stays available when you close a sub-screen. No automatic source switching, extension installation or manga deletion.",
                    )
                    .setPositiveButton("Back to tools", null)
                    .show()
                else -> return@MaterialMenuSheet false
            }
            // Stay in Library tools when inspecting/changing settings; only a
            // navigation into a different controller explicitly dismisses it.
            false
        }.show()
    }
}
