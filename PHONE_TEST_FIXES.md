# Phone-test hotfix: recommendation crash and confusing recovery navigation

The original installed J2K and its library are not modified. This is a new custom debug-test APK built from a draft branch stacked on Phase I.

## Fix: opening recommended manga

- Recommended manga may have existed only as an `SManga` search result. The details controller accepts a manga object but later recreates the screen from a **database row ID**, so opening an unsaved result can cause an immediate crash.
- On selection, check whether the (URL, source ID) pair has a saved record; otherwise create a **non-favorited** manga DB row before navigation. Confirm it has a valid ID, then open details. Mark a new entry as uninitialized so chapters and details will be fetched normally.
- If database insert or retrieval fails, keep the recommendation list open and show a clear error instead of crashing or silently adding anything to the favorites.
- The recommendations results remain open when viewing or canceling a preview, avoiding repeated network scans.
- Unit tests reject transient, zero/negative ID, invalid source and blank URL entries.

## Fix: navigation and auto-fix

- The Library toolbar's wrench-style **Library tools · Fix issues** opens a single persistent navigation sheet.
- First option **Fix issues** shows unresolved source-update failures, links directly to the library audit and extension diagnostics, and lets you open an affected manga.
- Second option **Auto-fix settings** shows two clear switches that **save immediately without resetting the sheet**.
- Safe automatic retries for transient chapter-update errors and automatic in-reader Cloudflare verification prompts default to ON, preserving prior behavior; users may turn them off independently. Normal invisible Cloudflare handling remains part of J2K.
- The title's **Fix a reading problem** screen also links to Auto-fix settings and keeps the issue list open when you inspect diagnostics.
- Auto-fix deliberately does **not** install untrusted extensions, ignore rate-limit Retry-After, delete manga, migrate progress or switch to a different source automatically. Those changes require explicit choices.

## Test checklist

- [ ] Open a *new, previously unseen* recommendation; ensure details and chapters load and app stays open
- [ ] Open an already saved recommendation; no duplicates added
- [ ] Preview, Back to results, preview another, open it; no forced rescan
- [ ] Library > Library tools > Fix issues > inspect an issue > Back to issues > Back to tools
- [ ] Library > Library tools > Auto-fix settings > toggle both switches repeatedly and return; choices persist
- [ ] Manga details > Manga tools > Fix a reading problem > Auto-fix settings and back
- [ ] On and off: verify library temporary network retry behavior and automatic Cloudflare website prompt; manual verification must remain possible
- [ ] No original-app install changes, no deleted downloads/favorites/history, and backup restore remains intact

## Release and upgrade

- [ ] JVM and Android CI successful
- [ ] v114→v115 same-signature disposable-key emulator upgrade successful
- [ ] Real Samsung test and saved backup/restore
- [ ] Permanent private release signing key before daily use

**Debug APK signing caveat:** a standard GitHub Actions debug build may get a different temporary debug key in each run. It may not install over the previous test build. Back up the **custom test app** before uninstalling it, and never uninstall the original J2K. The permanent release will use a user-owned stable signing identity.
