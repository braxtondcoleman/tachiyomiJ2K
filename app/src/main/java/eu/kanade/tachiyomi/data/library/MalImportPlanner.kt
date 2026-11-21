package eu.kanade.tachiyomi.data.library

/** Exact, unique title matches only. Uncertain or duplicate matches need manual review. */
object MalImportPlanner {
    data class Local(val id: Long, val title: String)
    data class Remote(val malId: Long, val title: String, val status: String)
    data class Match(val mangaId: Long, val malId: Long, val title: String, val status: ReadingFeedback.Status)
    data class Plan(val matches: List<Match>, val ambiguousRemote: Int)

    fun plan(local: List<Local>, remote: List<Remote>): Plan {
        val localByTitle = local.groupBy { DiscoveryPlanner.normalize(it.title) }
        val remoteByTitle = remote.groupBy { DiscoveryPlanner.normalize(it.title) }
        val candidates = remote.mapNotNull { item ->
            val key = DiscoveryPlanner.normalize(item.title)
            if (key.isBlank()) return@mapNotNull null
            val localItems = localByTitle[key].orEmpty()
            if (localItems.size != 1 || remoteByTitle[key]?.size != 1) return@mapNotNull null
            val status = when (item.status.lowercase()) {
                "reading" -> ReadingFeedback.Status.READING
                "completed" -> ReadingFeedback.Status.COMPLETED
                "on_hold" -> ReadingFeedback.Status.ON_HOLD
                "dropped" -> ReadingFeedback.Status.DROPPED
                "plan_to_read" -> ReadingFeedback.Status.PLAN_TO_READ
                else -> return@mapNotNull null
            }
            Match(localItems.single().id, item.malId, item.title, status)
        }
        return Plan(candidates.distinctBy { it.mangaId }, remote.size - candidates.size)
    }
}
