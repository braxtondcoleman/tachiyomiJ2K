# Milestone C — actionable source recovery and safe replacements

**Status: recovery feature code and JVM tests implemented in draft PR #5; latest CI, emulator and physical-device QA remain release gates.** This branch is stacked on Phase B's `feature/milestone-b-unified-chapters`. No permanent signing secrets are needed for coding, and nothing is merged into `master`.

## Purpose

When an extension disappears, a source fails repeatedly, or chapters become unavailable, make the problem actionable rather than leaving a manga silently stranded. This follows the previously agreed Komikku-inspired recovery direction, adapted to J2K's existing library-update history, source-health ledger, Smart Source Finder, and Phase B unified chapter variants.

## Deliverables in priority order

### C1. Actionable library update-error inbox

- Read existing actual update results (F01/F05/F15), not speculative network probes.
- One row per affected source/manga, with last attempt, error category, number of impacted titles, and attempt history.
- Categories must distinguish missing extension, disabled source, login required, rate limit / HTTP 429, temporary server / HTTP 5xx, timeout/network error, parse/layout change, removed title, and unknown.
- Provide "retry just this title", "open source", "find extensions", and "suggest alternate" actions where applicable.
- Never automatically retry rate limits or silently install untrusted extensions. Bound retries and preserve cancellation.

### C2. Source coverage and availability

- Per confirmed linked manga: known chapter numbers by source, possible gaps, last successful fetch, current availability, and unknown/uncertain areas.
- Source-wide failure is different from a manga-specific problem; a single timeout must never mark a whole site permanently dead.
- Avoid claiming missing chapter coverage when the extension is not installed or chapter lists are stale.
- Preserve chapter provenance and special/decimal/volume chapters from Phase B's reconciliation.

### C3. Intelligent replacement suggestions

- Reuse Smart Source Finder, confirmed alternate links, relevance/author scores, source health, and *verified* chapter coverage.
- Show best candidates with reasons and confidence, including differences in available chapters and risk of mismatch.
- Offer either "link as alternate" or "preview migration" after user confirmation; never silently adopt the recommendation.
- Detect and flag potential mismatches for unrelated series, short/shared titles, reboots, translated aliases, and remastered chapters.

### C4. Non-destructive migration preview

- Side-by-side source manga and chapter coverage, prior downloaded chapters, read/bookmark/page state, remaining gaps, and uncertain matches.
- Preview exact changes and what remains untouched before any confirmed operation.
- Default to retaining original source, original chapter records, downloads, and rollback link.
- Do not transfer reading progress across ambiguous chapter identities or automatically delete anything.
- Require an explicit user confirmation for every migration, separate from preview.

### C5. Recovery ergonomics

- One-tap "Source problem? Fix it" entry from manga details and the library-update history panel.
- Link to extension management, source settings and login where applicable.
- Rate-limited or missing-extension sources have an appropriate action, not a generic "dead source" label.
- Surface cases where no safe alternative is available rather than inventing a replacement.

## Safety / rollout gates

1. Keep Phase C changes on their own draft PR based on Phase B, with no changes to master or installed phones.
2. Unit tests for error categorization, candidate ranking, gap uncertainty, rollback, and no silent migration.
3. Android CI compilation + emulator upgrade check before any device QA.
4. Sample cases: missing extension, 429, 503, Cloudflare/login error, invalid catalog, two titles with same name, missing chapters, backup/restore.
5. Real-phone QA only after Phase B safety and signing workflows have been approved.
6. Release should keep automatic replacement **disabled by default**; this milestone implements suggestions/approval, not an autonomous destructive migration.

## Development work breakdown

| Task | Source of truth | Validation |
| --- | --- | --- |
| C1 error inbox | LibraryUpdateJob + existing update history | fixture-based categorization and per-title retry |
| C2 coverage | Phase B portable chapter variants + source-health ledger | stale/missing/unknown fixture coverage |
| C3 candidates | SmartSourceFinder + confirmed links | conservative match and wrong-title rejection |
| C4 preview | existing migration controllers + Phase B row identities | no writes until explicit confirmation, rollback |
| C5 navigation | manga details / library settings | click-through smoke and accessibility |

**Do not begin Phase C automatic repair or merging without explicitly revisiting the user-approval controls.**

