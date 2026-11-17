# J2K Custom — fork research, feature decisions and practical test plan

**Audit date:** 2026-11-17. **Scope:** Original J2K, TachiyomiSY, Komikku, Reikai, Neko, Tegaki, Rokku, Yōkai, Hayai and Mihon (their public GitHub code and READMEs). This is a curated implementation plan, **not** a claim that every fork feature is missing in J2K or that any feature has been tested on the phone.

**Read this first**
- **CI compiled:** PR #1 [run 44059420302](https://github.com/braxtondcoleman/tachiyomiJ2K/actions/runs/44059420302), PR #2 [run 44157497578](https://github.com/braxtondcoleman/tachiyomiJ2K/actions/runs/44157497578), through commit `35b8bd5f`. These Android APKs are artifacts of their respective runs.
- **Newer change:** alternate-title search port from Tegaki, commits `1aa01350` and `d3bd95ab`; new CI build is independent and must pass before testing that specific version.
- **Unverified:** no on-device tests, backup/restore roundtrip, live source scoring checks, release signing/upgrade tests. PR #1 and PR #2 are **draft**, unmerged. Original J2K install is untouched.
- **No unsafe automation:** never auto-delete, auto-uninstall extensions, silently modify bookmarks/progress, or auto-link based solely on title similarity. Treat updates and source failures as observations, not proof that a website is dead.
- Preserve Apache 2.0 notices and attribute borrowed/adapted code.

## A. Features from other forks that SHOULD be brought into J2K Custom

Only features relevant to a manga-focused reader and its actual pain points. `P0`: safety/identity; `P1`: high-value reading/library; `P2`: polish after core works. **Status means port status in J2K Custom.** Each source link is to a verified public repository or file.

