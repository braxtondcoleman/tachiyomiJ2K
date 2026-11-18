# Phase G — related series and locally ranked discovery

Implemented:
- **Discover similar manga** in manga details, including already-owned related titles on other sources.
- **Discover manga for me** in Library settings.
- User-triggered discovery reads up to 8 installed, language-enabled, nonhidden sources (3 simultaneous popular-list fetches with 9-second timeouts, up to 12 entries each).
- Enriches no more than 24 public manga detail entries for tags/author (2 concurrent, 7-second timeout), then scores locally. The library profile is **never transmitted as an extension search query**.
- Recommendation reasons: overlapping genres, author match, weighted read interests and source health. Generic popular items with no reliable metadata are clearly labelled **relevance unknown**.
- Excludes duplicates already owned by normalized title; limits results, allows cancel, and only opens candidate details after explicit selection.
- No automatic favorite, manga source linkage, migration, read-state changes, tracking or downloads.
- Unit tests cover profile read weighting, genre match, authors, bad source health, duplicates, unknown metadata, bounded results.

Release checks: JVM/Android CI, signed-upgrade emulator, device-only popular feeds, cancellation, private metadata, browsing from settings and manga details, compatibility with multiple sources.
