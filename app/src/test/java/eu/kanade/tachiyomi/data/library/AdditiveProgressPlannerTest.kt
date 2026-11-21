package eu.kanade.tachiyomi.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdditiveProgressPlannerTest {
    @Test fun remoteAheadNeverRewinds() {
        val entries = listOf(AdditiveProgressPlanner.KnownProgress(1, "Series", 80, 52))
        assertTrue(AdditiveProgressPlanner.preview(entries).isEmpty())
        assertEquals(80, AdditiveProgressPlanner.safeTarget(52, 80))
    }

    @Test fun multipleSourcesCountHighestChapterNotSum() {
        val changes = AdditiveProgressPlanner.preview(listOf(
            AdditiveProgressPlanner.KnownProgress(1, "Series", 40, 52),
            AdditiveProgressPlanner.KnownProgress(1, "Series", 40, 48),
        ))
        assertEquals(1, changes.size)
        assertEquals(52, changes.single().newRead)
    }

    @Test fun outdatedPreviewCannotOverwriteFreshRemoteProgress() {
        assertEquals(95, AdditiveProgressPlanner.safeTarget(52, 95))
        assertEquals(52, AdditiveProgressPlanner.safeTarget(52, 44))
    }

    @Test fun unchangedAndUnlinkedTitlesNotAdded() {
        assertTrue(AdditiveProgressPlanner.preview(listOf(
            AdditiveProgressPlanner.KnownProgress(0, "Unlinked", 0, 50),
            AdditiveProgressPlanner.KnownProgress(4, "Unchanged", 20, 20),
        )).isEmpty())
    }
}
