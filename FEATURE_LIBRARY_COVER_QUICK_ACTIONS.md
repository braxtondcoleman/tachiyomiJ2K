# Next feature: Library cover long-press Quick Actions

## User request — 2026-11-18

"I don't really want to have to open the manga. Is there not a way that I can like long hold the library image or something? That kind of sucks to have to hit it with, like go in and do a favorite tag or a completed tag or things like that."

Goal: change individual or multiple manga's reading organization/status directly from their covers in the Library, **without opening Manga Details**, unnecessary menus, or resetting the current library filter, scroll position and tab.

## Starting point

- Repo: `braxtondcoleman/tachiyomiJ2K`
- Branch: `feature/library-cover-quick-actions`, created from `fix/phase-i-phone-feedback` (PR #12, SHA `0b50b25ad818e64b6434b32b51b31ad42aee9517`).
- Previous PRs #1 through #11 form the Phase I base stack; PR #12 contains the recommendation crash fix, Library tools menu, Fix issues, and Auto-fix switches. Do **not** merge to master without user instruction.
- Existing phone APK artifact built successfully from PR #12; do not disturb the original J2K install or its library.

## Verified current implementation

- `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryController.kt`:
  - `onItemClick()` around line 1721 opens `MangaDetailsController`.
  - `onItemLongClick(position)` around line 1754 currently **enters multi-selection**; it does not open a quick-action menu.
  - `onActionItemClicked()` around line 2456 handles `R.id.action_move_to_category` and marks read/unread.
  - `showChangeMangaCategoriesSheet()` at end uses `selectedMangas.toList().moveCategories(presenter.db, activity) { presenter.getLibrary(); destroyActionModeIfNeeded() }`.
- `app/src/main/res/menu/library_selection.xml` already has the label/tag action, so **today's workaround** is Library > long-press cover > tag icon > choose Dropped/Completed category (create the category if missing), with multi-select available.
- `MangaActionHub` is detail-controller based; do not blindly call it for a Library quick action without checking routing.
- `MangaImpl` / Library uses favorite as in-library state; do not confuse this with tracker favorites or source publication status.
- Source-reported manga 'completed' means publication status, not the reader's 'I finished/abandoned this manga' status. Preserve the distinction.

## Desired interaction

1. **Long-press cover** (when not currently multi-selecting): open a polished native-themed **Manga Quick Actions** bottom sheet with the cover, title, current reading status, and these main actions:
   - **Set reading status:** Reading / On Hold / Dropped / Completed / Plan to Read (and clear status).
   - **Edit categories/tags:** check/uncheck multiple categories without removing unrelated categories.
   - **Mark chapters read/unread:** with clear confirmation for bulk changes, preserves downloads.
   - **Find/fix source:** source health/recovery/search entry when available, preferably without opening details. It is okay to phase this after status/category UX, but the menu action should be accurate, not fake.
   - **Select multiple manga:** retain native multi-select; once active, long presses and taps must keep behaving as selections.
2. **Bulk quick actions**: users can select multiple covers and assign a status or category from the selection toolbar. Keep existing download/share/migration actions.
3. **One-tap status choice**: tapping 'Dropped' updates the manga immediately with confirmation and an Undo snackbar where supported; do not navigate away, reset sorting/filtering, lose current tab/scroll, or rescan library.
4. **Data model**: reading status must be **local-user-defined** and independent of source publishing status and trackers. Decide whether to use a dedicated backup-covered metadata key in manga memo or a carefully managed set of status categories; do NOT automatically delete/rewrite unrelated categories. Document compatibility and backup behavior, and ensure statuses can be filtered or grouped in Library. Prefer a single exclusive status with an unset option, and allow normal categories to co-exist.
5. Show status subtly on library cover (optional tasteful badge/overlay, configurable for users who want clean artwork), with filter/grouping for Dropped, Completed, etc.
6. No destructive operation on long press; removing from Library should require separate confirmation. Status change must not mark every chapter as read, clear reading progress, unlink sources, move files, or change downloads.
7. Accessibility: >=48dp controls, TalkBack labels, no loss of existing native selection functionality, responsive on phone/tablet, works with cover grid and other Library layouts.
8. Unit/instrumentation tests for state changes, category/status persistence, changing status twice, bulk status, scrolling/filters and preservation of chapters/links/downloads; build Android debug + signed upgrade smoke; real Samsung phone QA.

## Additional issue noticed during previous audit

`SmartSourcesDialog.open()` currently constructs a transient `MangaImpl` if the candidate is not in DB, then opens `MangaDetailsController`. This may repeat the **missing database ID** crash that PR #12 fixed in `DiscoveryDialog`. When touching source-fix quick actions, fix/reuse a shared persist-before-navigation helper and add a regression test.

## Handoff / implementation guardrails

- Work only on this new feature branch; create a stacked draft PR based on `fix/phase-i-phone-feedback`, not master.
- Reuse J2K's `MaterialMenuSheet`, category operations, presenter refresh and existing theme wherever practical.
- Do not introduce a second destructive 'Completed' flag tied to source publishing status or automatically mark chapters read.
- The user wants minimal taps and direct cover actions; avoid another nested modal maze.
- Verify implementation through GitHub Actions and provide the exact artifact download path; warn about ephemeral debug signing key and app backup.
