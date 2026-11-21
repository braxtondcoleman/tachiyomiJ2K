package eu.kanade.tachiyomi.data.library

import kotlin.math.abs

/**
 * Chapter identity requires an actual integral chapter number, not a list size,
 * a truncated decimal (12.5 != 12), or a title similarity score.
 * Results are hints until the reader confirms that the manga is the same series.
 */
object MissingChapterMatcher {
    fun exactNumbers(chapterNumbers: Iterable<Float>, requested: Set<Int>): List<Int> =
        chapterNumbers.mapNotNull { number ->
            if (!number.isFinite() || number < 1f) return@mapNotNull null
            val whole = number.toInt()
            whole.takeIf { it in requested && abs(number - whole.toFloat()) < 0.001f }
        }.distinct().sorted()
}
