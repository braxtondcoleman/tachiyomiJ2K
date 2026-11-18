package eu.kanade.tachiyomi.ui.manga

import eu.kanade.tachiyomi.data.database.models.Manga

/** Details controllers persist/recreate by manga ID, never by the temporary
 * discovery search result. Never navigate with a transient unsaved model.
 */
object RecommendationOpenGuard {
    fun canOpen(manga: Manga?): Boolean {
        if (manga == null) return false
        return manga.id?.let { it > 0L } == true &&
            manga.url.isNotBlank() && manga.source >= 0L
    }
}
