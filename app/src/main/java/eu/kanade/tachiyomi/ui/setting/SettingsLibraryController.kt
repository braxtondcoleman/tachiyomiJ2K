package eu.kanade.tachiyomi.ui.setting

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.library.LibraryUpdateHistory
import eu.kanade.tachiyomi.data.library.SourceRecoveryInbox
import eu.kanade.tachiyomi.data.library.SmartSourceFinder
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.library.SourceHealthLedger
import eu.kanade.tachiyomi.data.preference.DEVICE_BATTERY_NOT_LOW
import eu.kanade.tachiyomi.data.preference.DEVICE_CHARGING
import eu.kanade.tachiyomi.data.preference.DEVICE_ONLY_ON_WIFI
import eu.kanade.tachiyomi.data.preference.DelayedLibrarySuggestionsJob
import eu.kanade.tachiyomi.data.preference.MANGA_HAS_UNREAD
import eu.kanade.tachiyomi.data.preference.MANGA_NON_COMPLETED
import eu.kanade.tachiyomi.data.preference.MANGA_NON_READ
import eu.kanade.tachiyomi.data.preference.MARK_DUPLICATE_CHAPTER_READ_EXISTING
import eu.kanade.tachiyomi.data.preference.MARK_DUPLICATE_CHAPTER_READ_NEW
import eu.kanade.tachiyomi.data.preference.asImmediateFlowIn
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.category.CategoryController
import eu.kanade.tachiyomi.ui.library.LibraryPresenter
import eu.kanade.tachiyomi.ui.library.display.TabbedLibraryDisplaySheet
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.ui.manga.DiscoveryDialog
import eu.kanade.tachiyomi.ui.extension.ExtensionIntelligenceDialog
import eu.kanade.tachiyomi.ui.source.BrowseController
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.data.preference.PreferenceKeys as Keys

class SettingsLibraryController : SettingsController() {
    private val db: DatabaseHelper = Injekt.get()