## Implementation handoff — source recovery (PR #5)

- **C1 — Per-title error inbox:** `SourceRecoveryInbox.recordRun` records actual chapter-update outcomes in a bounded private app file using portable (source ID, manga URL) identities. Individual successes resolve the active failure. Error categories distinguish missing/disabled extensions, HTTP 429, auth/Cloudflare, HTTP 5xx, timeouts, parser changes, removed title and unknown. Only sanitized short reasons are stored; no chapter URLs, credentials, speculative probes or automatic retries. Accessible from **Settings → Library → Source recovery inbox**, where the user can see the number of affected titles per source and open the affected manga.
- **C2 — Honest source coverage:** `SourceRecoveryDialog` reads only the original and explicitly confirmed linked manga's locally stored chapters, read/bookmark/in-progress state, source availability, and actual source health. Distinguishes **stored-only**, **fresh fetched**, unknown, and extension missing. A **Check live** action fetches up to 25 confirmed source manga lists, at most three in parallel, with 12-second timeouts and cancellation; this is read-only and does not update the historic source-health ledger. Even fresh lists with missing, duplicate or unrecognized chapter numbers stay *unverified* for numeric gap claims. Read-only cached download counts are displayed as advisory; existing files are never touched or claimed fully inventoried. The user can explicitly check live chapter lists, but source health is not changed by speculative checks.
- **C3 — Evidence-aware candidate preview:** Existing `SmartSourceFinder` performs bounded search. The candidate dialog adds conservative title/alias matching, author mismatch rejection, short-title warnings, non-equivalence of chapter counts, manual review and a second explicit same-series confirmation before saving a removable link. No auto-link, install, migration or progress copy.
- **C4 — Source-switch / migration safety preview:** Side-by-side original vs confirmed linked source shows chapter counts, overlap, possible gaps, read/bookmarked/in-progress state, cached download counts and explicit no-data-loss expectations. The sole write option changes user preference after a second confirmation; it **does not run the existing destructive migration wizard**. True migration remains a separate user-initiated operation and must be tested before offering it as a one-tap automated action.
- **C5 — Recovery navigation:** Manga details **Source problem? Fix it** menu opens failure details, stored coverage, replacement search, title-only manual update retry, unified chapters, or installed source browser/extension manager. HTTP 429 prompts an explicit warning before user-requested retry. The update-history summary and legacy source-health panel remain available.

### Implemented test fixtures

`SourceRecoveryPlannerTest.kt` includes failure classification (429, 503, network, login/Cloudflare, parser, missing extension/title/unknown), coverage-vs-unknown and unverified gaps, unavailable extension rejection, short-title and author mismatch controls, and strong versus review-only manual suggestions.

### Release/QA gates that are NOT complete

- [ ] Latest full-branch JVM/Android CI passes
- [ ] Latest same-key v114 → v115 disposable-emulator upgrade smoke passes
- [ ] Actual multi-extension source-failure simulation on device (missing, 429, Cloudflare, parse mismatch)
- [ ] Verify recovery inbox opens the correct original manga; source tool navigation and manual retry
- [ ] Verify backup/restore on a separate installed custom build preserves source links, per-source progress and the original J2K app
- [ ] User supplies permanent private signing identity through GitHub Actions Secrets before production release

Phase C is a **manual recovery and safe preview** milestone, not a fully autonomous site repair engine. Additional autonomous switching belongs in a later, separately reviewed phase.

## Beyond Phase C — optional backlog

| Next phase | Proposed scope |
| --- | --- |
| D — Reader continuity | Seamless next-chapter navigation and explicit confirmed-alternate fallback when a chapter fails; progress stays source-specific until verified |
| E — Extension intelligence | Reliable extension finder, deprecation warnings, trusted repo metadata, compatibility/installer diagnostics |
| F — Offline reliability | Download dedupe, queue recovery, offline-first chapter choice, safer refresh after outages |
| G — Discovery | Related works, alternate-title discovery, local recommendations, optionally local AI |
| H — Library organization | Duplicate resolution preview, bulk source audit, unified coverage dashboards, manual cleanup |
| I — UX/performance | Advanced filters, caching/performance, error explanation, accessibility, polished reader transitions |
| Release milestone | One permanent signing key, successful backup and Samsung-device testing, stable private release workflow |
