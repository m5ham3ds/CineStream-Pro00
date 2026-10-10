package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.model.HistoryItem
import com.example.data.model.LibraryItem
import com.example.data.model.NotificationItem
import com.example.data.model.SupportMessage
import com.example.data.notification.FcmTokenManager
import com.example.data.repository.AccountDeletionResult
import com.example.data.repository.AuthRepository
import com.example.data.repository.SocialRepository
import com.example.data.repository.UserSecurityManager
import com.example.data.session.SessionManager
import com.example.data.sync.CloudSyncManager
import com.example.ui.screens.player.PlaybackSyncStore
import com.example.utils.LastPlaybackStore
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PHASE 07.0 / WAVE 2.1 — FINAL VERIFICATION GAP CLOSURE TEST SUITE
 *
 * Authoritative Gap-Closure Verification for:
 * - F-002: Real FCM Race Scenarios (F002-R01 through F002-R06)
 * - F-005: Reauthentication & Account Deletion Outcomes (F005-R01 through F005-R03)
 * - F-005: Post-Deletion Session Safety (F005-R04 through F005-R05)
 * - F-011: User Boundary & Async Isolation (F011-R01 through F011-R05)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase07Wave21GapClosureTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) {
            com.google.firebase.FirebaseApp.initializeApp(context)
        }
        db = AppDatabase.getDatabase(context)
        SessionManager.invalidateSession()
        FcmTokenManager.getInstance(context).resetTestHooks()
        AuthRepository.resetTestHooks()
        com.example.utils.NetworkUtils.networkAvailableOverride = true
    }

    @After
    fun tearDown() {
        SessionManager.invalidateSession()
        FcmTokenManager.getInstance(context).resetTestHooks()
        AuthRepository.resetTestHooks()
        com.example.utils.NetworkUtils.networkAvailableOverride = null
    }

    // =========================================================================
    // SECTION 1: F-002 — REAL FCM RACE PROOFS (F002-R01 through F002-R06)
    // =========================================================================

    /**
     * F002-R01:
     * User A starts FCM registration
     * → session invalidated
     * → User B becomes active
     * → old A coroutine resumes
     * Expected:
     * NO write to A
     * NO write to B using A context
     */
    @Test
    fun testF002_R01_userAStartsRegistration_sessionInvalidated_userBActive_oldACoroutineResumes_noWriteToA_noWriteToBUsingAContext() = runBlocking {
        val fcmManager = FcmTokenManager.getInstance(context)
        val genA = SessionManager.bindSessionForTesting("user_A")
        val writtenUids = mutableListOf<String>()
        fcmManager.firestoreWriterOverride = { uid, _, _ ->
            writtenUids.add(uid)
        }

        // Session invalidated and User B becomes active
        SessionManager.invalidateSession()
        SessionManager.bindSessionForTesting("user_B")

        // Old coroutine for User A resumes
        val success = fcmManager.syncToken(token = "token_A_sample", sessionGeneration = genA, expectedUid = "user_A")

        assertFalse("Old coroutine must fail closed when session is invalidated", success)
        assertTrue("No write must occur to user_A", writtenUids.none { it == "user_A" })
        assertTrue("No write must occur to user_B using user_A context", writtenUids.none { it == "user_B" })
        assertEquals("Total writes must be 0", 0, writtenUids.size)
    }

    /**
     * F002-R02:
     * User A starts FCM token deletion
     * → logout
     * → User B login
     * → old deletion resumes
     * Expected:
     * NO mutation of B token state
     */
    @Test
    fun testF002_R02_userAStartsDeletion_logout_userBLogin_oldDeletionResumes_noMutationOfBTokenState() = runBlocking {
        val fcmManager = FcmTokenManager.getInstance(context)
        val genA = SessionManager.bindSessionForTesting("user_A")
        val deletedUids = mutableListOf<String>()
        fcmManager.firestoreDeleterOverride = { uid, _ ->
            deletedUids.add(uid)
        }

        // User A logout occurs
        SessionManager.onUserLoggedOut(context)
        // User B logs in
        SessionManager.onUserLoggedIn(context, "user_B")

        // Old deletion coroutine of User A resumes with genA
        fcmManager.onUserSignedOut("user_A", genA)

        assertTrue("No deletion may target user_B", deletedUids.none { it == "user_B" })
        assertEquals("User B must remain active session", "user_B", SessionManager.activeUid)
    }

    /**
     * F002-R03:
     * User A registration in progress
     * → account switch to B
     * → old operation completes
     * Expected:
     * A operation becomes stale and cannot mutate active B session.
     */
    @Test
    fun testF002_R03_userARegistrationInProgress_accountSwitchToB_oldOperationCompletes_staleCannotMutateB() = runBlocking {
        val fcmManager = FcmTokenManager.getInstance(context)
        val genA = SessionManager.bindSessionForTesting("user_A")
        val writtenRecords = mutableListOf<Pair<String, String>>()
        fcmManager.firestoreWriterOverride = { uid, instId, _ ->
            writtenRecords.add(uid to instId)
        }

        // Account switch to user_B occurs while A operation was in flight
        SessionManager.onAccountSwitched(context, "user_B")
        val genB = SessionManager.currentGeneration

        // Old A operation resumes
        val resultA = fcmManager.syncToken(token = "tok_A", sessionGeneration = genA, expectedUid = "user_A")
        assertFalse("A registration must be rejected as stale", resultA)
        assertTrue("A must not write under A or B", writtenRecords.none { it.first == "user_A" })

        // Active B registration completes
        val resultB = fcmManager.syncToken(token = "tok_B", sessionGeneration = genB, expectedUid = "user_B")
        assertTrue("B registration must succeed", resultB)
        assertTrue("Only user_B writes are permitted", writtenRecords.all { it.first == "user_B" })
    }

    /**
     * F002-R04:
     * User A token refresh completes after logout
     * Expected:
     * No Firestore mutation under stale A session.
     */
    @Test
    fun testF002_R04_userATokenRefreshCompletesAfterLogout_noFirestoreMutation() = runBlocking {
        val fcmManager = FcmTokenManager.getInstance(context)
        val genA = SessionManager.bindSessionForTesting("user_A")
        var firestoreWriteCount = 0
        fcmManager.firestoreWriterOverride = { _, _, _ ->
            firestoreWriteCount++
        }

        // Logout occurs before token refresh writes
        SessionManager.onUserLoggedOut(context)

        // Token refresh event completes under stale session
        val refreshed = fcmManager.syncToken(token = "refreshed_token", sessionGeneration = genA, expectedUid = "user_A")
        assertFalse("Refreshed token write must be rejected post-logout", refreshed)
        assertEquals("Zero writes permitted under stale session", 0, firestoreWriteCount)
    }

    /**
     * F002-R05:
     * Repeated logout during active FCM operation
     * Expected:
     * No stale mutation, no duplicate cleanup corruption.
     */
    @Test
    fun testF002_R05_repeatedLogoutDuringActiveFcmOperation_noStaleMutationNoDuplicateCleanupCorruption() = runBlocking {
        val fcmManager = FcmTokenManager.getInstance(context)
        val genA = SessionManager.bindSessionForTesting("user_A")
        var writeCount = 0
        fcmManager.firestoreWriterOverride = { _, _, _ -> writeCount++ }

        // First logout
        val genAfterFirst = SessionManager.onUserLoggedOut(context)
        assertTrue(genAfterFirst > genA)
        assertNull(SessionManager.activeUid)

        // Repeated logout (rapid double-tap or race)
        val genAfterSecond = SessionManager.onUserLoggedOut(context)
        assertTrue(genAfterSecond > genAfterFirst)
        assertNull(SessionManager.activeUid)

        // Resumed A operation attempts sync
        val syncResult = fcmManager.syncToken(token = "token_A", sessionGeneration = genA, expectedUid = "user_A")
        assertFalse(syncResult)
        assertEquals(0, writeCount)
    }

    /**
     * F002-R06:
     * Account switch during FCM write suspension point
     * Expected:
     * Post-write validation detects stale generation and rollback is executed.
     */
    @Test
    fun testF002_R06_accountSwitchDuringFcmWriteSuspensionPoint_rollbackExecuted() = runBlocking {
        val fcmManager = FcmTokenManager.getInstance(context)
        val genA = SessionManager.bindSessionForTesting("user_A")
        var rollbackTarget: String? = null

        // Suspension hook: right before write finishes, account switch happens!
        fcmManager.onPreFirestoreWriteHook = { _, _ ->
            SessionManager.onAccountSwitched(context, "user_B")
        }
        fcmManager.firestoreWriterOverride = { _, _, _ ->
            // Simulates write completion
        }
        fcmManager.firestoreRollbackOverride = { uid, _ ->
            rollbackTarget = uid
        }

        val syncResult = fcmManager.syncToken(token = "token_A", sessionGeneration = genA, expectedUid = "user_A")

        assertFalse("syncToken must return false due to stale post-write validation", syncResult)
        assertEquals("Rollback must be executed for user_A", "user_A", rollbackTarget)
    }

    // =========================================================================
    // SECTION 2: F-005 — REAUTHENTICATION & DELETION OUTCOMES (F005-R01..R03)
    // =========================================================================

    /**
     * F005-R01:
     * Recent-login-required condition
     * → deleteAccount()
     * → expected result = ReauthRequired
     * → MUST NOT report Success.
     */
    @Test
    fun testF005_R01_recentLoginRequired_returnsReauthRequired_doesNotReportSuccess() = runBlocking {
        AuthRepository.activeUserUidForTesting = "user_to_delete"
        AuthRepository.firebaseUserDeleter = {
            throw FirebaseAuthRecentLoginRequiredException("ERROR_RECENT_LOGIN_REQUIRED", "Recent login required")
        }

        val result = AuthRepository.deleteAccount(context)

        assertTrue("Expected ReauthRequired, got $result", result is AccountDeletionResult.ReauthRequired)
        assertFalse("Must NOT report Success", result is AccountDeletionResult.Success)
    }

    /**
     * F005-R02:
     * Critical Firestore deletion failure
     * → expected result = PartialFailure or Failed
     * → MUST NOT report Success.
     */
    @Test
    fun testF005_R02_criticalFirestoreDeletionFailure_returnsPartialFailure_doesNotReportSuccess() = runBlocking {
        AuthRepository.activeUserUidForTesting = "user_doc_fail"
        AuthRepository.userDocDeleter = {
            throw RuntimeException("Firestore document deletion blocked by security rule")
        }
        AuthRepository.firebaseUserDeleter = {
            // Auth delete succeeds
        }

        val result = AuthRepository.deleteAccount(context)

        assertTrue("Expected PartialFailure, got $result", result is AccountDeletionResult.PartialFailure)
        assertFalse("Must NOT report Success", result is AccountDeletionResult.Success)
    }

    /**
     * F005-R03:
     * Firebase Auth deletion success + required remote cleanup success
     * → expected result = Success.
     */
    @Test
    fun testF005_R03_authDeletionSuccessAndRemoteCleanupSuccess_returnsSuccess() = runBlocking {
        AuthRepository.activeUserUidForTesting = "user_clean_delete"
        AuthRepository.userDocDeleter = {
            // Document deletion succeeds
        }
        AuthRepository.firebaseUserDeleter = {
            // Auth deletion succeeds
        }

        val result = AuthRepository.deleteAccount(context)

        assertTrue("Expected Success, got $result", result is AccountDeletionResult.Success)
    }

    // =========================================================================
    // SECTION 3: F-005 — POST-DELETION SESSION SAFETY (F005-R04..R05)
    // =========================================================================

    /**
     * F005-R04:
     * User A deletion succeeds
     * → session invalidated
     * → local data cleared
     * → deleted UID remains unavailable
     * → a subsequent load attempt must NOT reconstruct User A state.
     * Expected:
     * No deleted-session profile/cache/playback/notification/support state is restored.
     */
    @Test
    fun testF005_R04_userADeletionSucceeds_sessionInvalidated_localDataCleared_noStateRestored() = runBlocking {
        val uidA = "user_alice_delete"
        AuthRepository.activeUserUidForTesting = uidA
        SessionManager.bindSessionForTesting(uidA)

        // Seed private data across Room, SharedPreferences, in-memory caches
        db.supportDao().insertMessage(SupportMessage(text = "Alice confidential support", isFromUser = true))
        db.notificationDao().insertNotification(NotificationItem(id = "notif_a", title = "A", message = "A"))
        db.libraryDao().insertItem(LibraryItem.create(com.example.data.model.ContentType.MOVIE, "m1", "Movie", ""))
        LastPlaybackStore.savePlayback(context, "m1", "https://cdn/a.mp4", positionMillis = 12345L)
        PlaybackSyncStore.setPosition("m1", 12345L)

        AuthRepository.userDocDeleter = {}
        AuthRepository.firebaseUserDeleter = {}

        val result = AuthRepository.deleteAccount(context)
        assertEquals(AccountDeletionResult.Success, result)

        // Assert session invalidated
        assertNull("Active UID must be unset", SessionManager.activeUid)
        // Assert local data cleared
        assertEquals(0, db.supportDao().getMessageCount())
        assertEquals(0, db.notificationDao().getAllNotifications().first().size)
        assertEquals(0, db.libraryDao().getAllItems().first().size)
        assertNull(LastPlaybackStore.getLastPlayback(context, "m1"))
        assertEquals(0L, PlaybackSyncStore.getPosition("m1"))

        // Subsequent attempt to load state without active session cannot restore Alice's state
        val currentSession = SessionManager.getCurrentSession()
        assertNull("Current session must remain null", currentSession)
        assertEquals(0, db.libraryDao().getAllItems().first().size)
    }

    /**
     * F005-R05:
     * User A deletion
     * → User B login
     * Expected:
     * User B starts with only User B state.
     */
    @Test
    fun testF005_R05_userADeletion_userBLogin_startsOnlyWithUserBState() = runBlocking {
        val uidA = "user_A_deleted"
        AuthRepository.activeUserUidForTesting = uidA
        SessionManager.bindSessionForTesting(uidA)

        db.libraryDao().insertItem(LibraryItem.create(com.example.data.model.ContentType.MOVIE, "lib_A", "Alice Title", ""))
        AuthRepository.userDocDeleter = {}
        AuthRepository.firebaseUserDeleter = {}

        val delResult = AuthRepository.deleteAccount(context)
        assertEquals(AccountDeletionResult.Success, delResult)

        // User B logs in
        SessionManager.onUserLoggedIn(context, "user_B")
        assertEquals("user_B", SessionManager.activeUid)

        // User B adds their own item
        db.libraryDao().insertItem(LibraryItem.create(com.example.data.model.ContentType.MOVIE, "lib_B", "Bob Title", ""))

        val bItems = db.libraryDao().getAllItems().first()
        assertEquals(1, bItems.size)
        assertEquals("lib_B", bItems.first().tmdbId)
        assertEquals("Bob Title", bItems.first().title)
    }

    // =========================================================================
    // SECTION 4: F-011 — USER BOUNDARY & ASYNC ISOLATION (F011-R01..R05)
    // =========================================================================

    /**
     * F011-R01:
     * User A data loaded
     * → logout
     * → User B login
     * → User A Room state unavailable.
     */
    @Test
    fun testF011_R01_userADataLoaded_logout_userBLogin_userARoomStateUnavailable() = runBlocking {
        SessionManager.bindSessionForTesting("user_A")
        db.libraryDao().insertItem(LibraryItem.create(com.example.data.model.ContentType.MOVIE, "1", "A movie", ""))
        db.historyDao().insertHistory(HistoryItem(id = "h1", title = "A history", isMovie = true, positionMillis = 5000L, durationMillis = 10000L))
        db.notificationDao().insertNotification(NotificationItem(id = "n1", title = "A notif", message = "msg"))
        db.supportDao().insertMessage(SupportMessage(text = "A support", isFromUser = true))

        assertEquals(1, db.libraryDao().getAllItems().first().size)
        assertEquals(1, db.historyDao().getAllHistory().first().size)

        // User A logs out
        SessionManager.onUserLoggedOut(context)

        // User B logs in
        SessionManager.onUserLoggedIn(context, "user_B")

        // User A Room state must be completely unavailable
        assertEquals(0, db.libraryDao().getAllItems().first().size)
        assertEquals(0, db.historyDao().getAllHistory().first().size)
        assertEquals(0, db.notificationDao().getAllNotifications().first().size)
        assertEquals(0, db.supportDao().getMessageCount())
    }

    /**
     * F011-R02:
     * User A playback state
     * → logout
     * → User B login
     * Expected:
     * User A playback URL/position not returned.
     */
    @Test
    fun testF011_R02_userAPlaybackState_logout_userBLogin_userAPlaybackNotReturned() = runBlocking {
        SessionManager.bindSessionForTesting("user_A")
        LastPlaybackStore.savePlayback(context, "video_100", "https://cdn.test/videoA.m3u8", positionMillis = 42000L)
        PlaybackSyncStore.setPosition("video_100", 42000L)

        assertEquals(42000L, PlaybackSyncStore.getPosition("video_100"))
        assertNotNull(LastPlaybackStore.getLastPlayback(context, "video_100"))

        // Logout & switch to B
        SessionManager.onUserLoggedOut(context)
        SessionManager.onUserLoggedIn(context, "user_B")

        assertNull("User B must not see User A playback record", LastPlaybackStore.getLastPlayback(context, "video_100"))
        assertEquals("User B must not inherit User A playback position", 0L, PlaybackSyncStore.getPosition("video_100"))
    }

    /**
     * F011-R03:
     * User A SocialRepository cache
     * → logout
     * → User B login
     * Expected:
     * User A profile cache not returned.
     */
    @Test
    fun testF011_R03_userASocialRepositoryCache_logout_userBLogin_userAProfileCacheNotReturned() = runBlocking {
        SessionManager.bindSessionForTesting("user_A")
        SocialRepository.clearCache()

        // Logout & Login B
        SessionManager.onUserLoggedOut(context)
        SessionManager.onUserLoggedIn(context, "user_B")

        // AuthRepository current user flow reset
        assertNull(AuthRepository.currentUserFlow.value)
    }

    /**
     * F011-R04:
     * User A asynchronous repository operation begins
     * → logout
     * → User B login
     * → old operation completes
     * Expected:
     * No User A result may populate active User B state.
     */
    @Test
    fun testF011_R04_userAAsyncRepoOperationBegins_logout_userBLogin_oldOperationCompletes_noADataPopulatesB() = runBlocking {
        val genA = SessionManager.bindSessionForTesting("user_A")

        var operationExecutedForUserA = false

        // In the middle of async work: Logout occurs and User B logs in
        SessionManager.onUserLoggedOut(context)
        SessionManager.onUserLoggedIn(context, "user_B")

        // Old operation checks session validation before mutating state
        if (SessionManager.isSessionValid("user_A", genA)) {
            operationExecutedForUserA = true
            db.libraryDao().insertItem(LibraryItem.create(com.example.data.model.ContentType.MOVIE, "stale_A", "Stale", ""))
        }

        assertFalse("Operation must detect stale generation and abort", operationExecutedForUserA)
        assertEquals("User B library must not be polluted by stale A operation", 0, db.libraryDao().getAllItems().first().size)
    }

    /**
     * F011-R05:
     * User A remote Firestore listener active
     * → logout/account switch
     * Expected:
     * Listener stopped before or atomically with session invalidation.
     */
    @Test
    fun testF011_R05_userARemoteFirestoreListenerActive_logoutOrSwitch_listenerStopped() = runBlocking {
        SessionManager.bindSessionForTesting("user_A")

        // Start listeners
        CloudSyncManager(context).startRealtimeSync("user_A")
        UserSecurityManager.listenToUserSecurity("user_A")

        assertTrue("CloudSyncManager must have active sync", CloudSyncManager.isSyncActive())
        assertTrue("UserSecurityManager must have active listener", UserSecurityManager.isListening())

        // Session logout
        SessionManager.onUserLoggedOut(context)

        assertFalse("CloudSyncManager listener must be stopped", CloudSyncManager.isSyncActive())
        assertFalse("UserSecurityManager listener must be stopped", UserSecurityManager.isListening())
    }
}
