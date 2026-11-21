package eu.kanade.tachiyomi.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryCoordinatorTest {
    @Test fun missingChapter404ChecksLinkedSourcesThenSearches() {
        val plan = RecoveryCoordinator.plan(RecoveryCoordinator.Origin.CHAPTER, "HTTP 404",
            hasConfirmedLinks = true)
        assertEquals(RecoveryCoordinator.Step.CHECK_LINKED, plan.steps.first())
        assertTrue(RecoveryCoordinator.Step.FIND_REPLACEMENT in plan.steps)
    }

    @Test fun rateLimitNeverTriggersImmediateRetry() {
        val plan = RecoveryCoordinator.plan(RecoveryCoordinator.Origin.LIBRARY, "HTTP 429",
            hasConfirmedLinks = true)
        assertEquals(listOf(RecoveryCoordinator.Step.WAIT_FOR_RATE_LIMIT), plan.steps)
    }

    @Test fun cloudflareVerificationIsNotATitleDeletion() {
        val plan = RecoveryCoordinator.plan(RecoveryCoordinator.Origin.PAGE, "HTTP 403 Cloudflare")
        assertEquals(SourceRecoveryPlanner.FailureKind.LOGIN, plan.failure)
        assertFalse(RecoveryCoordinator.Step.FIND_REPLACEMENT in plan.steps)
    }

    @Test fun noAutoSwitchWithoutAConfirmedUniqueChapter() {
        assertFalse(RecoveryCoordinator.mayAutoSwitchSameChapter(emptyList()))
    }
}
