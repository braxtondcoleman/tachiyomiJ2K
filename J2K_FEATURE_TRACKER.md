# J2K fork — feature tally

Development happens on feature branches. **Do not install over the original J2K app.** Keep a fresh `.tachibk` backup.

| ID | Priority | Change | Status | Notes |
| --- | --- | --- | --- | --- |
| F00 | Blocker | Repair Android CI dependency resolution | Maven Central replacement committed; new build pending | Archived JitPack FlexibleAdapter artifacts were missing; using published eu.davidea packages instead |
| F01 | High | Report unavailable or incompatible sources during chapter updates | Implemented; needs build/device validation | Failed manga now appear in the normal update error log |
| F02 | High | Bounded retries for transient chapter-update failures | Implemented; needs build/device validation | Two retries, 2s then 4s; IOException, HTTP 408 and HTTP 5xx only; no HTTP 429 retry |
| F03 | High | Persist manually selected manga IDs across process restart | Implemented; needs build/device validation | Stored in WorkManager Data, in batches of 256; **additional titles injected into a running job remain in-memory** |
| F04 | Medium | Protect concurrent update-result maps | Implemented; needs build/device validation | ConcurrentHashMap for new, failed, and skipped items |
| F05 | Medium | In-app history of last five update runs and share action | Implemented; needs build/device validation | Settings → Library → Global updates → Library update history |
| F06 | Medium | Per-title chapter update overrides | Planned | Separate from Samsung Auto Blocker |
| F07 | High | Fix Samsung source extension APK install friction | Deferred | Test J2K's existing Private installer first |
| F08 | High | Separate package name and repeatable signed Android builds | Implemented in code; signing secrets and device tests still required | Custom app IDs, no upstream updater/telemetry, manual signed release workflow; never commit keystore |
| F09 | Medium | Possible duplicates report in Library settings | Implemented read-only; needs build/device validation | Groups titles across sources, displays per-copy read/unread counts; no automatic merging |
| F10 | Later | Integrated recommendations and AI | Planned | Inside the reader, not a companion app |

## Quick phone APK test

Successful PR builds upload `J2K-Custom-Test-APK` under the GitHub Actions run's **Artifacts** section. On Android, download the ZIP from the run, extract `J2K-Custom-Test.apk`, and install it. The debug package is `eu.kanade.tachiyomi.customj2k.debug`, so it will not replace your original reader. This is a test build with GitHub's ephemeral debug signing certificate; **future test builds may not update in place**. Use the dedicated signed-release workflow and a stable personal keystore for lasting installs. Samsung Auto Blocker may need to be temporarily disabled to approve the install; do not work around device security.

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
