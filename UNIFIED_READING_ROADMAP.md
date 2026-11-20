# Unified Reading Experience — implementation and validation status

## Implemented in code on this development branch (not yet Android-build verified)

### One normal unified chapter list
- The regular manga details adapter now receives canonical merged rows built from confirmed linked sources. The original chapter list renders immediately while limited, cancellable source refresh runs asynchronously.
- Original and linked source chapters are conservatively reconciled by the existing UnifiedChapterPlanner; uncertain collisions remain separate.
- Default selection uses the existing source preference/health ranker. Tap opens the chosen real source chapter via UnifiedChapterReadThrough; uncached chapters are persisted only on request.
- A short Toast appears for alternate chapters, distinguishing an alternate choice from an entry absent in the primary's stored list; no false claim of verified 404.
- Long press chooses a source, and Manga tools can toggle original-only / combined chapters.
- Chapter subtitles show source provenance. Reader return refreshes merged read-state, with source-aware download, bookmark and read writes. Legacy "remove all downloads" still applies only to the original title.
- Source association requires confirmed user links; raw title similarity alone never links or merges unrelated series.
- No cross-source automatic progress copying, source migration, extension installation or deletion.

### Recovery and old broken entries
- RecoveryCoordinator shares decisions for missing extension, HTTP 404, parser failure, HTTP 403 challenge, 429 rate limit, 5xx and network error.
- Reader error can try a unique, healthy, confirmed same-chapter linked alternative automatically, once per chapter family to avoid loops; ambiguous or stale matches retain manual choice. Page-image failures cannot prove the series is removed.
- Library issue center adds a bounded, explicit network scan of up to 80 older entries (five per source, two parallel), including titles excluded from routine updates. Outcomes feed SourceRecoveryInbox without changing chapter data.
- Full automatic discovery and trusted linking of *previously unlinked* replacement manga is intentionally not claimed. Candidate identity still requires confirmation.

### Personal organization and UI
- Cover long-press quick actions: Reading / Completed / Dropped / On Hold / Plan to Read, edit personal tags, optional drop reasons, edit categories, multi-select, clear status.
- Drop reasons include Boring, OP slop, too much sexual violence, writing, pacing, characters and lost interest; private note supported.
- Personal tags, status and reasons are separate from publication status and chapter read flags, stored in backup-covered memo and preserved by source metadata refresh and MAL status imports.
- Subtle cover badge and list subtitle labels. Library tools reordered for clarity; new in-app help.
- Local recommendations incorporate completed/dropped feedback, content warning filters when source tags explicitly declare the content, and the official MAL related-manga signal.

### MyAnimeList
- Library tools offer clear Refresh, Import and Export actions.
- Refresh updates only *already-linked* tracker entries, and no longer silently re-adds removed remote entries.
- Import fetches the MAL list and allows review of exact uniquely matched reading statuses; ambiguous duplicates are skipped, local chapter flags and progress untouched.
- Export checks remote entries first, then offers a per-title status-only review; only selected statuses are sent, never scores, progress or private tags/reasons.
- Discovery can query the official /v2/manga/{id}?fields=recommendations endpoint for up to two linked seed titles, searches a bounded set of installed sources for matches, and ranks locally.
- Full unsupervised two-way tracker synchronization, title-alias auto-matching, and reading-progress equivalence remain outside safe, tested behavior.

## Validation required before a phone APK or merge
- Compile :app:assembleStandardDebug and run :app:testStandardDebugUnitTest.
- Resolve any Kotlin/API resource or instrumentation failures before release.
- Exercise adapter click/long-press, bulk actions, download ownership, bookmark/read progress and reader next-chapter on confirmed alternate sources.
- Offline and HTTP 404/403/429/5xx cases, no false source-death claim or recovery loop.
- MAL OAuth logged-in/out cases, exact/ambiguous list matching, status-only export without chapter/score overwrite, API rate limits.
- Backup/restore round trip of tags/status/reasons/linked sources, and signed emulator upgrade.
- Physical Samsung test on the separate custom debug package, preserving original J2K.
- No master merge or installed-app changes without user approval.

### Current testing limitation
Connector-authored pushes and draft PR reopening have not produced a GitHub Actions build for this branch. No Android SDK/Gradle dependencies are available in this assistant's container and Hermes cannot access this repository in its sandbox. Therefore unit tests were *written but not executed*, and no installable APK or passing CI result is available. Do not mark implementation verified/releasable until the existing GitHub Actions workflow successfully completes.

Explicitly excluded: English/Japanese split reader.
