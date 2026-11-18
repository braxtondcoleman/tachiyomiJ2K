package eu.kanade.tachiyomi.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedChapterPlannerTest {
    private fun v(
        source: Long,
        name: String,
        number: Float,
        url: String = "/chapter/$source/$name",
        preferred: Boolean = false,
        available: Boolean = true,
        attention: Boolean = false,
        read: Boolean = false,
        bookmarked: Boolean = false,
    ) = UnifiedChapterPlanner.Variant(
        sourceId = source,
        mangaUrl = "/manga/$source",
        chapterUrl = url,
        chapterName = name,
        chapterNumber = number,
        isPrimary = source == 1L,
        isPreferred = preferred,
        isAvailable = available,
        needsAttention = attention,
        read = read,
        bookmarked = bookmarked,
    )

    @Test fun sameChapterNumberMergesOnlyAcrossDistinctSources() {
        val result = UnifiedChapterPlanner.reconcile(
            listOf(v(1, "Chapter 12", 12f), v(2, "Ch. 12", 12f)),
        )
        assertEquals(1, result.rows.size)
        assertEquals(2, result.rows.single().variants.size)
        assertEquals(1, result.matchedRows)
    }

    @Test fun conflictingTitlesStaySeparateAndFlagged() {
        val result = UnifiedChapterPlanner.reconcile(
            listOf(v(1, "Chapter 12: The Beginning", 12f), v(2, "Chapter 12: The Ending", 12f)),
        )
        assertEquals(2, result.rows.size)
        assertEquals(2, result.uncertainRows)
        assertTrue(result.rows.all { it.ambiguous })
    }

    @Test fun twoScanlationsFromOneSourceNeverMerge() {
        val result = UnifiedChapterPlanner.reconcile(
            listOf(
                v(1, "Chapter 12", 12f, "/ch12-english"),
                v(1, "Chapter 12", 12f, "/ch12-other"),
            ),
        )
        assertEquals(2, result.rows.size)
        assertEquals(0, result.matchedRows)
    }

    @Test fun volumesAndPartNumbersAreNotMistakenForEquivalents() {
        val result = UnifiedChapterPlanner.reconcile(
            listOf(
                v(1, "Vol. 1 Chapter 5", 5f),
                v(2, "Vol. 2 Chapter 5", 5f),
                v(3, "Chapter 5 Part 2", 5f),
            ),
        )
        assertEquals(3, result.rows.size)
        assertEquals(0, result.matchedRows)
    }

    @Test fun specialAndUnnumberedChaptersStaySeparate() {
        val result = UnifiedChapterPlanner.reconcile(
            listOf(
                v(1, "Special Chapter 1", 1f),
                v(2, "Special Chapter 1", 1f),
                v(3, "Prologue", -1f),
                v(4, "Chapter 0", 0f),
                v(5, "Chapter NaN", Float.NaN),
            ),
        )
        assertEquals(5, result.rows.size)
        assertTrue(result.rows.all { it.number == null })
        assertTrue(result.possibleMissingNumbers.isEmpty())
    }

    @Test fun sourcePreferenceHonoredOnlyForAvailableHealthyVariant() {
        val healthy = v(1, "Chapter 8", 8f)
        val preferred = v(2, "Chapter 8", 8f, preferred = true, attention = true)
        val row = UnifiedChapterPlanner.reconcile(listOf(healthy, preferred)).rows.single()
        assertEquals(healthy.portableId, row.recommended?.portableId)

        val preferredHealthy = preferred.copy(needsAttention = false)
        val next = UnifiedChapterPlanner.reconcile(listOf(healthy, preferredHealthy)).rows.single()
        assertEquals(preferredHealthy.portableId, next.recommended?.portableId)

        val missing = UnifiedChapterPlanner.reconcile(
            listOf(healthy.copy(isAvailable = false), preferred.copy(isAvailable = false)),
        ).rows.single()
        assertNull(missing.recommended)
    }

    @Test fun progressAndOriginalIdentityNeverDiscarded() {
        val original = v(1, "Chapter 4", 4f, "/original", read = true, bookmarked = true)
        val alternative = v(2, "Chapter 4", 4f, "/alternative")
        val row = UnifiedChapterPlanner.reconcile(listOf(original, alternative)).rows.single()
        assertEquals(setOf(original.portableId, alternative.portableId), row.variants.map { it.portableId }.toSet())
        assertTrue(row.isRead)
        assertTrue(row.isBookmarked)
        assertFalse(alternative.read)
        assertFalse(alternative.bookmarked)
    }

    @Test fun exactPortableIdentityIsDeduplicatedWithoutRewriting() {
        val original = v(1, "Chapter 1", 1f)
        val result = UnifiedChapterPlanner.reconcile(listOf(original, original.copy(read = true)))
        assertEquals(1, result.rows.size)
        assertEquals(original, result.rows.single().variants.single())
    }

    @Test fun numberingGapsAreHintsAndBounded() {
        val result = UnifiedChapterPlanner.reconcile(
            listOf(v(1, "Chapter 1", 1f), v(1, "Chapter 2", 2f), v(1, "Chapter 4", 4f)),
        )
        assertEquals(listOf(3), result.possibleMissingNumbers)
        val huge = UnifiedChapterPlanner.reconcile(
            listOf(v(1, "Chapter 1", 1f), v(1, "Chapter 2", 2f), v(1, "Chapter 100", 100f)),
        )
        assertTrue(huge.possibleMissingNumbers.isEmpty())
    }

    @Test fun outputCapDoesNotLoseWholeSourceVariantsFromARow() {
        val chapters = (1..180).flatMap { number ->
            listOf(v(1, "Chapter $number", number.toFloat()), v(2, "Chapter $number", number.toFloat()))
        }
        val result = UnifiedChapterPlanner.reconcile(chapters, maxRows = 150)
        assertEquals(150, result.rows.size)
        assertEquals(180, result.matchedRows)
        assertTrue(result.rows.all { it.variants.size == 2 })
        assertEquals(180f, result.rows.first().number)
    }
}
