package eu.kanade.tachiyomi.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryAuditPlannerTest {
    private fun entry(
        id: Long, source: Long = id, title: String = "The Long Journey",
        author: String? = "Alice", read: Int = 0, missing: Boolean = false,
    ) = LibraryAuditPlanner.Entry(
        id = id, sourceId = source, url = "/manga/$id", title = title,
        originalTitle = title, author = author, read = read, unread = 3,
        bookmarks = 0, sourceInstalled = !missing,
        sourceNeedsAttention = false, activeUpdateFailure = false, linkedSources = 0,
    )

    @Test fun exactTitleDifferentSourcesBecomeReviewGroup() {
        val groups = LibraryAuditPlanner.duplicateGroups(listOf(entry(1), entry(2)))
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().copies.size)
    }

    @Test fun differentKnownAuthorsNeverBecomeSameSeriesAutomatically() {
        assertTrue(LibraryAuditPlanner.duplicateGroups(listOf(
            entry(1, author = "Alice"), entry(2, author = "Bob"),
        )).isEmpty())
    }

    @Test fun shortAmbiguousSeriesNamesAreNotGrouped() {
        assertTrue(LibraryAuditPlanner.duplicateGroups(listOf(
            entry(1, title = "IT"), entry(2, title = "It"),
        )).isEmpty())
    }

    @Test fun duplicateCategoryRowsWithSameIdAreDeduplicated() {
        assertTrue(LibraryAuditPlanner.duplicateGroups(listOf(entry(1), entry(1))).isEmpty())
        assertEquals(1, LibraryAuditPlanner.audit(listOf(entry(1), entry(1))).uniqueEntries)
    }

    @Test fun sameSourceTitleDoesNotCountAsMultiSourceDuplicate() {
        assertTrue(LibraryAuditPlanner.duplicateGroups(listOf(
            entry(1, source = 3), entry(2, source = 3),
        )).isEmpty())
    }

    @Test fun primaryPrefersOriginalProgress() {
        val group = LibraryAuditPlanner.duplicateGroups(listOf(entry(1, read = 20), entry(2, read = 0))).single()
        assertEquals(1L, group.suggestedPrimary.id)
    }

    @Test fun countsIssuesWithoutDeletingData() {
        val missing = entry(1, missing = true).copy(activeUpdateFailure = true)
        val broken = entry(2).copy(sourceNeedsAttention = true, read = 0, unread = 0, linkedSources = 2)
        val audit = LibraryAuditPlanner.audit(listOf(missing, broken))
        assertEquals(1, audit.missingExtensions.size)
        assertEquals(1, audit.failedUpdates.size)
        assertEquals(1, audit.emptyChapterLists.size)
        assertEquals(1, audit.sourcesNeedingAttention.size)
        assertEquals(2, audit.confirmedLinks)
        assertEquals(1, audit.linkedEntries.size)
    }

    @Test fun onlyCrossSourceSameTitleMayBeLinkedAfterManualApproval() {
        val a = entry(1)
        assertTrue(LibraryAuditPlanner.canLink(a, entry(2)))
        assertFalse(LibraryAuditPlanner.canLink(a, entry(2, source = 1)))
        assertFalse(LibraryAuditPlanner.canLink(a, entry(2, title = "Another Series")))
        assertFalse(LibraryAuditPlanner.canLink(a, entry(2, author = "Bob")))
    }

    @Test fun unknownAuthorDoesNotBridgeDifferentKnownAuthorsIntoOneGroup() {
        val groups = LibraryAuditPlanner.duplicateGroups(listOf(
            entry(1, author = "Alice"), entry(2, author = "Bob"), entry(3, author = null),
        ))
        assertTrue(groups.isEmpty())
    }
}
