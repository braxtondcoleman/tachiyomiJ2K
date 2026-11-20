package eu.kanade.tachiyomi.ui.library

import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.library.ReadingFeedback
import eu.kanade.tachiyomi.data.library.ReadingFeedback.DropReason
import eu.kanade.tachiyomi.data.library.ReadingFeedback.Entry
import eu.kanade.tachiyomi.data.library.ReadingFeedback.Status
import eu.kanade.tachiyomi.ui.base.MaterialMenuSheet
import eu.kanade.tachiyomi.util.moveCategories
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.view.snack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Library cover quick actions, without entering details or altering chapters. */
object MangaQuickActionsDialog {
    fun show(controller: LibraryController, manga: Manga, selectMultiple: () -> Unit) {
        val activity = controller.activity as? AppCompatActivity ?: return
        val previous = ReadingFeedback.get(manga)
        val statuses = Status.values()
        val options = statuses.mapIndexed { index, status ->
            MaterialMenuSheet.MenuSheetItem(index, text = status.label)
        } + listOf(
            MaterialMenuSheet.MenuSheetItem(5, text = "Clear reading status"),
            MaterialMenuSheet.MenuSheetItem(6, text = "Edit categories"),
            MaterialMenuSheet.MenuSheetItem(7, text = "Select multiple"),
            MaterialMenuSheet.MenuSheetItem(8, text = "Edit dropped reasons"),
        )
        MaterialMenuSheet(
            activity,
            options,
            title = manga.title,
            subtitle = "Quick actions · " + (previous.status?.label ?: "No reading status"),
            selectedId = statuses.indexOfFirst { it == previous.status }.takeIf { it >= 0 },
        ) { _, choice ->
            when (choice) {
                in statuses.indices -> {
                    val status = statuses[choice]
                    if (status == Status.DROPPED) showDropReasons(controller, manga)
                    else save(controller, manga, Entry(status = status))
                }
                5 -> save(controller, manga, Entry())
                6 -> listOf(manga).moveCategories(controller.presenter.db, activity) {
                    controller.presenter.getLibrary()
                }
                7 -> selectMultiple()
                8 -> showDropReasons(controller, manga)
            }
            true
        }.show()
    }

    private fun showDropReasons(controller: LibraryController, manga: Manga) {
        val activity = controller.activity as? AppCompatActivity ?: return
        val options = DropReason.values()
        val previous = ReadingFeedback.get(manga)
        val selected = previous.reasons.toMutableSet()
        activity.materialAlertDialog()
            .setTitle("Why did you drop this? (optional)")
            .setMultiChoiceItems(
                options.map { it.label as CharSequence }.toTypedArray(),
                options.map { it in selected }.toBooleanArray(),
            ) { _, index, checked ->
                if (checked) selected.add(options[index]) else selected.remove(options[index])
            }
            .setNeutralButton("Write a reason") { _, _ ->
                val note = EditText(activity).apply {
                    hint = "Private note about why you dropped it"
                    setSingleLine(false)
                    setText(previous.customReason.orEmpty())
                }
                activity.materialAlertDialog()
                    .setTitle("Personal drop reason")
                    .setView(note)
                    .setPositiveButton("Save") { _, _ ->
                        save(controller, manga, Entry(Status.DROPPED, selected, note.text.toString()))
                    }
                    .setNegativeButton("Skip note") { _, _ ->
                        save(controller, manga, Entry(Status.DROPPED, selected))
                    }
                    .show()
            }
            .setPositiveButton("Save") { _, _ ->
                save(controller, manga, Entry(Status.DROPPED, selected, previous.customReason))
            }
            .setNegativeButton("Skip reasons") { _, _ ->
                save(controller, manga, Entry(Status.DROPPED))
            }
            .show()
    }

    private fun save(controller: LibraryController, manga: Manga, entry: Entry) {
        val id = manga.id ?: return
        val before = ReadingFeedback.get(manga)
        controller.viewScope.launch {
            val written = withContext(Dispatchers.IO) {
                ReadingFeedback.save(controller.presenter.db, id, entry)
            }
            if (!written) {
                controller.view?.snack("Could not save reading status")
                return@launch
            }
            manga.memo = ReadingFeedback.withEntry(manga.memo, entry)
            controller.presenter.getLibrary()
            controller.view?.snack(
                "Reading status: " + (entry.status?.label ?: "Not set"),
                Snackbar.LENGTH_LONG,
            ) {
                setAction("Undo") {
                    controller.viewScope.launch {
                        val undone = withContext(Dispatchers.IO) {
                            ReadingFeedback.save(controller.presenter.db, id, before)
                        }
                        if (undone) {
                            manga.memo = ReadingFeedback.withEntry(manga.memo, before)
                            controller.presenter.getLibrary()
                        }
                    }
                }
            }
        }
    }
}
