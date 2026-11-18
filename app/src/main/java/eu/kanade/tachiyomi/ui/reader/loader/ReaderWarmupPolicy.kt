package eu.kanade.tachiyomi.ui.reader.loader

/**
 * Keep image loading ahead of the reader without caching an entire huge chapter.
 *
 * Reuses the existing "preload pages" preference: zero disables additional eager
 * warming, while the default of six preloads up to twelve current-chapter pages.
 * All images use the existing ChapterCache and queue; no new offline copies.
 */
object ReaderWarmupPolicy {
    fun initialPages(configuredAhead: Int, total: Int): Int {
        if (configuredAhead <= 0 || total <= 0) return 0
        return minOf(total, configuredAhead.coerceAtMost(12) * 2, 24)
    }

    fun nextChapterThreshold(configuredAhead: Int): Int {
        if (configuredAhead <= 0) return 5 // keep legacy chapter-transition behavior
        return (configuredAhead.coerceAtMost(12) * 2).coerceIn(8, 24)
    }

    fun shouldWarmNext(pageNumber: Int, pageCount: Int, configuredAhead: Int): Boolean =
        pageCount > 0 && pageNumber >= (pageCount - nextChapterThreshold(configuredAhead)).coerceAtLeast(1)
}
