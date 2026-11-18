package eu.kanade.tachiyomi.ui.reader.loader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderWarmupPolicyTest {
    @Test fun defaultWarmsTwelvePagesWithoutWholeChapterDownload() {
        assertEquals(12, ReaderWarmupPolicy.initialPages(6, 300))
    }
    @Test fun neverExceedsBoundedTwentyFour() {
        assertEquals(24, ReaderWarmupPolicy.initialPages(50, 200))
    }
    @Test fun shortChapterOnlyWarmsItsPages() {
        assertEquals(4, ReaderWarmupPolicy.initialPages(6, 4))
    }
    @Test fun zeroDisablesAdditionalEagerImages() {
        assertEquals(0, ReaderWarmupPolicy.initialPages(0, 45))
    }
    @Test fun nextChapterStartsEarlierAtDefaultSetting() {
        assertTrue(ReaderWarmupPolicy.shouldWarmNext(38, 50, 6))
        assertFalse(ReaderWarmupPolicy.shouldWarmNext(22, 50, 6))
    }
    @Test fun smallPreloadPreferenceRetainsConservativeRange() {
        assertEquals(8, ReaderWarmupPolicy.nextChapterThreshold(2))
        assertEquals(5, ReaderWarmupPolicy.nextChapterThreshold(0))
    }
}
