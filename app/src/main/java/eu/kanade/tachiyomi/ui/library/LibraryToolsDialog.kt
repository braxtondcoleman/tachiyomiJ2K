package eu.kanade.tachiyomi.ui.library

import androidx.appcompat.app.AppCompatActivity
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
        val entries: Array<CharSequence> = arrayOf(
            "Find manga for me\nSuggestions based on what I read",
            "Check my library\nDuplicates, missing sources and update problems",
            "Fix extensions & sources\nFind missing or outdated website extensions",
            "How does this work?\nLearn where to find chapters and alternate sources",
        )
        activity.materialAlertDialog()
            .setTitle("Explore & organize")
            .setItems(entries) { _, choice ->
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
                                "2. Manga details: tap a chapter to read. Use Explore & fix manga to find more chapter versions, other websites or similar manga.\n\n" +
                                "3. Reader: if a chapter fails, tap Try another source. You choose the new chapter; nothing switches automatically.\n\n" +
                                "4. Library tools: Find manga for me, Check my library or Fix extensions & sources.\n\n" +
                                "Your existing reading history, downloads and original manga are preserved when reviewing or linking sources.",
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
