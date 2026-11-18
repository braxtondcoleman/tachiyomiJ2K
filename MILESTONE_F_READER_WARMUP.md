# Phase F-lite — smoother, eager reading (no download overhaul)

This phase intentionally skips bulk downloads and offline-queue management.

- As soon as an HTTP chapter's page list is ready, warm the first **up to 2× the existing preload-page preference**, capped at **24 pages** in the existing ChapterCache and low-priority page queue.
- The currently requested visible image outranks warming. The loader deduplicates queued images as user reading catches up.
- Pager and webtoon start preparing the next chapter as much as 12 pages before the end with default 6-page preload; thresholds remain bounded to 24.
- Preload page setting of zero disables additional eager image warming and keeps original five-page next-chapter behavior.
- On chapter changes/recycling, existing loader cancellation and temporary cache are reused. No persistent bulk download, no automatic remote source switching, no new image decoding buffers or altered reader data.
- Unit tests cover caps, short chapters, disabled preload, and early next-chapter transitions.

## Device checks
1. Switch from webtoon/pager modes with an online source and compare page-turn stalls before/after.
2. Test flaky Wi-Fi, cellular data use, huge chapters, and preloading disabled.
3. Verify backing out cancels warming, does not lose download history, and doesn't make full-image memory usage grow unbounded.
4. All JVM, Android and signed-upgrade emulator CI must pass on final F-lite commit.
