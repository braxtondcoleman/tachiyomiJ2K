# TachiyomiSY multi-source manga port — J2K Custom

**Status: research and design complete; merged manga is NOT implemented.** This branch is isolated from PR #1 and the successfully built debug APK. Do not merge or use this branch as a release until functional tests pass.

## User goal

One library entry for a manga with multiple source entries behind it. From one manga details/reader view, the user can choose alternate sources and view chapters from multiple sources. If a source disappears, the other source can still be used. No companion app and no destructive merge of read progress.

## Verified reference implementation

Upstream: [jobobby04/TachiyomiSY](https://github.com/jobobby04/TachiyomiSY), **Apache License 2.0**. Reuse is allowed subject to Apache-2.0 attribution, preservation of notices, and marked modifications.

| SY source code | Behaviors worth porting |
| --- | --- |
| [MergedSource.kt](https://github.com/jobobby04/TachiyomiSY/blob/master/app/src/main/java/eu/kanade/tachiyomi/source/online/all/MergedSource.kt) | Virtual merged source; fetch manga metadata from a selected child; independently update chapters across child sources; per-child update/download toggles; bound concurrency |
| [MergeSettings.kt](https://github.com/jobobby04/TachiyomiSY/blob/master/domain/src/main/java/tachiyomi/domain/manga/model/MergeSettings.kt) | Chapter sorting modes: keep all, no dedupe, prioritize source, most chapters, highest chapter number |
| [MergedEntry.kt](https://github.com/jobobby04/TachiyomiSY/blob/master/domain/src/main/java/tachiyomi/domain/manga/model/MergedEntry.kt) | One linked child per source title, download/update flags, priority |
| [GetMergedChaptersByMangaId.kt](https://github.com/jobobby04/TachiyomiSY/blob/master/domain/src/main/java/tachiyomi/domain/chapter/interactor/GetMergedChaptersByMangaId.kt) | Chapter grouping, ordering, and preservation of unnumbered/special chapters |
| [SmartSearchMerge.kt](https://github.com/jobobby04/TachiyomiSY/blob/master/app/src/main/java/eu/kanade/domain/manga/interactor/SmartSearchMerge.kt) | Prevent duplicate children; create/reuse a virtual merged entry |
| [EditMergedSettingsDialog.kt](https://github.com/jobobby04/TachiyomiSY/blob/master/app/src/main/java/eu/kanade/tachiyomi/ui/manga/merged/EditMergedSettingsDialog.kt) | Source priorities, metadata source, update/download controls |
| [merged.sq](https://github.com/jobobby04/TachiyomiSY/blob/master/data/src/main/sqldelight/tachiyomi/data/merged.sq) | Relationship table with unique parent/child constraints and foreign keys |
| [BackupMergedEntry.kt](https://github.com/jobobby04/TachiyomiSY/blob/master/app/src/main/java/eu/kanade/tachiyomi/data/backup/models/BackupMergedEntry.kt) | Portable backup IDs (manga source ID + URL), not raw internal DB IDs |

## Why direct file copying is unsafe

J2K uses a different app architecture:
- J2K: `data/database/DatabaseHelper.kt` and Requery/SQLite queries, `ui/manga/MangaDetailsController.kt` / `MangaDetailsPresenter.kt`, `source/SourceManager.kt` and `data/backup/models/Backup.kt`.
- SY: newer **SQLDelight** domain/data modules, Metro dependency injection, Compose screens, and different Manga/Chapter models.
- Copying `MergedSource.kt` into J2K produces missing-type errors. Adapt behavior at boundaries rather than transplanting modules.
- Existing J2K protobuf backup already uses source/title fields and custom tags 602 and 800–805. New fields must use unused field numbers and be safe when absent.

## Implementation milestones

- [ ] **M1: non-destructive links.** Add a J2K-native SQLite `merged_manga` and `merged_manga_entries` representation keyed by existing manga IDs, with foreign keys; no overwriting original manga, chapters, downloaded files, categories or read history. A merge should be reversible. Leave source manga intact.
- [ ] **M2: link UI.** Action on a manga's details page: "Add alternate source." Search existing library first; show source title, URL, and chapter/read counts for confirmation. Support rename and unlink.
- [ ] **M3: composite chapter view.** Query each linked manga's chapters. Clearly label source and scanlator; offer "keep all" vs "prioritize preferred source" dedupe with stable ordering; handle unknown chapter numbers, specials, and decimals.
- [ ] **M4: reading/download control.** Opening a chapter must use its *originating source and manga ID*, not a fake URL; keep original read markers; allow per-source update/download toggles and primary metadata source.
- [ ] **M5: source outages.** If one extension is unavailable, do not block the remaining linked manga. Never silently migrate read progress or overwrite original chapters; clearly surface unavailable sources.
- [ ] **M6: backup/restore.** Add optional mapping to `.tachibk` format using a portable (source ID, manga URL) identity. Roundtrip tests for imported/exported merges, duplicate scans, missing sources, uninstall/reinstall.
- [ ] **M7: verification and UX.** Test links, unlink, duplicates, >2 sources, damaged backup, unavailable extensions, multi-source chapter counts, unknown chapter numbers, and reading progress. Only then mark ready and prepare signed APK update.

## Other confirmed code issues to track

- `ExtensionInstallerJob.kt` uses a regular `mutableListOf` for active, succeeded and failed installs and `installed++` while processing up to three concurrent updates. Switch to structured concurrency or synchronization and use an atomic counter/snapshots for notifications.
- `ExtensionInstallerJob.startJob()` calls `requests.first()` after `info.chunked(32)`. If passed an empty list, the job can throw. Add a guard.
- J2K **already schedules backups** via `BackupCreatorJob.setupTask()`. Improve restore verification and prompts before changing merge records; don't duplicate the backup engine.
- Current J2K Custom Possible Duplicates uses simplified title normalization; add author/series metadata plus confirmation to avoid collisions and handle alternate translations.
- The older update-history report currently keeps only five reports; consider expanding to a searchable view instead of a single long dialog, after device validation.

## Safe release path

The successfully built test APK is an artifact of PR #1, commit `d7b6fe734ab91a3c126f28c122644a1aae663227`, not this prototype branch. Preserve it for phone testing. Add signed release workflows and stable keys before promising repeatable updates. Samsung Auto Blocker may block APK installations; do not attempt to circumvent the phone's protections.
