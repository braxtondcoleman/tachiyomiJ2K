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
            MaterialMenuSheet.MenuSheetItem(0, R.drawable.ic_search_24dp, text = "Discover manga for me"),
            MaterialMenuSheet.MenuSheetItem(1, R.drawable.ic_library_outline_24dp, text = "Check my library"),
            MaterialMenuSheet.MenuSheetItem(2, R.drawable.ic_extension_update_24dp, text = "Fix sources & extensions"),
            MaterialMenuSheet.MenuSheetItem(3, R.drawable.ic_help_outline_24dp, text = "How does this work?"),
        )
        MaterialMenuSheet(
            activity,
            actions,
            title = "Explore & organize",
            subtitle = "Discover manga, maintain your library, or get help",
        ) { _, choice ->
            when (choice) {
                0 -> DiscoveryDialog.show(
                    activity,
                    controller.viewScope,
                    controller.presenter.db,
                    controller.presenter.sourceManager,
                    controller.preferences,
                ) { manga ->
                    controller.router.pushController(
                        MangaDetailsController(manga, true).withFadeTransaction(),
                    )
                }
                1 -> LibraryAuditDialog.show(
                    activity,
                    controller.viewScope,
                    controller.presenter.db,
                    controller.presenter.sourceManager,
                ) { manga ->
                    controller.router.pushController(
                        MangaDetailsController(manga, true).withFadeTransaction(),
                    )
                }
                2 -> ExtensionIntelligenceDialog.show(activity) {
                    controller.router.pushController(BrowseController().withFadeTransaction())
                }
                3 -> activity.materialAlertDialog()
                    .setTitle("Your manga reading flow")
                    .setMessage(
                        "1. Library: open the manga you want to read.\n\n" +
                            "2. Manga details: tap a chapter to read. Open Manga tools to find chapter versions, alternate websites, or similar manga.\n\n" +
                            "3. Reader: if a page fails, use Try another source. You choose the alternative; nothing switches automatically.\n\n" +
                            "4. Explore & organize: recommendations, library health, and extension diagnostics are here.\n\n" +
                            "Your existing reading history and downloads are preserved when reviewing or linking sources.",
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                else -> return@MaterialMenuSheet false
            }
            true
        }.show()
    }
}
