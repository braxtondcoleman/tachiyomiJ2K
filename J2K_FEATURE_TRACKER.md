# J2K fork — feature tally

Development happens on feature branches. **Do not install over the original J2K app.** Keep a fresh `.tachibk` backup.

| ID | Priority | Change | Status | Notes |
| --- | --- | --- | --- | --- |
| F00 | Blocker | Repair Android CI dependency resolution | CI compiled successfully; phone testing pending | Archived JitPack FlexibleAdapter artifacts were missing; using published eu.davidea packages instead |
| F01 | High | Report unavailable or incompatible sources during chapter updates | Implemented; needs build/device validation | Failed manga now appear in the normal update error log |
| F02 | High | Bounded retries for transient chapter-update failures | Implemented; needs build/device validation | Two retries, 2s then 4s; IOException, HTTP 408 and HTTP 5xx only; no HTTP 429 retry |
| F03 | High | Persist manually selected manga IDs across process restart | Implemented; needs build/device validation | Stored in WorkManager Data, in batches of 256; **additional titles injected into a running job remain in-memory** |
| F04 | Medium | Protect concurrent update-result maps | Implemented; needs build/device validation | ConcurrentHashMap for new, failed, and skipped items |
| F05 | Medium | In-app history of last five update runs and share action | Implemented; needs build/device validation | Settings → Library → Global updates → Library update history |
| F06 | Medium | Per-title chapter update overrides | Planned | Separate from Samsung Auto Blocker |
| F07 | High | Fix Samsung source extension APK install friction | Deferred | Test J2K's existing Private installer first |
| F08 | High | Separate package name and repeatable signed Android builds | Implemented in code; signing secrets and device tests still required | Custom app IDs, no upstream updater/telemetry, manual signed release workflow; never commit keystore |
| F09 | Medium | Possible duplicates report in Library settings | Code revised; new CI/device check pending | Now considers original/custom names and compatible authors; advisory only |
| F10 | Later | Integrated recommendations and AI | Planned | Inside the reader, not a companion app |

## Quick phone APK test

Successful PR builds upload `J2K-Custom-Test-APK` under the GitHub Actions run's **Artifacts** section. On Android, download the ZIP from the run, extract `J2K-Custom-Test.apk`, and install it. The debug package is `eu.kanade.tachiyomi.customj2k.debug`, so it will not replace your original reader. This is a test build with GitHub's ephemeral debug signing certificate; **future test builds may not update in place**. Use the dedicated signed-release workflow and a stable personal keystore for lasting installs. Samsung Auto Blocker may need to be temporarily disabled to approve the install; do not work around device security.

## New research and engineering priorities

| ID | Priority | Change | Status | Notes |
| --- | --- | --- | --- | --- |
| F11 | High | **Multiple source titles for one manga** | Non-destructive links and navigation coded; unified chapter view pending | Source URLs+IDs stored in existing backup-covered manga memo; keep original progress separate |
| F12 | High | Extension installer queue concurrency and zero-items guard | Code fixed; new CI/device check pending | CopyOnWriteArrayList, AtomicInteger, completion dedupe and empty-list guard |
| F13 | Medium | Backup recovery safeguards | Code fixed; new CI/device check pending | Prior auto backups now kept until new backup is verified; existing backup validation retained |
| F14 | Medium | Improved duplicate matching | Code improved; new CI/device check pending | Original/custom titles + author check; cannot resolve unrelated translation aliases without external metadata |

| F15 | High | Source health panel | Code added; new CI/device check pending | Counts real library successes/failures; missing extensions; failure streaks rather than guessing a site is dead |
| F16 | High | Smart source finder per manga | Code added; new CI/device check pending | Searches up to 24 enabled sources on request, ranks title/author/chapters/health, shows best recommendation; user confirms link/open |

## Smart source / merge QA checklist

- [ ] CI compiles with J2K Custom smart-source components and extension fixes
- [ ] Missing extension appears under Settings → Library → Source health
- [ ] Repeated errors update health correctly; mixed success is not labelled as site-wide outage
- [ ] Smart search on one title finds confident results across installed enabled catalogues
- [ ] Cancelling an in-progress smart search cancels outstanding requests
- [ ] Recommended source matches title and chapter coverage; low-confidence mismatches excluded
- [ ] Link/unlink preserves original library, chapters, downloads and progress
- [ ] Backup roundtrip restores the portable URL + source ID link entries from manga memo
- [ ] Automatic backups retain old copies if the new backup fails validation
- [ ] Installer handles zero candidates and parallel successful/failed installs without double-counting
- [ ] Cross-source **unified chapter list is NOT implemented** yet and must be tested separately

## QA checklist for F01–F04

- [ ] CI compiles the modified Android app (original build failed due to missing FlexibleAdapter artifacts; retry underway)
- [ ] With a source extension removed, chapter updates report affected manga as failed
- [ ] HTTP 500/503 and transient network errors retry at most twice
- [ ] HTTP 429 and nontransient parse/login errors do **not** retry
- [ ] Cancelling an update stops retries promptly
- [ ] Manually select more than 256 manga; queued batches survive process restart
- [ ] Confirm update notifications/results remain accurate
- [ ] Confirm reading progress and database are unchanged

## Install and update plan

The current upstream build uses `eu.kanade.tachiyomi.j2k` for release, and `eu.kanade.tachiyomi.debugJ2K` for debug. Our first custom install **must** use a distinct application ID to avoid conflicting with the installed J2K app and its signing certificate.

We must sign all future updates with the **same private signing key**, increase Android `versionCode`, and keep the same new application ID. GitHub Actions can build artifacts, but Samsung Auto Blocker may still reject APK updates outside approved stores. A GitHub Releases updater alone does not remove that device restriction. Google Play may also be unsuitable because J2K dynamically downloads executable extension code, contrary to Play policy. Keep secrets out of Git and don't commit keystores.
