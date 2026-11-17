package eu.kanade.tachiyomi.data.library

/**
 * Extract descriptive alternate series names, where a source provides an
 * "Alternative Titles:" or "Alternative Names:" block. Based on the idea in
 * Tegaki's AlternativeTitles.kt (Apache-2.0).
 *
 * This is advisory search metadata. A matching alias does NOT imply that two
 * manga should automatically be merged.
 */
internal object AlternativeTitles {
    private val heading = Regex(
        """(?im)^[ \t]*Alternative (?:Titles|Names)[ \t]*:[ \t]*$""",
    )
    private const val MAX_ALIASES = 16

    fun fromDescription(description: String?): List<String> {
        if (description.isNullOrBlank()) return emptyList()
        val match = heading.find(description) ?: return emptyList()
        return description.substring(match.range.last + 1)
            .lineSequence()
            .dropWhile { it.isBlank() }
            .takeWhile { it.isNotBlank() }
            .map { it.trim().trimStart('•', '·', '*', '-').trim() }
            .filter { it.length >= 2 && it.length <= 120 }
            .distinctBy { it.lowercase() }
            .take(MAX_ALIASES)
            .toList()
    }
}
