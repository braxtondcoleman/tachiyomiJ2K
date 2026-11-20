package eu.kanade.tachiyomi.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryPlannerTest {
    private fun manga(id: Long, title: String, genres: String = "", author: String? = null, read: Int = 0, owned: Boolean = false) =
        DiscoveryPlanner.MangaInfo(id, "/manga/$id", title, author, DiscoveryPlanner.genres(genres), read, owned)

    @Test fun genreProfileWeightsReadFavoritesMoreThanUnread() {
        val profile = DiscoveryPlanner.profile(listOf(
            manga(1, "Blue", "Fantasy, Romance", read = 12, owned = true),
            manga(2, "Red", "Mystery", owned = true),
        ))
        assertEquals(3, profile.genreWeights["fantasy"])
        assertEquals(1, profile.genreWeights["mystery"])
    }

    @Test fun recommendationsExplainMatchedGenresAndAuthors() {
        val profile = DiscoveryPlanner.profile(listOf(manga(1, "Favorite", "Fantasy, Drama", "Jane Doe", 6, true)))
        val ranked = DiscoveryPlanner.recommend(profile, listOf(
            manga(2, "Candidate", "Fantasy, Drama", "Jane Doe"),
            manga(3, "Unrelated", "Sports"),
        ))
        assertEquals("Candidate", ranked.first().manga.title)
        assertTrue(ranked.first().personalized)
        assertTrue(ranked.first().reasons.any { "reading interests" in it })
    }

    @Test fun sourceFailuresLowerRecommendation() {
        val profile = DiscoveryPlanner.profile(listOf(manga(1, "Read", "Fantasy", owned = true)))
        val a = manga(2, "Good", "Fantasy")
        val b = manga(3, "Failing", "Fantasy").copy(sourceHealthy = false)
        val ranked = DiscoveryPlanner.recommend(profile, listOf(b, a))
        assertEquals("Good", ranked.first().manga.title)
        assertTrue(ranked.last().reasons.any { "failures" in it })
    }

    @Test fun alreadyOwnedSeriesIsExcludedEvenFromAnotherSource() {
        val profile = DiscoveryPlanner.profile(listOf(manga(1, "The Hero", "Fantasy", owned = true)))
        val candidate = manga(2, "The Hero", "Fantasy")
        assertTrue(DiscoveryPlanner.recommend(profile, listOf(candidate)).isEmpty())
    }

    @Test fun localRelatedTitlesCanBeShownWhenExplicitlyAllowed() {
        val seed = manga(1, "One", "Mystery", "Author A", owned = true)
        val related = manga(2, "Two", "Mystery", "Author A", owned = true)
        val profile = DiscoveryPlanner.profile(listOf(seed, related))
        val ranked = DiscoveryPlanner.recommend(profile, listOf(seed, related), seed, includeOwned = true)
        assertEquals(listOf("Two"), ranked.map { it.manga.title })
    }

    @Test fun unknownMetadataMustNotBePresentedAsPersonalized() {
        val profile = DiscoveryPlanner.profile(listOf(manga(1, "Read", "Fantasy", owned = true)))
        val s = DiscoveryPlanner.recommend(profile, listOf(manga(2, "Popular Unrelated"))).single()
        assertFalse(s.personalized)
        assertTrue(s.reasons.single().contains("relevance unverified"))
    }

    @Test fun genericGenresDoNotCreateSpuriousPersonalMatches() {
        val genres = DiscoveryPlanner.genres("Manga, Full Color, Romance, Webtoon")
        assertEquals(setOf("romance"), genres)
    }

    @Test fun duplicateSourceIdentityDoesNotAppearTwice() {
        val profile = DiscoveryPlanner.Profile(emptyMap(), emptyMap(), emptySet())
        val manga = manga(2, "Example")
        assertEquals(1, DiscoveryPlanner.recommend(profile, listOf(manga, manga)).size)
    }

    @Test fun recommendationListIsBounded() {
        val profile = DiscoveryPlanner.Profile(emptyMap(), emptyMap(), emptySet())
        val titles = (1L..250L).map { manga(it, "Popular $it") }
        assertEquals(30, DiscoveryPlanner.recommend(profile, titles).size)
    }

    @Test fun droppedMangaNoLongerCountsAsPositiveGenreSignal() {
        val dropped = manga(1, "Boring", "Fantasy", owned = true).copy(
            feedbackStatus = ReadingFeedback.Status.DROPPED,
            dropReasons = setOf(ReadingFeedback.DropReason.BORING),
        )
        val profile = DiscoveryPlanner.profile(listOf(dropped))
        assertTrue((profile.genreWeights["fantasy"] ?: 0) < 0)
    }

    @Test fun explicitlyTaggedSexualViolenceIsFilteredWhenRejected() {
        val disliked = manga(1, "Stopped", "Fantasy", owned = true).copy(
            feedbackStatus = ReadingFeedback.Status.DROPPED,
            dropReasons = setOf(ReadingFeedback.DropReason.SEXUAL_VIOLENCE),
        )
        val profile = DiscoveryPlanner.profile(listOf(disliked))
        val flagged = manga(2, "Flagged", "Fantasy, Sexual Violence")
        val untagged = manga(3, "Untagged", "Fantasy")
        assertEquals(listOf("Untagged"), DiscoveryPlanner.recommend(profile, listOf(flagged, untagged)).map { it.manga.title })
    }

    @Test fun previouslyDroppedRelatedTitleCanResurfaceWithExplanation() {
        val seed = manga(1, "Loved", "Mystery", owned = true)
        val dropped = manga(2, "Earlier Drop", "Mystery", owned = true)
            .copy(feedbackStatus = ReadingFeedback.Status.DROPPED)
        val profile = DiscoveryPlanner.profile(listOf(seed, dropped))
        val found = DiscoveryPlanner.recommend(profile, listOf(dropped), seed, includeOwned = true)
        assertEquals(1, found.size)
        assertTrue(found.first().reasons.any { it.contains("Previously dropped") })
    }

    @Test fun malRelatedMangaGetsAnExplainableBonus() {
        val profile = DiscoveryPlanner.Profile(emptyMap(), emptyMap(), emptySet())
        val generic = manga(1, "Popular but not related")
        val related = manga(2, "MAL recommended").copy(malRecommendationVotes = 6)
        val ranked = DiscoveryPlanner.recommend(profile, listOf(generic, related))
        assertEquals("MAL recommended", ranked.first().manga.title)
        assertTrue(ranked.first().reasons.any { "MyAnimeList" in it })
    }
}
