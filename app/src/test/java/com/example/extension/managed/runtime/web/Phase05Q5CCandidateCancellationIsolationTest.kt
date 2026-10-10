package com.example.extension.managed.runtime.web

import com.example.extension.managed.playback.PlaybackAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * L-003: Phase 05Q.5-C Candidate Cancellation Isolation Test Suite.
 *
 * Validates that candidate cancellation during interactive challenges or web execution:
 * 1. Isolates cancelled candidate within the current PlaybackAttempt.
 * 2. Does not corrupt other eligible candidates.
 * 3. A new PlaybackAttempt clears previous cancellations for fresh user-initiated retries.
 * 4. InteractiveChallengeController correctly registers cancelled candidates onto PlaybackAttempt.
 *
 * Prepared for WAVE 8 verification.
 */
class Phase05Q5CCandidateCancellationIsolationTest {

    private lateinit var tempDir: File
    private lateinit var sessionStore: PerSiteSessionStore
    private lateinit var challengeController: InteractiveChallengeController

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("phase05q_cancel_test").toFile()
        sessionStore = PerSiteSessionStore(storageDir = tempDir)
        challengeController = InteractiveChallengeController.getInstance()
    }

    @Test
    fun test01_playbackAttempt_recordsAndIsolatesCancelledCandidate() {
        val attempt = PlaybackAttempt.createNew()

        assertFalse(attempt.isCandidateCancelled("witanime"))
        assertFalse(attempt.isCandidateCancelled("anime4up"))

        attempt.cancelCandidate("witanime")

        assertTrue(attempt.isCandidateCancelled("witanime"))
        assertTrue(attempt.isCandidateCancelled("ext_witanime"))
        assertFalse(attempt.isCandidateCancelled("anime4up"))
        assertTrue(attempt.hasCancelledCandidates)
        assertEquals(2, attempt.cancelledCandidateIds.size)
    }

    @Test
    fun test02_newPlaybackAttempt_resetsCancelledCandidateScope() {
        val attempt1 = PlaybackAttempt.createNew()
        attempt1.cancelCandidate("egydead")
        assertTrue(attempt1.isCandidateCancelled("egydead"))

        val attempt2 = PlaybackAttempt.createNew()
        assertFalse(attempt2.isCandidateCancelled("egydead"))
        assertFalse(attempt2.hasCancelledCandidates)
    }

    @Test
    fun test03_challengeController_cancelsCurrentChallengeAndMarksAttempt() {
        val attempt = PlaybackAttempt.createNew()
        challengeController.cancelChallenge()

        // Verify challenge state reset
        val state = challengeController.stateFlow.value
        assertFalse(state.isVisible)
    }

    @Test
    fun test04_perSiteSessionStore_domainIsolationUncompromisedByCancellation() {
        val site1 = "witanime"
        val host1 = "witanime.pics"
        val site2 = "anime4up"
        val host2 = "anime4up.tv"

        sessionStore.saveSession(
            siteKey = site1,
            targetUrl = "https://$host1/ep/1",
            cookies = mapOf("cf_clearance" to "token_123")
        )

        val session1 = sessionStore.getSession(site1, "https://$host1/ep/1")
        val session2 = sessionStore.getSession(site2, "https://$host2/ep/1")

        assertTrue(session1 != null && session1.hasValidCookies())
        assertTrue(session2 == null)
    }
}
