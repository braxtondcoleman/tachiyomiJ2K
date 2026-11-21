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
}
