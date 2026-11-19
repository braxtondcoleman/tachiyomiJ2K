package eu.kanade.tachiyomi.data.library

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingFeedbackTest {
    @Test fun droppedReasonsAreIndependentFromOtherMemoFields() {
        val old = JsonObject(mapOf("other" to JsonPrimitive("retained")))
        val changed = ReadingFeedback.withEntry(old, ReadingFeedback.Entry(
            ReadingFeedback.Status.DROPPED,
            setOf(ReadingFeedback.DropReason.BORING, ReadingFeedback.DropReason.OP_SLOP),
        ))
        assertEquals("retained", (changed["other"] as JsonPrimitive).content)
        assertEquals(2, ReadingFeedback.fromMemo(changed).reasons.size)
        assertEquals(old, ReadingFeedback.withEntry(changed, ReadingFeedback.Entry()))
    }

    @Test fun statusChangeClearsDropReasons() {
        val dropped = ReadingFeedback.withEntry(JsonObject(emptyMap()), ReadingFeedback.Entry(
            ReadingFeedback.Status.DROPPED, setOf(ReadingFeedback.DropReason.SEXUAL_VIOLENCE),
        ))
        val completed = ReadingFeedback.withEntry(dropped, ReadingFeedback.Entry(ReadingFeedback.Status.COMPLETED))
        assertEquals(ReadingFeedback.Status.COMPLETED, ReadingFeedback.fromMemo(completed).status)
        assertTrue(ReadingFeedback.fromMemo(completed).reasons.isEmpty())
    }

    @Test fun metadataRefreshPreservesStatus() {
        val original = ReadingFeedback.withEntry(JsonObject(emptyMap()), ReadingFeedback.Entry(ReadingFeedback.Status.ON_HOLD))
        val updated = ReadingFeedback.preserveOnRefresh(JsonObject(mapOf("source" to JsonPrimitive("fresh"))), original)
        assertEquals(ReadingFeedback.Status.ON_HOLD, ReadingFeedback.fromMemo(updated).status)
        assertEquals("fresh", (updated["source"] as JsonPrimitive).content)
    }
}
