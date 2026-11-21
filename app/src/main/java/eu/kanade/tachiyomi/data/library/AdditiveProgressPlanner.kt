package eu.kanade.tachiyomi.data.library

/** Add-only manga chapter progress. No deletion, status reset, or rewind is represented here. */
object AdditiveProgressPlanner {
    data class KnownProgress(
        val mediaId: Long,
        val title: String,
        val remoteRead: Int,
        val localRead: Int,
    )
    data class Change(val mediaId: Long, val title: String, val oldRead: Int, val newRead: Int)

    fun preview(entries: List<KnownProgress>): List<Change> =
        entries.filter { it.mediaId > 0L && it.remoteRead >= 0 && it.localRead >= 0 }
            .groupBy { it.mediaId }
            .mapNotNull { (id, sameTitle) ->
                // A manga linked from multiple sources should never count as
                // multiple chapters read; take only the highest recorded number.
                val remote = sameTitle.maxOf { it.remoteRead }
                val local = sameTitle.maxOf { it.localRead }
                if (local <= remote) return@mapNotNull null
                Change(id, sameTitle.first().title, remote, local)
            }
            .sortedBy { it.title.lowercase() }

    /** The number safe to write after a fresh online recheck, not just the preview. */
    fun safeTarget(localRead: Int, freshlyFetchedRemote: Int): Int {
        require(localRead >= 0 && freshlyFetchedRemote >= 0)
        return maxOf(localRead, freshlyFetchedRemote)
    }
}