    override fun setupPreferenceScreen(screen: PreferenceScreen) =
        screen.apply {
            titleRes = R.string.library
            preferenceCategory {
                titleRes = R.string.general
                switchPreference {
                    key = Keys.removeArticles
                    titleRes = R.string.sort_by_ignoring_articles
                    summaryRes = R.string.when_sorting_ignore_articles
                    defaultValue = false
                }

                switchPreference {
                    key = Keys.showLibrarySearchSuggestions
                    titleRes = R.string.search_suggestions
                    summaryRes = R.string.search_tips_show_periodically

                    onChange {
                        it as Boolean
                        if (it) {
                            launchIO {
                                LibraryPresenter.setSearchSuggestion(preferences, db, Injekt.get())
                            }
                        } else {
                            DelayedLibrarySuggestionsJob.setupTask(context, false)
                            preferences.librarySearchSuggestion().set("")
                        }
                        true
                    }
                }

                preference {
                    key = "library_display_options"
                    isPersistent = false
                    titleRes = R.string.display_options
                    summaryRes = R.string.can_be_found_in_library_filters

                    onClick {
                        TabbedLibraryDisplaySheet(this@SettingsLibraryController).show()
                    }
                }

                preference {
                    key = "library_duplicate_finder"
                    isPersistent = false
                    titleRes = R.string.library_duplicate_finder
                    summaryRes = R.string.library_duplicate_finder_summary
                    onClick {
                        // A manga in multiple categories appears several times in the DB query.
                        // Count its ID only once so category membership isn't mistaken for a duplicate.
                        val uniqueManga = db.getLibraryMangas().executeAsBlocking().distinctBy { it.id }
                        // Match original titles or personalized names, rejecting conflicting
                        // author names when known. This remains advisory: never delete on a match.
                        val possibleGroups = mutableListOf<MutableList<eu.kanade.tachiyomi.data.database.models.LibraryManga>>()
                        uniqueManga.forEach { candidate ->
                            if (candidate.originalTitle.isBlank()) return@forEach
                            val match = possibleGroups.firstOrNull { group ->
                                group.any { existing ->
                                    existing.source != candidate.source &&
                                        (
                                            SmartSourceFinder.titleScore(existing.originalTitle, candidate.originalTitle) >= 93 ||
                                                SmartSourceFinder.titleScore(existing.title, candidate.title) >= 95
                                        ) &&
                                        (
                                            existing.originalAuthor.isNullOrBlank() ||
                                                candidate.originalAuthor.isNullOrBlank() ||
                                                SmartSourceFinder.titleScore(existing.originalAuthor!!, candidate.originalAuthor!!) >= 80
                                        )
                                }
                            }
                            if (match == null) {
                                possibleGroups.add(mutableListOf(candidate))
                            } else {
                                match.add(candidate)
                            }
                        }
                        val groups = possibleGroups.filter { it.size > 1 }.sortedBy { it.first().title }

                        val report =
                            if (groups.isEmpty()) {
                                context.getString(R.string.library_duplicate_finder_empty)
                            } else {
                                buildString {
                                    appendLine("${groups.size} possible duplicate title groups:")
                                    appendLine()
                                    groups.forEach { copies ->
                                        appendLine("${copies.first().title} (${copies.size} copies)")
                                        copies.forEach { manga ->
                                            appendLine("  Source ${manga.source} — ${manga.read} read, ${manga.unread} unread")
                                        }
                                        appendLine()
                                    }
                                    appendLine("These are suggestions only. No manga or progress was changed.")
                                }
                            }

                        context.materialAlertDialog()
                            .setTitle(R.string.library_duplicate_finder)
                            .setMessage(report.take(12_000) + if (report.length > 12_000) "\n\n(Share for full results.)" else "")
                            .setNeutralButton(R.string.library_duplicate_finder_share) { _, _ ->
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, report)
                                }
                                context.startActivity(
                                    Intent.createChooser(
                                        intent,
                                        context.getString(R.string.library_duplicate_finder_share),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }

                multiSelectListPreferenceMat(activity) {
                    bindTo(preferences.markDuplicateReadChapterAsRead())
                    titleRes = R.string.pref_mark_duplicate_read_chapter_read
                    entriesRes =
                        arrayOf(
                            R.string.pref_mark_duplicate_read_chapter_read_existing,
                            R.string.pref_mark_duplicate_read_chapter_read_new,
                        )
                    entryValues = listOf(MARK_DUPLICATE_CHAPTER_READ_EXISTING, MARK_DUPLICATE_CHAPTER_READ_NEW)
                    noSelectionRes = R.string.never
                }
            }

            val dbCategories = db.getCategories().executeAsBlocking()

            preferenceCategory {
                titleRes = R.string.categories
                preference {
                    key = "edit_categories"
                    isPersistent = false
                    val catCount = db.getCategories().executeAsBlocking().size
                    titleRes = if (catCount > 0) R.string.edit_categories else R.string.add_categories
                    if (catCount > 0) summary = context.resources.getQuantityString(R.plurals.category_plural, catCount, catCount)
                    onClick { router.pushController(CategoryController().withFadeTransaction()) }
                }
                intListPreference(activity) {
                    key = Keys.defaultCategory
                    titleRes = R.string.default_category

                    val categories = listOf(Category.createDefault(context)) + dbCategories
                    entries =
                        listOf(context.getString(R.string.last_used), context.getString(R.string.always_ask)) +
                        categories.map { it.name }.toTypedArray()
                    entryValues = listOf(-2, -1) + categories.mapNotNull { it.id }.toList()
                    defaultValue = "-2"

                    val categoryName: (Int) -> String = { catId ->
                        when (catId) {
                            -2 -> context.getString(R.string.last_used)
                            -1 -> context.getString(R.string.always_ask)
                            else ->
                                categories.find { it.id == preferences.defaultCategory() }?.name
                                    ?: context.getString(R.string.last_used)
                        }
                    }
                    summary = categoryName(preferences.defaultCategory())
                    onChange { newValue ->
                        summary = categoryName(newValue as Int)
                        true
                    }
                }
            }

            preferenceCategory {
                titleRes = R.string.global_updates
                intListPreference(activity) {
                    key = Keys.libraryUpdateInterval
                    titleRes = R.string.library_update_frequency
                    entriesRes =
                        arrayOf(
                            R.string.manual,
                            R.string.every_12_hours,
                            R.string.daily,
                            R.string.every_2_days,
                            R.string.every_3_days,
                            R.string.weekly,
                        )
                    entryValues = listOf(0, 12, 24, 48, 72, 168)
                    defaultValue = 24

                    onChange { newValue ->
                        // Always cancel the previous task, it seems that sometimes they are not updated.
                        LibraryUpdateJob.setupTask(context, 0)

                        val interval = newValue as Int
                        if (interval > 0) {
                            (activity as? MainActivity)?.showNotificationPermissionPrompt(true)
                            LibraryUpdateJob.setupTask(context, interval)
                        }
                        true
                    }
                }
                multiSelectListPreferenceMat(activity) {
                    bindTo(preferences.libraryUpdateDeviceRestriction())
                    titleRes = R.string.library_update_restriction
                    entriesRes = arrayOf(R.string.wifi, R.string.charging, R.string.battery_not_low)
                    entryValues = listOf(DEVICE_ONLY_ON_WIFI, DEVICE_CHARGING, DEVICE_BATTERY_NOT_LOW)
                    preSummaryRes = R.string.restrictions_
                    noSelectionRes = R.string.none

                    preferences.libraryUpdateInterval().asImmediateFlowIn(viewScope) {
                        isVisible = it > 0
                    }

                    onChange {
                        // Post to event looper to allow the preference to be updated.
                        viewScope.launchUI { LibraryUpdateJob.setupTask(context) }
                        true
                    }
                }

                multiSelectListPreferenceMat(activity) {
                    bindTo(preferences.libraryUpdateMangaRestriction())
                    titleRes = R.string.pref_library_update_manga_restriction
                    entriesRes =
                        arrayOf(
                            R.string.pref_update_only_completely_read,
                            R.string.pref_update_only_started,
                            R.string.pref_update_only_non_completed,
                        )
                    entryValues = listOf(MANGA_HAS_UNREAD, MANGA_NON_READ, MANGA_NON_COMPLETED)
                    noSelectionRes = R.string.none
                }

                triStateListPreference(activity) {
                    preferences.apply {
                        bindTo(libraryUpdateCategories(), libraryUpdateCategoriesExclude())
                    }
                    titleRes = R.string.categories

                    val categories = listOf(Category.createDefault(context)) + dbCategories
                    entries = categories.map { it.name }
                    entryValues = categories.map { it.id.toString() }

                    allSelectionRes = R.string.all
                }

                preference {
                    key = "library_source_health"
                    isPersistent = false
                    titleRes = R.string.library_source_health
                    summaryRes = R.string.library_source_health_summary
                    onClick {
                        val report = SourceHealthLedger.report(context, Injekt.get<SourceManager>())
                        context.materialAlertDialog()
                            .setTitle(R.string.library_source_health)
                            .setMessage(report.take(12_000))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }

                preference {
                    key = "j2k_personalized_discovery"
                    isPersistent = false
                    title = "Discover manga for me"
                    summary = "Popular titles from installed sources, ranked locally by genres and authors in your library"
                    onClick {
                        val host = activity as? androidx.appcompat.app.AppCompatActivity
                        if (host != null) {
                            DiscoveryDialog.show(host, viewScope, db, Injekt.get(), preferences) { manga ->
                                router.pushController(MangaDetailsController(manga, true).withFadeTransaction())
                            }
                        }
                    }
                }

                preference {
                    key = "j2k_extension_intelligence"
                    isPersistent = false
                    title = "Extension intelligence"
                    summary = "Search configured extension indexes, review updates, compatibility and installer status"
                    onClick {
                        val current = activity as? AppCompatActivity
                        if (current != null) {
                            ExtensionIntelligenceDialog.show(current) {
                                router.pushController(BrowseController().withFadeTransaction())
                            }
                        }
                    }
                }

                preference {
                    key = "j2k_source_recovery_inbox"
                    isPersistent = false
                    title = "Source recovery inbox"
                    summary = "Actionable chapter-update failures and source-specific recovery"
                    onClick {
                        val issues = SourceRecoveryInbox.list(context)
                        if (issues.isEmpty()) {
                            context.materialAlertDialog()
                                .setTitle("Source recovery inbox")
                                .setMessage("No unresolved per-title failures yet. Run a library chapter update to collect source-specific recovery results. Old text-only update history remains available below.")
                                .setPositiveButton(android.R.string.ok, null).show()
                        } else {
                            val sourceManager: SourceManager = Injekt.get()
                            val affectedBySource = issues.groupingBy { it.sourceId }.eachCount()
                            val choices: Array<CharSequence> = issues.take(100).map { item ->
                                "${item.title}\n${sourceManager.getOrStub(item.sourceId).name} · ${item.category.label} · ${affectedBySource[item.sourceId] ?: 1} affected titles"
                            }.map { it as CharSequence }.toTypedArray()
                            context.materialAlertDialog()
                                .setTitle("Source recovery · ${issues.size} titles")
                                .setItems(choices) { _, index ->
                                    val issue = issues[index]
                                    context.materialAlertDialog()
                                        .setTitle(issue.title)
                                        .setMessage(
                                            "Source: ${sourceManager.getOrStub(issue.sourceId).name}\n" +
                                                "Issue: ${issue.category.label}\n" +
                                                "Last result: ${issue.detail}\n" +
                                                "Consecutive failed checks: ${issue.failureStreak}\n" +
                                                "Recent attempts: ${issue.history.joinToString(" → ")}\n\n" +
                                                "Opening the manga lets you retry, compare confirmed sources, or find alternatives without migrating automatically.",
                                        )
                                        .setPositiveButton("Open manga") { _, _ ->
                                            viewScope.launch {
                                                val manga = withContext(Dispatchers.IO) {
                                                    db.getManga(issue.mangaUrl, issue.sourceId).executeAsBlocking()
                                                }
                                                if (manga != null) {
                                                    router.pushController(MangaDetailsController(manga, true).withFadeTransaction())
                                                } else {
                                                    context.materialAlertDialog()
                                                        .setMessage("This title is no longer in your database. Nothing was changed.")
                                                        .setPositiveButton(android.R.string.ok, null).show()
                                                }
                                            }
                                        }
                                        .setNegativeButton(android.R.string.cancel, null).show()
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                    }
                }

                switchPreference {
                    key = Keys.refreshCoversToo
                    titleRes = R.string.auto_refresh_covers
                    summaryRes = R.string.auto_refresh_covers_summary
                    defaultValue = true
                }
                preference {
                    key = "library_update_history"
                    isPersistent = false
                    titleRes = R.string.library_update_history
                    summaryRes = R.string.library_update_history_summary
                    onClick {
                        val report = LibraryUpdateHistory.read(context)
                        context.materialAlertDialog()
                            .setTitle(R.string.library_update_history)
                            .setMessage(
                                LibraryUpdateHistory.preview(context).ifBlank {
                                    context.getString(R.string.library_update_history_empty)
                                },
                            )
                            .setNeutralButton(R.string.library_update_history_share) { _, _ ->
                                if (report.isNotBlank()) {
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, report)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(
                                            intent,
                                            context.getString(R.string.library_update_history_share),
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            }
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
        }
}
