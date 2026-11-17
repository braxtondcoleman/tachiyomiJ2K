package eu.kanade.tachiyomi.data.library

import android.content.Context
import eu.kanade.tachiyomi.data.database.models.Manga
import timber.log.Timber
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Small, private, on-device history of chapter update runs.
 * No chapter URLs, cookies, or extension credentials are persisted.
 */
object LibraryUpdateHistory {
    private const val FILE_NAME = "library_update_history.txt"
    private const val SEPARATOR = "\n\n----------\n\n"
    private const val MAX_REPORTS = 5
    private const val MAX_PREVIEW_LENGTH = 12_000

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun record(
        context: Context,
        checked: Map<Manga, Int>,
        failed: Map<Manga, String>,
        skipped: Map<Manga, String>,
    ) {
        if (checked.isEmpty() && failed.isEmpty() && skipped.isEmpty()) return

        val newChapters = checked.values.sum()
        val report =
            buildString {
                appendLine("Library update: ${DateFormat.getDateTimeInstance().format(Date())}")
                appendLine("Checked: ${checked.size} | New chapters: $newChapters | Failed: ${failed.size} | Skipped: ${skipped.size}")
                appendLine()
                checked.entries.sortedBy { it.key.title }.forEach { (manga, count) ->
                    appendLine("${if (count > 0) "NEW ($count)" else "OK"} - ${label(manga)}")
                }
                failed.entries.sortedBy { it.key.title }.forEach { (manga, reason) ->
                    appendLine("FAILED - ${label(manga)} - ${reason.replace('\\n', ' ').take(250)}")
                }
                skipped.entries.sortedBy { it.key.title }.forEach { (manga, reason) ->
                    appendLine("SKIPPED - ${label(manga)} - ${reason.replace('\\n', ' ').take(250)}")
                }
            }.trimEnd()

        try {
            val destination = file(context)
            val previous = if (destination.isFile) destination.readText() else ""
            val reports = (listOf(report) + previous.split(SEPARATOR).filter { it.isNotBlank() }).take(MAX_REPORTS)
            val staging = File(context.filesDir, "$FILE_NAME.tmp")
            staging.writeText(reports.joinToString(SEPARATOR))
            if (!staging.renameTo(destination)) {
                // Filesystems that cannot atomically replace the previous file still get a report.
                staging.copyTo(destination, overwrite = true)
                staging.delete()
            }
        } catch (e: Exception) {
            // History must never prevent chapter updates from finishing.
            Timber.e(e, "Could not save library update history")
        }
    }

    fun read(context: Context): String =
        try {
            file(context).takeIf { it.isFile }?.readText().orEmpty()
        } catch (e: Exception) {
            Timber.e(e, "Could not load library update history")
            ""
        }

    fun preview(context: Context): String {
        val text = read(context)
        return if (text.length > MAX_PREVIEW_LENGTH) {
            text.take(MAX_PREVIEW_LENGTH) + "\n\n(Additional results available with Share.)"
        } else {
            text
        }
    }

    private fun label(manga: Manga): String =
        "${manga.title.replace('\\n', ' ').take(180)} (source ${manga.source})"
}
