# Phase H — library health, duplicate review and safe source-linking

Implemented under **Library → Library health and duplicate review**:

- Counts favorite library titles only once across categories, with dedicated panels for missing extensions, unresolved chapter update failures, sources needing attention, no stored chapters, and confirmed source links.
- Conservative duplicate groups require an **exact normalized original title** with at least five characters, distinct source IDs and non-conflicting known authors. Similar generic/translated titles remain ungrouped unless you review them separately.
- Per group: inspect individual manga, source, read/unread counts, bookmarks, and cached download count (queried only when chosen, not during bulk audit).
- Suggest the primary copy according to existing read progress, bookmarks and source health. Recommendation is not verification of same series.
- Optional **Review source link → Confirm link** adds only a portable, backup-covered alternate link after explicit human confirmation. Both manga remain favorites; no titles, chapters, downloads, history or category assignments are removed or synchronized.
- Original legacy duplicate summary remains available; this adds actionable navigational review.
- Audit is read-only except the separate confirmed manual link action. Results show at most 150 rows per section, while summary counts cover the whole unique library.

## Tests / acceptance

- Planner tests cover exact duplicates, author conflicts, ambiguous short titles, same-source entries, repeated category rows, source health and suggested-primary choice.
- Automated JVM, Android compilation, signed-key emulator upgrade required before release.
- Device: large library, missing extensions, mixed read progress, duplicate review, safe linking, download counts, and backup restore.
- Permanent user-owned signing key and real Samsung upgrade still required before long-term installation.
