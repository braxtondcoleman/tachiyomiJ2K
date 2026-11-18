# Milestone C — actionable source recovery and safe replacements

**Status: planning / acceptance design only.** This branch is based on Phase B's `feature/milestone-b-unified-chapters`. Do not release or merge it until Phase B and Milestone A pass their gates. No permanent signing secrets are needed for coding.

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
