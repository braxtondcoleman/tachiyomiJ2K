# Milestone B — Unified chapters with safe multi-source fallback

Status: **design and acceptance plan only**. Milestone A (#3) must pass CI and restore/upgrade checks before shipping any unified-chapter behavior. This branch is stacked on `feature/milestone-a-library-safety`; neither branch changes `master` until reviewed.

## Goal

Make one manga library item able to show a coherent chapter timeline assembled from its **confirmed linked sources**, then choose a sensible usable source for each chapter. Reading progress, bookmarks, history, downloads and source-origin information must remain recoverable. Existing individual-source chapter lists remain intact.

## Boundaries

- No guessed cross-series links. Smart Source Picker can recommend, but the user confirms the title match and source link.
- Never silently migrate, overwrite, or delete an existing library entry, chapter record, read state, download or source link.
- Don't install extensions automatically. Failed/unavailable extensions must be reported, not hidden.
- Start with an opt-in unified view; original chapter list remains one tap away, and disabling the feature is lossless.
- Source health (F15) is a ranking input, **not** evidence that an individual chapter exists or matches.
- Keep sources distinct (source ID, manga URL, chapter URL) so an alternate can be opened without pretending it is the original chapter.
- No automatic backfill of chapter progress across different sources until equivalence is demonstrably safe and tested.
- Do not merge this branch into master before Milestone A testing/signing gates are complete.

## Implementation sequence

1. **Read-only normalization model.** Create stable canonical chapter candidates from chapter number/volume, title and ordering, with retained per-source variants. A missing, blank, ambiguous or special chapter number must not be forced into a numeric identity. Repeated chapter numbers, decimals, parts, extras, and scanlator differences need explicit handling.
2. **Conservative reconciliation.** Merge only high-confidence equivalent chapters. Represent uncertain collisions separately with a visible reason; prefer missing duplicates over wrongly merging unrelated chapters.
3. **Opt-in unified chapter UI.** Show one logical row per confirmed equivalent chapter, preserve source attribution and provide an original-source view. Load no more data than needed; never erase native rows.
4. **Coverage and gaps.** Distinguish chapter genuinely absent from a link, extension unavailable, network failure, stale catalogue, and unknown coverage. Detect only trustworthy numbering gaps; don't invent gaps between specials/volumes.
5. **Smart variant selection.** Prefer an available, healthy, user-preferred source with a confirmed matching chapter. Provide manual override and show when the preferred variant failed. A source switch is a user-visible navigation/reading decision, not a silent library migration.
6. **Progress-safe opening and persistence.** Reading one variant updates exactly the intended logical progress after a verified equivalence; preserve original chapter identifiers, remote URLs, source-specific bookmarks and history. If mapping is uncertain, keep progress source-local and don't transfer.
7. **Backup, recovery and rollout.** Serialize any extra cross-source mappings with source IDs and URLs (not local row IDs). Restore into a clean app and over an existing item without discarding confirmed links or original progress. Leave a simple rollback path to original chapter presentation.

## Acceptance matrix (must pass before Milestone B release)

| Test | Expected result |
| --- | --- |
| Same numbered chapter on two confirmed sources | One logical row; both clickable source variants; original URLs preserved |
| Sources differ by decimal, part, scanlator, title, or volume | No false merge; uncertainty made visible |
| Special/extra chapters, chapter 0, invalid/blank chapter number | No invented gaps, canonical numbering or mistaken progress propagation |
| Alternate source missing chapter or source extension gone | Original choice retained; gap/unknown classified accurately; manual override possible |
| Timeout/rate-limit/source error | No permanent source death, no duplicate updates, no destructive switch |
| Preferred source becomes unavailable | Another confirmed variant suggested, with provenance and a way back |
| Bookmarked/read/in-progress chapter switched to confirmed equivalent | Logical progress persists; history and local source records preserved |
| Equivalence uncertain | Progress stays with original variant; user can pick a source manually |
| Existing downloads and offline chapters | No lost files or broken old chapter list |
| Backup round-trip, restore into existing and fresh library | No lost links, source IDs/URLs, bookmarks, read history or selected-source preferences |
| Feature off or rollback to original view | Old source-specific data still fully usable |
| One title with up to 24 installed catalogues | Bounded work, cancellation, responsiveness and no automatic extension installation |
| Signed upgrade on a disposable emulator, then actual phone | Same package/signature, higher version, retained app data; user-approved device test |

## Required gates

- Milestone A draft PR #3 has passing JVM/Android, signed-upgrade smoke and approved device backup/restore check.
- Unit tests cover reconciliation, ambiguity, numbering and progress safety; integration tests cover backups.
- Controlled Android APK builds and tests finish successfully.
- Manual test uses a small, disposable sample manga; the existing J2K app remains untouched.
- User reviews a demo of unified chapter rows and source choice before the behavior is enabled by default.

**Not in Milestone B:** automated link discovery of an unconfirmed series, extension installation, deletion/merging of unrelated library entries, universal progress synchronization across ambiguous matches, and a new separate companion app.
