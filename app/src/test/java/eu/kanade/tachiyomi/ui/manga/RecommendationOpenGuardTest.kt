package eu.kanade.tachiyomi.ui.manga

import eu.kanade.tachiyomi.data.database.models.MangaImpl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationOpenGuardTest {
    private fun candidate(id: Long? = null, url: String = "/series/one", source: Long = 1001L): MangaImpl =
        MangaImpl().apply {
            this.id = id
            this.url = url
            this.source = source
            title = "Some recommended series"
            favorite = false
        }

    @Test fun neverOpensTransientRecommendationWithoutDatabaseId() {
        assertFalse(RecommendationOpenGuard.canOpen(candidate()))
        assertFalse(RecommendationOpenGuard.canOpen(candidate(id = 0L)))
        assertFalse(RecommendationOpenGuard.canOpen(candidate(id = -1L)))
        assertTrue(RecommendationOpenGuard.canOpen(candidate(id = 42L)))
    }

    @Test fun incompleteRecommendationsCannotLaunchDetails() {
        assertFalse(RecommendationOpenGuard.canOpen(candidate(id = 42L, url = "")))
        assertFalse(RecommendationOpenGuard.canOpen(candidate(id = 42L, source = -1L)))
        assertFalse(RecommendationOpenGuard.canOpen(null))
    }

    @Test fun aValidRecordRemainsNotFavoritedUntilUserAddsIt() {
        val newTitle = candidate(id = 33L)
        assertTrue(RecommendationOpenGuard.canOpen(newTitle))
        assertFalse(newTitle.favorite)
    }
}
