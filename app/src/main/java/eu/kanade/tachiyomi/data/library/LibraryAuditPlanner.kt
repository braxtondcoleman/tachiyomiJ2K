package eu.kanade.tachiyomi.data.library

/**
 * Pure library health and duplicate REVIEW. Never deletes, merges, favorites,
 * rewrites chapter progress, or changes category assignments.
 */
object LibraryAuditPlanner {
    data class Entry(
        val id: Long,
        val sourceId: Long,
        val url: String,
        val title: String,
        val originalTitle: String,
        val author: String?,
        val read: Int,
        val unread: Int,
        val bookmarks: Int,
        val sourceInstalled: Boolean,
        val sourceNeedsAttention: Boolean,
        val activeUpdateFailure: Boolean,
        val linkedSources: Int,
    ) {
        val count get() = read + unread
        val key get() = sourceId to url
    }

    data class Group(
        val title: String,
        val copies: List<Entry>,
        val suggestedPrimary: Entry,
        val explanation: String,
    )

    data class Report(
        val uniqueEntries: Int,
        val allEntries: List<Entry>,
        val missingExtensions: List<Entry>,
        val failedUpdates: List<Entry>,
        val sourcesNeedingAttention: List<Entry>,
        val emptyChapterLists: List<Entry>,
        val groups: List<Group>,
        val confirmedLinks: Int,
        val linkedEntries: List<Entry>,
    )

    /**
     * Exact normalized original-title matches only. Do not chain fuzzy
     * similarities into a group. Ambiguous short names and conflicting known
     * authors are deliberately split instead of incorrectly grouping series.
     */
    fun duplicateGroups(entries: List<Entry>): List<Group> =
        entries.distinctBy { it.id }
            .filter { DiscoveryPlanner.normalize(it.originalTitle).length >= 5 }
            .groupBy { DiscoveryPlanner.normalize(it.originalTitle) }
            .values.asSequence()
            .filter { it.size > 1 }
            .flatMap { titleMatches ->
                // A conflicting author is stronger evidence of different works
                // than a common generic title. Unknown authors form a separate
                // review subgroup, never bridging two conflicting authors.
                titleMatches.groupBy { entry ->
                    entry.author?.let(DiscoveryPlanner::normalize)?.takeIf { it.isNotBlank() } ?: "UNKNOWN_AUTHOR"
                }.values.asSequence()
            }
            .filter { it.size > 1 && it.map { m -> m.sourceId }.distinct().size > 1 }
            .map { copies ->
                val ranked = copies.sortedWith(
                    compareByDescending<Entry> { it.read }
                        .thenByDescending { it.bookmarks }
                        .thenByDescending { it.sourceInstalled && !it.sourceNeedsAttention }
                        .thenByDescending { it.count }
                        .thenBy { it.title },
                )
                Group(
                    title = ranked.first().title,
                    copies = ranked,
                    suggestedPrimary = ranked.first(),
                    explanation = "Exact normalized title and compatible known-author metadata. " +
                        "The suggested primary is based on stored reading progress, not a verified same-series identity.",
                )
            }.sortedBy { it.title }.toList()

    fun audit(entries: List<Entry>): Report {
        val unique = entries.distinctBy { it.id }.filter { it.url.isNotBlank() }
        return Report(
            uniqueEntries = unique.size,
            allEntries = unique,
            missingExtensions = unique.filterNot { it.sourceInstalled },
            failedUpdates = unique.filter { it.activeUpdateFailure },
            sourcesNeedingAttention = unique.filter { it.sourceNeedsAttention },
            emptyChapterLists = unique.filter { it.count == 0 },
            groups = duplicateGroups(unique),
            confirmedLinks = unique.sumOf { it.linkedSources },
            linkedEntries = unique.filter { it.linkedSources > 0 },
        )
    }

    fun canLink(primary: Entry, alternative: Entry): Boolean =
        primary.id != alternative.id &&
            primary.sourceId != alternative.sourceId &&
            primary.url.isNotBlank() && alternative.url.isNotBlank() &&
            DiscoveryPlanner.normalize(primary.originalTitle).length >= 5 &&
            DiscoveryPlanner.normalize(primary.originalTitle) == DiscoveryPlanner.normalize(alternative.originalTitle) &&
            (
                primary.author.isNullOrBlank() || alternative.author.isNullOrBlank() ||
                    DiscoveryPlanner.normalize(primary.author) == DiscoveryPlanner.normalize(alternative.author)
            )
}