| ID / priority | Feature and origin | What it would do here | Why it is good | Status |
| --- | --- | --- | --- | --- |
| C01 P0 | **Unified merged chapter timeline** — [Reikai stitch/reconcile](https://github.com/unseensnick/Reikai/blob/main/app/src/main/java/reikai/domain/merge/ReconcileMergedChapters.kt), [SY merged source](https://github.com/jobobby04/TachiyomiSY/blob/master/app/src/main/java/eu/kanade/tachiyomi/source/online/all/MergedSource.kt) | Stitch chapters from linked sources into one sorted reader while retaining the source ID/URL and allowing manual source choice | Main missing multi-source feature, prevents tab-hopping | **Not implemented**; only navigation/linking is coded |
| C02 P0 | **Chapter gap detection** — [Reikai ChapterGap](https://github.com/unseensnick/Reikai/blob/main/app/src/main/java/reikai/domain/merge/ChapterGap.kt) | Detect confidently missing numbers without confusing specials, decimals, or different source numbering | Finds holes before switching to a backup source | Not implemented |
| C03 P0 | **Merge-aware backup, restore and tests** — [Reikai backups/tests](https://github.com/unseensnick/Reikai/tree/main/app/src/test/java/eu/kanade/tachiyomi/data/backup), [SY backup](https://github.com/jobobby04/TachiyomiSY/blob/master/app/src/main/java/eu/kanade/tachiyomi/data/backup/models/BackupMergedEntry.kt) | Restore portable source ID+URL associations and reconciled chapter choices; test round trips and unlink | Essential before using merged manga in a real library | J2K memo link included in existing backup format; **round-trip not tested**, unified backup not implemented |
| C04 P0 | **Failed-updates management screen** — [Komikku](https://github.com/komikku-app/komikku/blob/master/app/src/main/java/eu/kanade/tachiyomi/ui/libraryUpdateError/LibraryUpdateErrorScreenModel.kt), [Rokku](https://github.com/rokku-app/rokku) | Select failed manga by source/error; targeted retry and reviewed migration, not just a text report | Converts update failures into actionable recovery | Text history and source-health reports coded, selectable recovery UI **not** |
| C05 P0 | **More reliable extension/update infrastructure** — [Rokku](https://github.com/rokku-app/rokku), [Mihon](https://github.com/mihonapp/mihon) | Compare current extension API/installer handling, source compatibility, download/queue edge cases, and safe fallback | Prevents outages and reduces time spent on extension updates | J2K 1.6-style memo already present; installer concurrency fixes coded; deeper parity audit outstanding |
| C06 P1 | **Chapter fallback from other servers** — [Neko merged sources](https://github.com/nekomangaorg/neko/tree/main/app/src/main/java/eu/kanade/tachiyomi/source/online/merged), [Reikai](https://github.com/unseensnick/Reikai) | For missing or inaccessible chapters, show confirmed alternate sources in the unified reader | Allows continuing when a specific upload disappears | Ranking/link navigation coded; **automatic chapter fallback not implemented** |
| C07 P1 | **Best-source matching using alternate names** — [Tegaki AlternativeTitles](https://github.com/MyNameHand/Tegaki/blob/main/app/src/main/java/mihon/feature/migration/list/search/AlternativeTitles.kt) | Search localized/romanized aliases and title metadata; compare with title/author and coverage | Finds correct copies that use a different title | **Just coded** as `AlternativeTitles.kt` and bounded alias search; new CI and device validation needed |
| C08 P1 | **No-regression migration choice** — [Tegaki](https://github.com/MyNameHand/Tegaki) | Hide or warn about sources with fewer chapters than current source, allow override if numbering differs | Avoids migrating to an incomplete host | Basic chapter-count scoring exists; migration-specific filter not implemented |
| C09 P1 | **Scanlator priority and source deduplication** — [Tegaki](https://github.com/MyNameHand/Tegaki) | For duplicate chapter releases, choose preferred language/scanlation group, with "show all" fallback | Cleaner reading list without losing alternative translations | Not implemented |
| C10 P1 | **Personal recommendations from user taste** — [Reikai profile](https://github.com/unseensnick/Reikai/blob/main/app/src/main/java/reikai/domain/recommendation/taste/ComputeTasteProfile.kt), [SY recommendations](https://github.com/jobobby04/TachiyomiSY) | Learn preferences from ratings/tags/reading and dropped status; suggest diverse unseen titles | Strong discovery without requiring cloud AI or exposing a library | Not implemented |
| C11 P1 | **Related-source suggestions and discovery feed** — [Komikku](https://github.com/komikku-app/komikku), [Reikai](https://github.com/unseensnick/Reikai) | Show source-provided related titles and save cross-source searches into an opt-in feed | Discovery while browsing, not a disruptive home screen replacement | Not implemented |
| C12 P1 | **Upcoming update queue preview** — [Komikku](https://github.com/komikku-app/komikku) | Show manga that will be checked/skipped and reasons *before* global update | Makes hidden filters transparent and prevents surprise skips | Post-run history coded; pre-run preview not implemented |
| C13 P1 | **Resumable failed page downloads** — [Rokku](https://github.com/rokku-app/rokku) | Resume from downloaded pages after interruption and verify the download instead of restarting | Saves bandwidth and frustration on weak connections | Not verified in J2K Custom; port only missing behavior |
| C14 P1 | **MangaDex-like missing content / recommendation sync** — [Neko](https://github.com/nekomangaorg/neko) | Optional trusted tracker/source metadata: IDs, alternative titles, similar titles, chapter status | Better title identity, less false merge, useful recommendations | Not implemented; only for sources/trackers used by user |
| C15 P1 | **Backup-format compatibility validation** — [Yōkai](https://github.com/null2264/yokai), [Reikai](https://github.com/unseensnick/Reikai) | Check J2K/other-fork import, unknown fields, legacy histories, and backup verification | Protects large libraries before source merges | Existing backup validation; safer auto-backup pruning coded, compatibility tests outstanding |
| C16 P2 | **Accessible reduce-motion setting** — [Hayai](https://github.com/wotaku-ship-it/hayai) | Disable UI transitions, fades and load animations consistently | More comfortable reading, useful on battery saver | Not implemented |
| C17 P2 | **Crash-log share and bounded migration concurrency** — [Hayai](https://github.com/wotaku-ship-it/hayai) | Easier diagnostic export and limited parallel batch migration with error reporting | Makes bug reports actionable and bulk moves safer | Not implemented; audit current J2K before port |
| C18 P2 | **Local ComicInfo metadata** — [Yōkai](https://github.com/null2264/yokai) | Read chapter title/number/scanlator from local CBZ archives when metadata exists | Improves downloaded/local manga and imports | Not verified in J2K Custom; port only if missing |
| C19 P2 | **Clear source/language icon and update progress** — [Komikku](https://github.com/komikku-app/komikku) | Source context in library and unobtrusive update progress indication | Makes multi-source entries understandable | J2K already has some source context; verify gaps before port |
| C20 P2 | **Configurable reader preloading** — [Hayai](https://github.com/wotaku-ship-it/hayai) | Tune ahead-of-page image buffering, favor lower data use or smoother reading | Helps slow networks without changing reading engine | Audit existing J2K settings before port |

**Deliberately excluded for now:** separate novel-plugin stack, NSFW-specialty sources, global design/theme replacement, aggressive anti-bot bypass/proxy, blanket adblock integration, automatic unreviewed manga migration, or copy-pasting entire Compose/SQLDelight modules into J2K. These have poor cost/safety/value tradeoffs for the requested manga reader.

## B. New concepts not found as complete advertised/verified features in the reviewed forks

These are **proposals**, not proven universal firsts. Several forks have pieces of each; the *specific combination or workflow* below was not verified as a complete feature.

| Idea | What it would do | Why good / safeguard |
| --- | --- | --- |
| N01 **Source confidence timeline** | Track success, timeout, login challenge, source missing, and coverage changes by day; mark temporary outages separately from persistent failures | Avoids declaring a source dead after one incident; human review for migration |
| N02 **Auto-source ranking per chapter with confidence gate** | Assign a preferred source at title/arc/chapter level using identity, expected chapter range, health, scanlator and download status | Gets the *best available chapter* instead of one blindly preferred website; never switch on low confidence |
| N03 **Dry-run merge + rollback manifest** | Preview source links, duplicate chapters, affected progress/bookmarks/download paths and backup plan; revert linked/reconciled state | Turns major library cleanup into a reversible operation |
| N04 **Source coverage matrix** | One screen: chapters 1–50 source A, 51–100 source B, gaps in red only where numbering is trustworthy | Visually shows why multiple sources matter and where a fallback is necessary |
| N05 **Chapter identity ledger** | Stable composite chapter identity with provenance: source ID, URL, normalized number/title, scanlator, origin, last verified; explicit mapping of equivalents | Prevents reading-progress loss, duplicate downloads and incorrect cross-source chapter jumps |
| N06 **Safe extension-trust audit** | For each extension show repository provenance, signature continuity, version, install type (private/system), last health and available update | Helps decide what to trust, while respecting Android Auto Blocker |
| N07 **Per-host polite request budget** | Coordinate extensions that share a website domain; honor Retry-After, stagger checks, exponential backoff and global concurrency limits | Fewer avoidable 429s and more reliable background updates; no bypassing site security |
| N08 **Local "why I liked/dropped it" taste journal** | One-tap subjective notes ("great tactical MC", "too slow after arc X"); combine with on-device tags/trackers to rank candidates | Recommendations reflect story quality and personal reasons, not only genre labels |
| N09 **Release stall detector** | Detect when a series has not updated unusually long *relative to the source's own history*, with status-aware thresholds | Distinguishes stalled source from legitimately slow releases; label uncertain predictions |
| N10 **Migration recovery assistant** | On repeated source failures, automatically search candidate alternatives, show chapter coverage/progress mapping and require user approval before changes | Reduces repair work without unsafe hands-off changes |
| N11 **Source quality score with explanations** | Rank reliability (known success), freshness (last chapter), completeness (coverage), scanlation preference, and reading/download availability with a visible breakdown | Transparent decisions and user-adjustable weights; never infer quality solely from response speed |
| N12 **Cross-device backup health check** | Verify recent backup readability, size/manga-count consistency, and portable source mapping, then alert locally if recovery data is inadequate | Finds corrupted/missing backup problems *before* an upgrade or phone failure |

## C. Already implemented in our fork — code and test method

**Status terms:** `Built in CI` = compiled and APK artifact published for the branch; **not proof of correct on-device behavior**. `Coded/new CI` = changes committed after the last confirmed successful build and need recompile. `Partial` means the limited scope described, not an entire upstream feature.

| ID | What our implementation does / why useful | How to test without damaging the original | Evidence/status |
| --- | --- | --- | --- |
| F00 Dependency repair | Uses Maven Central FlexibleAdapter artifacts; lets the aging project build | Open GitHub Actions, confirm green run and APK artifact | Built in CI, PR #1/#2 |
| F01 Missing-source update errors | Records affected manga when an extension/source is unavailable rather than silently skipping | On test app only, open a backed-up manga whose source is absent, run chapter update, inspect history/error; **do not uninstall a shared extension from original app** just to force a failure | Built in CI; device untested |
| F02 Bounded transient retries | Up to two retries on connection/IO, HTTP 408/5xx; skips auto-retrying 429 to avoid hammering sites | Developer integration test with controlled mock HTTP 503/429; confirm 503 attempts <=3, 429 one attempt, cancellation prompt; do not generate errors on public sites | Built in CI; controlled test missing |
| F03 WorkManager manual queue persistence | Saves selected manga IDs in up to 256-ID batches, improving survival across process death | On test copy choose >256 titles, start manual update, kill/reopen app, compare result counts (some running-job additions still ephemeral) | Built in CI; device untested |
| F04 Thread-safe library update maps | Prevents simultaneous sources corrupting success/failure/skipped maps | Trigger global update involving multiple installed sources and verify totals/errors; concurrency stress test still needed | Built in CI |
| F05 Last-five-run update history | Saved local update checks, new chapters, skips/errors; share text | Settings → Library → Global updates → **Library update history**; run update, reopen app, verify report, share text deliberately | Built in CI; device untested; **not searchable yet** |
| F08 Separate J2K Custom APK and signing workflow | Distinct package ID, no upstream APK updater, manual signed release workflow; protects original reader | Install debug ZIP APK alongside original, verify both icons, read in custom only; **do not uninstall original**. For future updates configure stable signing key and higher versionCode first | Built debug APK; stable signed update **not configured** |
| F09 Advisory duplicate finder | Displays possible same-title entries by source/read-unread counts, now title+author-aware fuzzy grouping | Settings → Library → **Possible duplicates**; verify known duplicates; check unrelated similar titles aren't grouped; no automatic deletion | Built PR #2; device untested |
| F11 **Partial** linked alternate sources | Link/open/unlink additional source URLs in existing manga memo; no chapter merging | Manga details overflow → **Find best sources** → choose candidate → Link. Then **Linked sources** → Open/Unlink; verify original read history remains; test `.tachibk` restore only on disposable copy | Built PR #2; device untested; unified timeline **not implemented** |
| F12 Extension installer hardening | Atomic counters, concurrent-safe collections, deduplicate completion, guard empty install queue | When updates genuinely available, process several, compare final update counts; empty queue should no-op. Developer-controlled synthetic test preferred | Built PR #2; device untested |
| F13 Safer automatic backup pruning | Write and validate newest backup before old automatic copies are pruned | Configure auto backups in a test directory; after scheduled run verify new file readable, older retained on simulated failure; use dev test for simulated failure | Built PR #2; device untested |
| F14 Improved duplicate matching | Checks original/custom title similarity and compatible known authors | Use known duplicates and edge cases with same title/different authors; inspect read-only list | Built PR #2; device untested |
| F15 Source health report | Logs real chapter update successes/failures and streaks, highlights missing extensions | Settings → Library → **Source health**; run updates, verify source names/counts; distinguish one transient error from repeated failure | Built PR #2; device untested |
| F16 Smart source picker | Searches up to 24 enabled sources, scores title/author/known-library/health, checks chapter count for top matches | Manga details overflow → **Find best sources**, inspect ranking for known multi-source titles (e.g. Kagurabachi/Fool Night), open without linking first, compare chapter counts manually | Built PR #2; device untested; **recommendation, not automatic switching** |
| F17 Extension finder by website name | Search extension catalog using extension name, package ID or bundled source name | Browse → Extensions, search an individual website bundled by an extension and compare results | Built PR #2; device untested |
| F18 Alternate-title matching (Tegaki-inspired) | Reads explicit Alternative Titles/Names section in descriptions, allows alias matches and one bounded fallback search on a subset of sources | Find a title where the current source uses a different translation, compare the smart picker results with previous build; confirm unrelated aliases do not auto-link | **New code committed; CI outcome must be checked separately** |

Not yet built: F06 per-title chapter update override, F07 Samsung Private installer device test, F10 AI recommendations, the full SY/Reikai merged chapter timeline, automatic source fallback, scanlator-priority UI, release signing.

## D. Practical phone testing sequence (15 minutes for the smoke pass; longer tests separately)

1. On Samsung **Files**, keep the existing J2K and a recent `.tachibk` backup. Install **the latest successful GitHub Actions APK artifact** from the desired branch. The test app package must be distinct; do not overwrite your existing reader.
2. Open J2K Custom first with an empty/test library; make a backup in the custom app. Import the existing library only after verifying backup compatibility; do not test destructive migration on the original.
3. Settings → Library: verify history, source health, duplicates; check that opening each does not crash.
4. Choose two known duplicate titles in different sources: **Find best sources** → inspect confidence and chapter coverage → choose **Open**. Only after verifying identity choose **Link**. Verify **Linked sources**, then **Unlink**.
5. Run a small, manual library update on the custom app; inspect failures, skips, and source health. Do not intentionally trigger a website's rate limit.
6. Browse → Extensions; search by extension name and by the website name within an installed extension.
7. Create a test backup, check it is nonempty, and verify restoring **in a disposable copy** does not change reading progress or lose linked sources.
8. Report any crash or wrong ranking with the title, source names, visible error and app build. A green CI build does not replace these tests.

## E. Engineering/release order

1. **Stabilize current compiled branch and sign repeatable releases**; keep Android debug signatures and stable release identities distinct.
2. Add **Reikai-style merge groups and reconciled chapter identity** behind an opt-in flag; design database and backup migration *before* using real library records.
3. Implement **coverage gaps, scanlator priorities and preferred per-chapter source**, then chapter-reader integration.
4. Implement **actionable update-error dashboard**, pre-update skip preview, source migrations and responsible host scheduling.
5. Add **Reikai taste/recommendations + Komikku related titles** (local-first); optionally add AI notes after privacy controls.
6. Later: resumable downloads, reduced motion, local ComicInfo, diagnostics, source icons.

## Source catalog verified November 17, 2026

- [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY) — merged-source design, per-source options and recommendations.
- [Komikku](https://github.com/komikku-app/komikku) — persistent update-error UI, related-manga discovery, update queue preview, feed.
- [Reikai](https://github.com/unseensnick/Reikai) — merge reconciliation/stitch, missing chapters, backup tests, taste profile.
- [Neko](https://github.com/nekomangaorg/neko) — manga recommendations, optional source-specific missing-chapter fills.
- [Tegaki](https://github.com/MyNameHand/Tegaki) — scanlator priority, migration lag filter, alternative-title matching.
- [Rokku](https://github.com/rokku-app/rokku) — resumable downloads, extension/API fixes, errors/migration.
- [Yōkai](https://github.com/null2264/yokai) — backup compatibility and local ComicInfo.
- [Hayai](https://github.com/wotaku-ship-it/hayai) — motion reduction, preloading and diagnostics.
- [Mihon](https://github.com/mihonapp/mihon) — modern core, tracker and extension integration baseline.

This audit is a **living plan**; update statuses only after inspecting actual source, passing CI and recording on-device evidence.
