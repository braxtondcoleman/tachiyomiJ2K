package eu.kanade.tachiyomi.data.library

import org.junit.Assert.assertEquals
import org.junit.Test

class MalImportPlannerTest {
    @Test fun exactUniqueTitleMatches() {
        val plan = MalImportPlanner.plan(
            listOf(MalImportPlanner.Local(1, "One Punch-Man"), MalImportPlanner.Local(2, "Berserk")),
            listOf(MalImportPlanner.Remote(5, "One Punch Man", "reading"),
                MalImportPlanner.Remote(6, "Berserk", "completed")),
        )
        assertEquals(2, plan.matches.size)
        assertEquals(ReadingFeedback.Status.COMPLETED, plan.matches.last().status)
    }

    @Test fun duplicateLocalSourcesDoNotReceiveGuessedStatus() {
        val plan = MalImportPlanner.plan(
            listOf(MalImportPlanner.Local(1, "Same"), MalImportPlanner.Local(2, "Same")),
            listOf(MalImportPlanner.Remote(5, "Same", "dropped")),
        )
        assertEquals(0, plan.matches.size)
        assertEquals(1, plan.ambiguousRemote)
    }

    @Test fun duplicateRemoteTitlesDoNotAutoMatch() {
        val plan = MalImportPlanner.plan(
            listOf(MalImportPlanner.Local(1, "Same")),
            listOf(MalImportPlanner.Remote(5, "Same", "reading"), MalImportPlanner.Remote(6, "Same", "completed")),
        )
        assertEquals(0, plan.matches.size)
    }
}
