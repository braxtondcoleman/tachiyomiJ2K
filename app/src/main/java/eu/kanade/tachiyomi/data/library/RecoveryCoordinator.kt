package eu.kanade.tachiyomi.data.library

/** Shared decisions for every reader and updater error; each caller owns its UI. */
object RecoveryCoordinator {
    enum class Origin { LIBRARY, MANGA, CHAPTER, PAGE, EXTENSION }
    enum class Step { CHECK_EXTENSION, VERIFY_WEBSITE, WAIT_FOR_RATE_LIMIT, RETRY_TEMPORARY, CHECK_LINKED, FIND_REPLACEMENT, EXPLAIN_FAILURE }
    data class Decision(
        val origin: Origin,
        val failure: SourceRecoveryPlanner.FailureKind,
        val steps: List<Step>,
    )

    fun plan(
        origin: Origin,
        reason: String,
        installed: Boolean = true,
        hasConfirmedLinks: Boolean = false,
    ): Decision {
        val kind = SourceRecoveryPlanner.classify(reason, installed)
        val linked = if (hasConfirmedLinks) listOf(Step.CHECK_LINKED) else emptyList()
        val steps = when (kind) {
            SourceRecoveryPlanner.FailureKind.MISSING_EXTENSION ->
                listOf(Step.CHECK_EXTENSION) + linked
            SourceRecoveryPlanner.FailureKind.LOGIN ->
                listOf(Step.VERIFY_WEBSITE) + linked
            SourceRecoveryPlanner.FailureKind.RATE_LIMIT ->
                listOf(Step.WAIT_FOR_RATE_LIMIT)
            SourceRecoveryPlanner.FailureKind.SERVER, SourceRecoveryPlanner.FailureKind.NETWORK ->
                listOf(Step.RETRY_TEMPORARY) + linked
            SourceRecoveryPlanner.FailureKind.PARSER ->
                linked + listOf(Step.CHECK_EXTENSION, Step.FIND_REPLACEMENT)
            SourceRecoveryPlanner.FailureKind.MISSING_TITLE ->
                linked + listOf(Step.FIND_REPLACEMENT)
            SourceRecoveryPlanner.FailureKind.UNKNOWN ->
                linked + listOf(Step.EXPLAIN_FAILURE)
        }
        return Decision(origin, kind, steps.distinct())
    }

    /** A broken page alone cannot prove a manga or extension is permanently gone. */
    fun mayAutoSwitchSameChapter(
        suggestions: List<ReaderContinuityPlanner.Suggestion>,
    ): Boolean {
        val safe = suggestions.filter { !it.needsTitleReview && !it.option.needsAttention }
        // Automatic routing is allowed only when the match is unambiguous.
        return suggestions.size == 1 && safe.size == 1
    }
}
