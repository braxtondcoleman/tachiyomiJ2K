package eu.kanade.tachiyomi.data.library

import java.util.Locale

/**
 * Explainable discovery from user's already-stored manga metadata.
 * Does not learn from private browsing activity or send profile data away.
 */
object DiscoveryPlanner {
    data class MangaInfo(
        val sourceId: Long,
        val url: String,
        val title: String,
        val author: String?,
        val genres: Set<String>,
        val readChapters: Int = 0,
        val inLibrary: Boolean = false,
        val sourceHealthy: Boolean = true,
        val popularityIndex: Int = 0,
        val feedbackStatus: ReadingFeedback.Status? = null,
        val dropReasons: Set<ReadingFeedback.DropReason> = emptySet(),
        val malRecommendationVotes: Int = 0,
    )

    data class Profile(
        val genreWeights: Map<String, Int>,
        val authorWeights: Map<String, Int>,
        val titles: Set<String>,
        val avoidSexualViolence: Boolean = false,
        val avoidOpSlop: Boolean = false,
    )
    data class Suggestion(val manga: MangaInfo, val score: Int, val reasons: List<String>, val personalized: Boolean)

    private val explicitSexualViolenceTags = setOf(
        "rape", "sexual violence", "sexual assault", "non consensual", "non consensual sex",
    )
    private val explicitOverpoweredTags = setOf("overpowered protagonist", "overpowered mc", "op protagonist")

    private val genericGenres = setOf(
        "manga", "comic", "manhua", "manhwa", "webtoon", "long strip", "english", "korean",
        "japanese", "chinese", "full color", "adaptation", "completed", "ongoing",
    )

    fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    fun genres(raw: String?): Set<String> =
        raw.orEmpty().split(',').map(::normalize)
            .filter { it.length > 2 && it !in genericGenres }
            .take(32).toSet()

    fun profile(library: List<MangaInfo>): Profile {
        val unique = library.distinctBy { Triple(it.sourceId, it.url, normalize(it.title)) }
        val genreScore = mutableMapOf<String, Int>()
        val authorScore = mutableMapOf<String, Int>()
        unique.filter { it.inLibrary }.forEach { manga ->
            val weight = when (manga.feedbackStatus) {
                ReadingFeedback.Status.DROPPED ->
                    if (ReadingFeedback.DropReason.SEXUAL_VIOLENCE in manga.dropReasons) 0 else -2
                ReadingFeedback.Status.COMPLETED -> 4
                ReadingFeedback.Status.PLAN_TO_READ -> 0
                ReadingFeedback.Status.ON_HOLD -> 1
                else -> if (manga.readChapters > 0) 3 else 1
            }
            manga.genres.filterNot { it in genericGenres }.forEach { g ->
                genreScore[g] = (genreScore[g] ?: 0) + weight
            }
            manga.author?.let(::normalize)?.takeIf { it.length >= 4 }?.let { a ->
                authorScore[a] = (authorScore[a] ?: 0) + weight
            }
        }
        return Profile(
            genreScore,
            authorScore,
            unique.filter { it.inLibrary }.map { normalize(it.title) }.toSet(),
            avoidSexualViolence = unique.any { it.inLibrary && ReadingFeedback.DropReason.SEXUAL_VIOLENCE in it.dropReasons },
            avoidOpSlop = unique.any { it.inLibrary && ReadingFeedback.DropReason.OP_SLOP in it.dropReasons },
        )
    }

    fun recommend(
        profile: Profile,
        candidates: List<MangaInfo>,
        seed: MangaInfo? = null,
        limit: Int = 30,
        includeOwned: Boolean = false,
    ): List<Suggestion> {
        require(limit in 1..100)
        val seedGenres = seed?.genres.orEmpty()
        val seedAuthor = seed?.author?.let(::normalize)
        return candidates.asSequence()
            .distinctBy { it.sourceId to it.url }
            .filterNot { seed != null && it.sourceId == seed.sourceId && it.url == seed.url }
            .filter { includeOwned || !it.inLibrary }
            .filterNot { !it.inLibrary && normalize(it.title) in profile.titles }
            // Only exclude when the source explicitly supplies a recognizable content warning.
            // Missing/incorrect source tags can never be treated as a content-safety guarantee.
            .filterNot { profile.avoidSexualViolence && it.genres.any { tag -> tag in explicitSexualViolenceTags } }
            .mapNotNull { candidate ->
                val name = normalize(candidate.title)
                if (name.isBlank()) return@mapNotNull null
                val author = candidate.author?.let(::normalize)
                val sharedSeed = candidate.genres.intersect(seedGenres).size
                val sharedProfile = candidate.genres.map { profile.genreWeights[it] ?: 0 }
                    .sortedDescending().take(4).sum()
                val sameAuthor = seedAuthor != null && author == seedAuthor
                val familiarAuthor = if (author != null) profile.authorWeights[author] ?: 0 else 0
                var score = sharedSeed * 20 + minOf(sharedProfile, 25) * 3
                if (sameAuthor) score += 35
                score += minOf(familiarAuthor, 6) * 5
                if (!candidate.sourceHealthy) score -= 35
                if (profile.avoidOpSlop && candidate.genres.any { it in explicitOverpoweredTags }) score -= 35
                if (candidate.feedbackStatus == ReadingFeedback.Status.DROPPED) score -= 10
                if (candidate.malRecommendationVotes > 0) {
                    score += minOf(50, 15 + candidate.malRecommendationVotes * 2)
                }
                score += maxOf(0, 6 - candidate.popularityIndex / 3) // minor discovery ordering
                val reasons = buildList {
                    if (sharedSeed > 0) add("$sharedSeed genres shared with selected manga")
                    if (sameAuthor) add("Same credited author")
                    if (sharedProfile > 0) add("Matches reading interests from your library")
                    if (familiarAuthor > 0) add("Author already represented in your library")
                    if (candidate.malRecommendationVotes > 0) {
                        add("Recommended by MyAnimeList readers of a tracked manga")
                    }
                    if (!candidate.sourceHealthy) add("Source has recent failures")
                    if (profile.avoidOpSlop && candidate.genres.any { it in explicitOverpoweredTags }) {
                        add("Tagged with overpowered-protagonist content you previously dropped")
                    }
                    if (candidate.feedbackStatus == ReadingFeedback.Status.DROPPED) {
                        add("Previously dropped · consider again if your interests changed")
                    }
                    if (sharedSeed == 0 && sharedProfile == 0 && !sameAuthor && familiarAuthor == 0) {
                        add("Popular on an installed source; relevance unverified")
                    }
                }
                Suggestion(
                    candidate, score, reasons,
                    sharedSeed > 0 || sharedProfile > 0 || sameAuthor || familiarAuthor > 0 ||
                        candidate.malRecommendationVotes > 0,
                )
            }
            .sortedWith(compareByDescending<Suggestion> { it.personalized }.thenByDescending { it.score }.thenBy { it.manga.title })
            .take(limit).toList()
    }
}
