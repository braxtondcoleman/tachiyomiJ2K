package eu.kanade.tachiyomi.data.library

import eu.kanade.tachiyomi.source.model.SChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderContinuityPlannerTest {
    private fun chapter(name: String, number: Float): SChapter = SChapter.create().apply {
        url = "/chapter/$name"
        this.name = name
        chapter_number = number
    }

    private fun option(
        source: Long,
        name: String,
        number: Float,
        preferred: Boolean = false,
        trouble: Boolean = false,
    ) = ReaderContinuityPlanner.Option(
        sourceId = source,
        mangaUrl = "/manga/$source",
        mangaTitle = "Sample",
        sourceName = "Source $source",
        chapter = chapter(name, number),
        isPreferred = preferred,
        needsAttention = trouble,
    )

    private val same = ReaderContinuityPlanner.Direction.SAME_CHAPTER
    private val next = ReaderContinuityPlanner.Direction.NEXT_CHAPTER

    @Test fun sameNumberFromDifferentSourceIsSuggested() {
        val suggestions = ReaderContinuityPlanner.suggest(
            chapter("Chapter 12", 12f), 1L, same,
            listOf(option(2L, "Ch. 12", 12f)),
        )
        assertEquals(1, suggestions.size)
        assertFalse(suggestions.single().needsTitleReview)
    }

    @Test fun differentTitleRequiresReview() {
        val suggestions = ReaderContinuityPlanner.suggest(
            chapter("Chapter 12: The Beginning", 12f), 1L, same,
            listOf(option(2L, "Chapter 12: The Ending", 12f)),
        )
        assertTrue(suggestions.single().needsTitleReview)
    }

    @Test fun onlyConfirmedOtherSourcesAreConsideredByIdentity() {
        val suggestions = ReaderContinuityPlanner.suggest(
            chapter("Chapter 5", 5f), 11L, same,
            listOf(option(11L, "Chapter 5", 5f), option(12L, "Chapter 5", 5f)),
        )
        assertEquals(listOf(12L), suggestions.map { it.option.sourceId })
    }

    @Test fun nextChapterNeverSkipsMultipleNumbers() {
        val suggestions = ReaderContinuityPlanner.suggest(
            chapter("Chapter 5", 5f), 1L, next,
            listOf(option(2L, "Chapter 6", 6f), option(3L, "Chapter 11", 11f)),
        )
        assertEquals(listOf(6f), suggestions.map { it.option.chapter.chapter_number })
    }

    @Test fun decimalFollowingChapterIsAllowed() {
        val suggestions = ReaderContinuityPlanner.suggest(
            chapter("Ch. 5", 5f), 1L, next,
            listOf(option(2L, "Ch. 5.5", 5.5f), option(3L, "Ch. 6", 6f)),
        )
        assertEquals(5.5f, suggestions.first().option.chapter.chapter_number)
    }

    @Test fun specialsAndUnknownChapterNumbersNeverAutoMatch() {
        val suggestions = ReaderContinuityPlanner.suggest(
            chapter("Special 2", 2f), 1L, same,
            listOf(option(2L, "Chapter 2", 2f)),
        )
        assertTrue(suggestions.isEmpty())
        assertTrue(ReaderContinuityPlanner.suggest(
            chapter("Chapter ?", Float.NaN), 1L, next,
            listOf(option(2L, "Chapter 3", 3f)),
        ).isEmpty())
    }

    @Test fun chapterVolumeMismatchIsExcluded() {
        val suggestions = ReaderContinuityPlanner.suggest(
            chapter("Vol 1 Chapter 9", 9f), 1L, same,
            listOf(option(2L, "Vol 2 Chapter 9", 9f)),
        )
        assertTrue(suggestions.isEmpty())
    }

    @Test fun preferredHealthySourceWinsWithoutSilentSwitch() {
        val suggestions = ReaderContinuityPlanner.suggest(
            chapter("Chapter 8", 8f), 1L, same,
            listOf(
                option(2L, "Chapter 8", 8f, preferred = true, trouble = true),
                option(3L, "Chapter 8", 8f),
                option(4L, "Chapter 8", 8f, preferred = true),
            ),
        )
        assertEquals(4L, suggestions.first().option.sourceId)
        assertEquals(2L, suggestions.last().option.sourceId)
    }

    @Test fun emptyPortableIdentityIsRejected() {
        val invalid = option(2L, "Chapter 7", 7f).copy(mangaUrl = "")
        assertTrue(ReaderContinuityPlanner.suggest(chapter("Chapter 7", 7f), 1L, same, listOf(invalid)).isEmpty())
    }

    @Test fun duplicateRemoteChapterIsDeduplicated() {
        val duplicate = option(2L, "Chapter 7", 7f)
        val suggestions = ReaderContinuityPlanner.suggest(
            chapter("Chapter 7", 7f), 1L, same,
            listOf(duplicate, duplicate),
        )
        assertEquals(1, suggestions.size)
    }
}
