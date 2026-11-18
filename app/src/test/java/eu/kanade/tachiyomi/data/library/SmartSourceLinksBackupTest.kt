package eu.kanade.tachiyomi.data.library

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartSourceLinksBackupTest {
    private fun linkedMemo(vararg links: SmartSourceLinks.Link): JsonObject =
        JsonObject(
            mapOf(
                "j2k.custom.linkedSources.v1" to JsonArray(
                    links.map {
                        JsonObject(
                            mapOf(
                                "source" to JsonPrimitive(it.sourceId),
                                "url" to JsonPrimitive(it.url),
                                "title" to JsonPrimitive(it.title),
                            ),
                        )
                    },
                ),
                "some.extension.memo" to JsonPrimitive("must survive"),
            ),
        )

    @Test
    fun backupRoundTripPreservesLinksProgressAndBookmarks() {
        val link = SmartSourceLinks.Link(404, "/series/other", "Translated Other")
        val manga = BackupManga(
            source = 202,
            url = "/series/main",
            title = "The Example",
            memo = linkedMemo(link).toString(),
            chapters = listOf(
                BackupChapter(
                    url = "/chapter/5",
                    name = "Chapter 5",
                    read = true,
                    bookmark = true,
                    lastPageRead = 16,
                    chapterNumber = 5f,
                ),
            ),
            history = listOf(BackupHistory(url = "/chapter/5", lastRead = 12345)),
        )
        val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), Backup(backupManga = listOf(manga)))
        val restored = ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes)
        val entry = restored.backupManga.single()
        assertEquals(202, entry.source)
        assertEquals("/series/main", entry.url)
        assertEquals(listOf(link), SmartSourceLinks.fromMemo(entry.getMangaImpl().memo))
        assertEquals("must survive", (entry.getMangaImpl().memo["some.extension.memo"] as JsonPrimitive).content)
        assertTrue(entry.chapters.single().read)
        assertTrue(entry.chapters.single().bookmark)
        assertEquals(16, entry.chapters.single().lastPageRead)
        assertEquals(12345L, entry.history.single().lastRead)
    }

    @Test
    fun restoringExistingMangaRetainsBothSetsOfLinksAndUnrelatedMetadata() {
        val existing = SmartSourceLinks.Link(202, "/old", "Old source")
        val incoming = SmartSourceLinks.Link(303, "/new", "New source")
        val currentMemo = linkedMemo(existing)
        val backupMemo = JsonObject(mapOf(
            "j2k.custom.linkedSources.v1" to linkedMemo(existing, incoming).getValue("j2k.custom.linkedSources.v1"),
            "some.extension.memo" to JsonPrimitive("older backup value"),
        ))
        val restored = SmartSourceLinks.mergeOnRestore(currentMemo, backupMemo)
        assertEquals(listOf(existing, incoming), SmartSourceLinks.fromMemo(restored))
        assertEquals(JsonPrimitive("must survive"), restored["some.extension.memo"])
        assertEquals(restored, SmartSourceLinks.mergeOnRestore(restored, backupMemo))
    }

    @Test
    fun sourceMetadataRefreshMustNotEraseConfirmedLinkedSources() {
        val link = SmartSourceLinks.Link(66, "/alt", "Alternate")
        val stored = MangaImpl().apply {
            url = "/primary"
            source = 55
            title = "Same Manga"
            favorite = false
            memo = linkedMemo(link)
        }
        val refreshed = MangaImpl().apply {
            url = "/primary"
            source = 55
            title = "Same Manga"
            favorite = false
            memo = JsonObject(mapOf("site.new-key" to JsonPrimitive("new metadata")))
        }
        stored.copyFrom(refreshed)
        assertEquals(listOf(link), SmartSourceLinks.get(stored))
        assertEquals(JsonPrimitive("new metadata"), stored.memo["site.new-key"])
    }

    @Test
    fun legacyBackupWithoutLinkDataIsStillAccepted() {
        assertEquals(emptyList<SmartSourceLinks.Link>(), SmartSourceLinks.fromMemo(JsonObject(emptyMap())))
        assertEquals(
            emptyList<String>(),
            SmartSourceLinks.backupLinkIssues("{}", 1, "/original"),
        )
    }

    @Test
    fun signedNegativeSourceIdsArePortableAndNotConsideredCorrupt() {
        val alternative = SmartSourceLinks.Link(-123456789L, "/signed-id", "Negative ID")
        val memo = linkedMemo(alternative)
        assertEquals(listOf(alternative), SmartSourceLinks.fromMemo(memo))
        assertEquals(emptyList<String>(), SmartSourceLinks.backupLinkIssues(memo.toString(), 2, "/main"))
    }

    @Test
    fun restoreGuardRejectsCorruptionBeforeTheDatabaseIsTouched() {
        val corrupt = BackupManga(
            source = 9,
            url = "/one",
            title = "Corrupt",
            memo = """{"j2k.custom.linkedSources.v1":[{"url":"/bad"}]}""",
        )
        var threw = false
        try {
            SmartSourceLinks.requireValidBackupEntries(listOf(corrupt))
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        // A legacy backup with no source links is always eligible for restoration.
        SmartSourceLinks.requireValidBackupEntries(listOf(BackupManga(9, "/one", "Legacy")))
    }

    @Test
    fun invalidOrDuplicatedLinksAreRejectedByBackupValidation() {
        val duplicated = linkedMemo(
            SmartSourceLinks.Link(77, "/same", "A"),
            SmartSourceLinks.Link(77, "/same", "A"),
        ).toString()
        assertTrue(SmartSourceLinks.backupLinkIssues(duplicated, 1, "/other").any { "Duplicate" in it })

        val self = linkedMemo(SmartSourceLinks.Link(1, "/original", "Self")).toString()
        assertTrue(SmartSourceLinks.backupLinkIssues(self, 1, "/original").any { "own manga" in it })

        val corrupt = """{"j2k.custom.linkedSources.v1":[{"url":"/without-source"}]}"""
        assertFalse(SmartSourceLinks.backupLinkIssues(corrupt, 1, "/original").isEmpty())
        assertFalse(SmartSourceLinks.backupLinkIssues("not-json", 1, "/original").isEmpty())
    }

    @Test
    fun preferredSourceSurvivesBackupRoundTripAndRestore() {
        val preference = SmartSourceLinks.Preferred(303, "/alternative")
        val preferredJson = JsonObject(
            mapOf(
                "source" to JsonPrimitive(preference.sourceId),
                "url" to JsonPrimitive(preference.url),
            ),
        )
        val backupMemo = JsonObject(
            linkedMemo(SmartSourceLinks.Link(303, "/alternative", "Alternate")).toMutableMap().apply {
                put("j2k.custom.preferredSource.v1", preferredJson)
            },
        )
        val backup = BackupManga(44, "/primary", "Example", memo = backupMemo.toString())
        val bytes = ProtoBuf.encodeToByteArray(
            Backup.serializer(),
            Backup(backupManga = listOf(backup)),
        )
        val decoded = ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes).backupManga.single()
        assertEquals(preference, SmartSourceLinks.preferredFromMemo(decoded.getMangaImpl().memo))
        val merged = SmartSourceLinks.mergeOnRestore(linkedMemo(), decoded.getMangaImpl().memo)
        assertEquals(preference, SmartSourceLinks.preferredFromMemo(merged))
        assertEquals(JsonPrimitive("must survive"), merged["some.extension.memo"])
    }

    @Test
    fun currentExplicitPreferredSourceWinsOverOlderBackup() {
        fun memo(sourceId: Long, url: String): JsonObject =
            JsonObject(
                linkedMemo().toMutableMap().apply {
                    put(
                        "j2k.custom.preferredSource.v1",
                        JsonObject(
                            mapOf(
                                "source" to JsonPrimitive(sourceId),
                                "url" to JsonPrimitive(url),
                            ),
                        ),
                    )
                },
            )
        val merged = SmartSourceLinks.mergeOnRestore(memo(22, "/current"), memo(33, "/backup"))
        assertEquals(SmartSourceLinks.Preferred(22, "/current"), SmartSourceLinks.preferredFromMemo(merged))
    }

    @Test
    fun invalidPreferredSourceIsRejectedEvenWithoutLinksArray() {
        val bad = """{"j2k.custom.preferredSource.v1":{"source":"garbage","url":"/manga"}}"""
        val issues = SmartSourceLinks.backupLinkIssues(bad, 11, "/primary")
        assertTrue(issues.any { "Invalid preferred" in it })
    }

    @Test
    fun sourceMetadataRefreshPreservesExplicitPreference() {
        val original = MangaImpl().apply {
            source = 11
            url = "/main"
            title = "A"
            memo = JsonObject(
                mapOf(
                    "j2k.custom.preferredSource.v1" to JsonObject(
                        mapOf("source" to JsonPrimitive(11), "url" to JsonPrimitive("/main")),
                    ),
                ),
            )
        }
        val updated = MangaImpl().apply {
            source = 11
            url = "/main"
            title = "A"
            memo = JsonObject(mapOf("upstream" to JsonPrimitive("fresh")))
        }
        original.copyFrom(updated)
        assertEquals(SmartSourceLinks.Preferred(11, "/main"), SmartSourceLinks.preferred(original))
        assertEquals(JsonPrimitive("fresh"), original.memo["upstream"])
    }
}
