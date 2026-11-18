package eu.kanade.tachiyomi.ui.manga

/**
 * One entry point to the manga-level tools added in A–H.
 * Labels describe reader goals rather than internal implementation terms.
 */
object MangaActionPlanner {
    enum class Action(val title: String, val description: String) {
        CHAPTERS("All chapter versions", "Choose a chapter from your linked sources"),
        FIND_SOURCE("Find another source", "Search your installed manga websites"),
        LINKED("My linked sources", "Review or unlink alternate copies"),
        RELATED("Discover similar manga", "Find more titles related to this one"),
        FIX("Fix a reading problem", "See errors, compare sources or retry"),
    }

    fun options(isFavorite: Boolean, isLocal: Boolean): List<Action> = buildList {
        if (isFavorite && !isLocal) add(Action.CHAPTERS)
        if (!isLocal) add(Action.FIND_SOURCE)
        if (isFavorite && !isLocal) add(Action.LINKED)
        add(Action.RELATED)
        if (!isLocal) add(Action.FIX)
    }

    fun row(action: Action): String = "${action.title}\n${action.description}"
}
