package eu.kanade.tachiyomi.data.library

import eu.kanade.tachiyomi.source.model.SChapter
import java.util.Locale

/**
 * Conservative chapter suggestions across user-CONFIRMED linked manga.
 * Choosing a variant never copies progress and never changes the original.
 * This planner doesn't perform network access, DB updates or source migration.
 */
object ReaderContinuityPlanner {
    enum class Direction { SAME_CHAPTER, NEXT_CHAPTER }

    data class Option(
        val sourceId: Long,
        val mangaUrl: String,
        val mangaTitle: String,
        val sourceName: String,
        val chapter: SChapter,
        val isPreferred: Boolean = false,
        val isOriginal: Boolean = false,
        val needsAttention: Boolean = false,
    )

    data class Suggestion(
        val option: Option,
        val needsTitleReview: Boolean,
    )

    private val special = Regex(
        """(?i)\b(special|bonus|omake|extra|prologue|epilogue|one[\s-]?shot|side[\s-]?story|interlude|part\s*\d+)\b""",
    )
    private val prefix = Regex("""(?i)^\s*(?:vol(?:ume)?\s*\d+\s*)?(?:chapter|ch\.?|episode|ep\.?)\s*\d+(?:\.\d+)?\s*(?:[:.\-–—]\s*)?""")
    private val volume = Regex("""(?i)\bvol(?:ume)?\.?\s*(\d+)\b""")

    fun number(chapter: SChapter): Float? =
        chapter.chapter_number.takeIf {
            it.isFinite() && it >= 1f && !special.containsMatchIn(chapter.name)
        }

    private fun title(chapter: SChapter): String =
        prefix.replace(chapter.name, "")
            .lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()

    /** An ambiguous title is offered with a warning, never silently equated. */
    fun suggest(
        current: SChapter,
        currentSourceId: Long,
        direction: Direction,
        options: List<Option>,
    ): List<Suggestion> {
        val number = number(current) ?: return emptyList()
        val currentVol = volume.find(current.name)?.groupValues?.get(1)
        return options.asSequence()
            .filter { it.sourceId != currentSourceId && it.mangaUrl.isNotBlank() && it.chapter.url.isNotBlank() }
            .filter { option ->
                val candidate = number(option.chapter) ?: return@filter false
                val sameVolume = volume.find(option.chapter.name)?.groupValues?.get(1) == currentVol
                sameVolume && when (direction) {
                    Direction.SAME_CHAPTER -> candidate == number
                    Direction.NEXT_CHAPTER -> candidate > number && candidate - number <= 1f
                }
            }
            .distinctBy { Triple(it.sourceId, it.mangaUrl, it.chapter.url) }
            .map { option ->
                val a = title(current)
                val b = title(option.chapter)
                val uncertain = direction == Direction.SAME_CHAPTER &&
                    a.isNotBlank() && b.isNotBlank() && a != b
                Suggestion(option, uncertain)
            }
            .sortedWith(
                compareBy<Suggestion> { it.needsTitleReview }
                    .thenBy { it.option.needsAttention }
                    .thenByDescending { it.option.isPreferred }
                    .thenByDescending { it.option.isOriginal }
                    .thenBy { if (direction == Direction.NEXT_CHAPTER) it.option.chapter.chapter_number else 0f }
                    .thenBy { it.option.sourceName },
            )
            .take(24)
            .toList()
    }
}
