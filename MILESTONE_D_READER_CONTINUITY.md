# Phase D — Safe reader continuity across confirmed manga sources

## Implementation

- In the native reader menu select **Continue from linked source**. Choose either **same chapter** (when pages fail) or **next chapter** (at a source's chapter boundary). A page-loader error additionally offers a nonblocking **Find alternate** Snackbar.
- Searches **only existing confirmed links** for the same manga. If reading a Phase B nonfavorite alternate, resolves **exactly one** favorited parent that already links it; ambiguous or unlinked parents are rejected rather than guessed.
- Bounded to 24 linked sources, three concurrent requests, 10-second request timeout, cancelable load. Remote chapter lists are preferred; a failed fetch can show a stored snapshot with an explicit warning.
- **ReaderContinuityPlanner** rejects unknown/unreliable numbering, special chapters, volume mismatch and skipped chapter gaps. Same-number chapters with conflicting descriptive titles require an explicit warning. A proposed next chapter must be at most one chapter number after the current, including decimal parts; options remain source-attributed.
- Preferred/healthy links rank higher, but ranking is **not automatic switching**. Every alternate requires source choice and an **Open** confirmation.
- Opening a selected variant uses the Phase B isolated source-specific record bridge and keeps the original source, downloads, links, history and read states intact. Saves current reader progress before handing off to a native reader instance.

## Deliberate limits

This is **suggested seamless continuity**, not autonomous background fallback. The user confirms source and chapter, especially where chapter titles conflict. Sources requiring login may not return chapters. The current chapter's *same-source* next/previous controls continue to behave as before. No chapter record is overwritten or progress copied to a different source.

## Required release tests

- [ ] JVM planner tests and full Android app compile pass on final D commit.
- [ ] Temporary-key Android v114→115 upgrade smoke passes.
- [ ] Real extensions: failed current chapter, chapter-end fallback, decimal chapters, volume conflict, missing/disabled extension, login/429 timeout, canceled search, alternate without a favorite parent.
- [ ] Real-phone reader navigation, progress persistence, back behavior and download preservation.
- [ ] Permanent signing secret and restore tests for production release.

## Phase E follows

Source-aware extension finder, obsolete/update and untrusted warnings, configured-repository verification, missing-source diagnostics and installer-state help, without silently trusting or installing extensions.
