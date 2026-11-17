package eu.kanade.tachiyomi.data.database.resolvers

import android.content.ContentValues
import com.pushtorefresh.storio.sqlite.StorIOSQLite
import com.pushtorefresh.storio.sqlite.operations.put.PutResolver
import com.pushtorefresh.storio.sqlite.operations.put.PutResult
import com.pushtorefresh.storio.sqlite.queries.UpdateQuery
import eu.kanade.tachiyomi.data.database.inTransactionReturn
import eu.kanade.tachiyomi.data.database.memoAdapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.tables.MangaTable

/**
 * Only updates the memo column, so changing source links cannot overwrite chapter flags,
 * favorite status, or metadata updated by another screen.
 */
class MangaMemoPutResolver : PutResolver<Manga>() {
    override fun performPut(db: StorIOSQLite, manga: Manga) = db.inTransactionReturn {
        val update = UpdateQuery.builder()
            .table(MangaTable.TABLE)
            .where("${MangaTable.COL_ID} = ?")
            .whereArgs(manga.id)
            .build()
        val values = ContentValues(1).apply {
            put(MangaTable.COL_MEMO, memoAdapter.encode(manga.memo))
        }
        PutResult.newUpdateResult(db.lowLevel().update(update, values), update.table())
    }
}
