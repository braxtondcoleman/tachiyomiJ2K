# Unified J2K reading experience: implementation and release gates

## Implemented on this development branch

- Library long-press opens native quick actions: Reading, Completed, Dropped, On Hold, Plan to Read, clear, edit categories, and enter existing multi-select.
- Dropped has optional multiple feedback reasons plus a private note; settings are stored in a namespaced manga memo, independent of source publication status and chapter read flags.
- Personal reading statuses are shown as a subtle cover badge (and in list subtitles). Existing source metadata refresh preserves feedback.
- Changes have an Undo snackbar. No manga is un-favorited or migrated; no chapters, downloads, bookmarks, tracker records or categories are rewritten by status selection.
- A Library tools entry refreshes **already-linked** MyAnimeList entries via J2K's existing tracking update worker. It deliberately does not claim full list matching/import or synchronizing local personal feedback to MAL.
- Unit tests exercise metadata encoding, status transitions and refresh persistence.

## Pending design work and high-risk implementation

### Native unified chapter list (the required end state)

Replace the normal single-source MangaDetailsPresenter chapter timeline with canonical chapter groups from all **confirmed** linked sources. Each group must preserve provenance, original IDs, chapter ordering, volume/part/scanlation distinctions, local downloads, bookmark/read state and source choice. A simple mix of ChapterItem values is unsafe: MangaDetailsController.openChapter currently passes the primary manga, not the ChapterItem.manga, to ReaderActivity. Alternate chapter rows require source-aware click, navigation, download, next-chapter and reading-progress handling.

Default selection: primary or explicitly preferred healthy, verified same-chapter variant; if missing, automatically use a proven alternate. Only show a short overlay when alternate is used, with accurate language distinguishing confirmed missing from unknown/outage. Long press selects a specific source. No silent cross-source progress rewriting or unrelated series merging.

### Recovery coordinator

Share diagnosis and bounded retries across library chapter updates, manga details, reader image failures and extension diagnostics. Distinguish 404 manga, 404 chapter, 404 image, 401/403 website challenge, missing extension, parser error and temporary network outage. Reuse existing SourceRecoveryPlanner, ReaderContinuityPlanner, SmartSourceFinder and SourceHealthLedger; coordinator orchestration must not become a single giant file. Check linked sources first, then propose verified new matches for approval. Background library repair is explicit and rate-limit-aware.

### Full MyAnimeList synchronization and recommendations

Import MAL list only with a preview of matched, unmatched and conflicting series (one canonical manga per merged group). Do not guess title-only matches or replace local history. Merge status/progress by explicit user-approved conflict policy. Separate local dropped reasons, which should not be uploaded to MAL. Explore API /manga/{id}?fields=recommendations and use MAL suggestions with local positive/negative preference ranking.

### Release tests

- JVM tests and Android debug build CI
- Backups of linked sources and personal feedback; restore on separate test package
- No regressions in original J2K installation
- Signed v114 → v115 same-key emulator upgrade and real Samsung QA
- Permanent user-owned signing key before daily-use release
- No automatic changes to master, production signing, or user's phone

**Explicitly excluded:** English/Japanese split-language reader.
