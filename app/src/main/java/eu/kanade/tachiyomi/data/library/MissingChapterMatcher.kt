package eu.kanade.tachiyomi.data.library

import kotlin.math.abs

/**
 * Check the real chapter identity, never infer availability from a list size.
 * Some extensions return chapter_number=-1 even when the name says "Chapter 42".
 * Only extract a missing number from a clear chapter/episode label, and never
 * turn a fractional chapter (42.5) into whole chapter 42.
 */
object MissingChapterMatcher {
    private val titleNumber = Regex(
        """(?i)(?:^|[\s\[(\-])(?:chapter|ch\.?|episode|ep\.?|cap[ií]tulo|chapitre|第)\s*[#№.:\-]*\s*(\d{1,5})(?:\.(\d+))?(?=$|[^\d.])""",
    )
    private val specialName = Regex(
        """(?i)\b(?:extra|special|bonus|omake|prologue|epilogue|side[ -]?story|interlude)\b""",
    )

    /** A precise numeric value always takes precedence over its potentially noisy title. */
    fun chapterNumber(rawNumber: Float, name: String): Int? {
        if (rawNumber.isFinite() && rawNumber >= 1f) {
            val integer = rawNumber.toInt()
            return integer.takeIf { integer >= 1 && abs(rawNumber - integer.toFloat()) < 0.001f }
        }
        if (specialName.containsMatchIn(name)) return null
        val match = titleNumber.find(name) ?: return null
        val number = match.groupValues[1].toIntOrNull() ?: return null
        if (number < 1) return null
        val decimals = match.groupValues[2]
        if (decimals.isNotEmpty() && decimals.any { it != '0' }) return null
        return number
    }

    fun exactNumbers(chapterNumbers: Iterable<Float>, requested: Set<Int>): List<Int> =
        chapterNumbers.mapNotNull { value -> chapterNumber(value, "")?.takeIf { it in requested } }
            .distinct().sorted()

    fun exactNumbersFromChapters(chapters: Iterable<Pair<Float, String>>, requested: Set<Int>): List<Int> =
        chapters.mapNotNull { (number, name) -> chapterNumber(number, name)?.takeIf { it in requested } }
            .distinct().sorted()
}
