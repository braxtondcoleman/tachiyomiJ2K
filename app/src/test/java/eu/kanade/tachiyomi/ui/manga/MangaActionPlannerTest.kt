package eu.kanade.tachiyomi.ui.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaActionPlannerTest {
    @Test fun favoriteOnlineTitleSeesAllFiveActionsInReaderOrder() {
        assertEquals(
            listOf(
                MangaActionPlanner.Action.CHAPTERS,
                MangaActionPlanner.Action.FIND_SOURCE,
                MangaActionPlanner.Action.LINKED,
                MangaActionPlanner.Action.RELATED,
                MangaActionPlanner.Action.FIX,
            ),
            MangaActionPlanner.options(isFavorite = true, isLocal = false),
        )
    }

    @Test fun notFavoritedMangaHasNoMisleadingLinkedChapterActions() {
        val actions = MangaActionPlanner.options(isFavorite = false, isLocal = false)
        assertFalse(actions.contains(MangaActionPlanner.Action.CHAPTERS))
        assertFalse(actions.contains(MangaActionPlanner.Action.LINKED))
        assertTrue(actions.contains(MangaActionPlanner.Action.FIND_SOURCE))
        assertTrue(actions.contains(MangaActionPlanner.Action.RELATED))
    }

    @Test fun localMangaIsNotAskedToFixWebsiteOrInstallExtensions() {
        assertEquals(
            listOf(MangaActionPlanner.Action.RELATED),
            MangaActionPlanner.options(isFavorite = true, isLocal = true),
        )
    }

    @Test fun allActionsHavePlainLanguageTitlesAndExplanations() {
        MangaActionPlanner.Action.values().forEach { action ->
            assertFalse(action.title.isBlank())
            assertFalse(action.description.isBlank())
            assertTrue(MangaActionPlanner.row(action).contains("\n"))
            assertFalse(action.title.contains("migration", ignoreCase = true))
        }
    }

    @Test fun noActionIsRepeatedInAnyContext() {
        for (favorite in listOf(false, true)) for (local in listOf(false, true)) {
            val actions = MangaActionPlanner.options(favorite, local)
            assertEquals(actions.size, actions.distinct().size)
        }
    }
}
