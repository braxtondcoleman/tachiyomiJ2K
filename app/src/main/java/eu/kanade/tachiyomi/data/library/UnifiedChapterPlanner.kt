package eu.kanade.tachiyomi.data.library

import java.util.Locale

/**
 * Conservative, read-only reconciliation of chapters across CONFIRMED linked manga.
 *
 * This never writes database rows or transfers progress. Variants retain their
 * portable (source ID, manga URL, chapter URL) identities. An uncertain match
 * remains two separate rows; users can still open either source explicitly.
 */
object UnifiedChapterPlanner {
    data class Variant(
        val sourceId: Long,
        val mangaUrl: String,
        val chapterUrl: String,
        val chapterName: String,
        val chapterNumber: Float,
        val scanlator: String? = null,
        val isPrimary: Boolean = false,
        val isPreferred: Boolean = false,
        val isAvailable: Boolean = true,
        val needsAttention: Boolean = false,
        val read: Boolean = false,
        val bookmarked: Boolean = false,
        val lastPageRead: Int = 0,
    ) {
        val portableId: Triple<Long, String, String>
            get() = Triple(sourceId, mangaUrl, chapterUrl)
    }

    data class Row(
        val variants: List<Variant>,
        val number: Float?,
        val ambiguous: Boolean,
    ) {
        val recommended: Variant?
            get() = variants
                .filter { it.isAvailable }
                .sortedWith(
                    compareBy<Variant> { it.needsAttention }
                        .thenByDescending { it.isPreferred }
                        .thenByDescending { it.isPrimary }
                        .thenBy { it.sourceId },
                ).firstOrNull()
        val isMerged: Boolean get() = variants.size > 1
        val isRead: Boolean get() = variants.any { it.read }
        val isBookmarked: Boolean get() = variants.any { it.bookmarked }
    }

    data class Result(
        val rows: List<Row>,
        val matchedRows: Int,
        val uncertainRows: Int,
        val possibleMissingNumbers: List<Int>,
    )

    private val specialWords = Regex(
        """(?i)\b(extra|special|omake|one[\s-]?shot|oneshot|prologue|epilogue|side[\s-]?story|interlude|bonus|part\s*\d+|chapter\s*0|ch\.?\s*0)\b""",
    )
    private val volume = Regex("""(?i)\bvol(?:ume)?\.?\s*(\d+)\b""")
    private val leadingChapter = Regex(
        """(?i)^\s*(?:vol(?:ume)?\.?\s*\d+[\s:.-]*)?(?:chapter|ch\.?|episode|ep\.?)\s*\d+(?:\.\d+)?(?:\s*[:.\-–—]\s*|\s*$)""",
    )
    private val spaces = Regex("""\s+""")
    private val punctuation = Regex("""[^\p{L}\p{N}]+""")

    /** A positive finite chapter number is required to infer equivalence. */
    private fun number(variant: Variant): Float? =
        variant.chapterNumber.takeIf {
            it.isFinite() && it >= 1f && !specialWords.containsMatchIn(variant.chapterName)
        }

    private fun title(variant: Variant): String =
        punctuation.replace(
            leadingChapter.replace(variant.chapterName, "").lowercase(Locale.ROOT),
            " ",
        ).trim().replace(spaces, " ")

    private fun canMerge(first: Variant, second: Variant): Boolean {
        if (first.sourceId == second.sourceId) return false
        val firstNumber = number(first) ?: return false
        if (number(second) != firstNumber) return false
        // Volume labels are never dropped. A missing volume is not proof of equivalence.
        if (volume.find(first.chapterName)?.groupValues?.get(1) !=
            volume.find(second.chapterName)?.groupValues?.get(1)
        ) return false
        val a = title(first)
        val b = title(second)
        // If both sources supply distinct descriptive chapter titles, don't guess.
        return a.isBlank() || b.isBlank() || a == b
    }

    fun reconcile(input: List<Variant>, maxRows: Int = 150): Result {
        require(maxRows in 1..1000)
        val unique = input
            .filter { it.chapterUrl.isNotBlank() && it.mangaUrl.isNotBlank() }
            .distinctBy { it.portableId }
        val groups = mutableListOf<MutableList<Variant>>()
        // Index by chapter number: avoid comparing every chapter with every
        // other chapter when several installed sources expose thousands of rows.
        val byNumber = mutableMapOf<Float, MutableList<MutableList<Variant>>>()
        unique.forEach { variant ->
            val chapterNumber = number(variant)
            if (chapterNumber == null) {
                groups.add(mutableListOf(variant))
            } else {
                val sameNumber = byNumber.getOrPut(chapterNumber) { mutableListOf() }
                val candidates = sameNumber.filter { group ->
                    group.none { it.sourceId == variant.sourceId } &&
                        group.all { canMerge(it, variant) }
                }
                if (candidates.size == 1) {
                    candidates.single().add(variant)
                } else {
                    val newGroup = mutableListOf(variant)
                    groups.add(newGroup)
                    sameNumber.add(newGroup)
                }
            }
        }
        val ambiguousNumbers = byNumber.filterValues { it.size > 1 }.keys

        val grouped = groups.map { group ->
            val n = number(group.first())
            Row(group.toList(), n, n != null && n in ambiguousNumbers)
        }.sortedWith(
            compareByDescending<Row> { it.number != null }
                .thenByDescending { it.number ?: Float.NEGATIVE_INFINITY }
                .thenBy { it.variants.first().chapterName },
        )

        // A missing chapter NUMBER is only a hint, never a source outage claim.
        // Avoid filling gaps across specials, volumes or ambiguous collisions.
        val safeIntegerNumbers = grouped
            .filter { !it.ambiguous && it.number != null && it.variants.all { variant ->
                volume.find(variant.chapterName) == null
            } }
            .mapNotNull { row ->
                row.number?.takeIf { n ->
                    n in 1f..10000f && n % 1f == 0f
                }?.toInt()
            }
            .distinct().sorted()
        val gaps = if (safeIntegerNumbers.size >= 3) {
            safeIntegerNumbers.zipWithNext().flatMap { (a, b) ->
                if (b - a in 2..10) (a + 1 until b).toList() else emptyList()
            }.take(30)
        } else {
            emptyList()
        }
        return Result(
            rows = grouped.take(maxRows),
            matchedRows = grouped.count { it.isMerged },
            uncertainRows = grouped.count { it.ambiguous },
            possibleMissingNumbers = gaps,
        )
    }
}
