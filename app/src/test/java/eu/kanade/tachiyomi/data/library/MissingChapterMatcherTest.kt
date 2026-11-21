package eu.kanade.tachiyomi.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissingChapterMatcherTest {
    @Test fun onlyRealMissingNumberedChaptersCount() {
        assertEquals(
            listOf(12, 14),
            MissingChapterMatcher.exactNumbers(
                listOf(12f, 12f, 12.5f, 13.5f, 14.0001f, -1f, Float.NaN, Float.POSITIVE_INFINITY),
                setOf(12, 13, 14),
            ),
        )
    }

    @Test fun chapterCountDoesNotFabricateMissingEntries() {
        assertTrue(MissingChapterMatcher.exactNumbers(listOf(20f, 21f, 22f), setOf(12)).isEmpty())
    }

    @Test fun onlyRequestedNumbersAreReturnedInOrder() {
        assertEquals(listOf(3, 5), MissingChapterMatcher.exactNumbers(listOf(5f, 3f, 7f), setOf(3, 5)))
    }

    @Test fun usesNamedChaptersWhenExtensionLeavesNumberUnset() {
        assertEquals(
            listOf(7, 12, 14),
            MissingChapterMatcher.exactNumbersFromChapters(
                listOf(
                    -1f to "Chapter 12: A New Start",
                    -1f to "Ch. 14",
                    -1f to "Episode 7",
                    -1f to "Chapter 12.5 Extra",
                    -1f to "Special Chapter 9",
                ),
                setOf(7, 9, 12, 14),
            ),
        )
    }

    @Test fun neverTruncatesFractionalChaptersOrGuessesVolumeNumbers() {
        assertTrue(
            MissingChapterMatcher.exactNumbersFromChapters(
                listOf(
                    -1f to "Volume 12",
                    -1f to "Chapter 12.5",
                    12.5f to "Chapter 12",
                    -1f to "Some title 12",
                    Float.NaN to "Bonus Chapter 12",
                ),
                setOf(12),
            ).isEmpty(),
        )
    }

    @Test fun allowsAlternateChapterLabellingAndLeadingZeros() {
        assertEquals(42, MissingChapterMatcher.chapterNumber(-1f, "Chapter 042: Prelude"))
        assertEquals(42, MissingChapterMatcher.chapterNumber(-1f, "第42話"))
        assertEquals(42, MissingChapterMatcher.chapterNumber(-1f, "Capítulo 42"))
    }
}
