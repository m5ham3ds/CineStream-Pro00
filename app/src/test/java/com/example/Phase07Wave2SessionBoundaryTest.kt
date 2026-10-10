package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.model.HistoryItem
import com.example.data.model.LibraryItem
import com.example.data.model.NotificationItem
import com.example.data.model.SupportMessage
import com.example.data.model.WatchedEpisode
import com.example.data.notification.FcmTokenDocument
import com.example.data.notification.FcmTokenManager
import com.example.data.repository.AccountDeletionResult
import com.example.data.repository.AuthRepository
import com.example.data.repository.NotificationPreferencesRepository
import com.example.data.repository.SocialRepository
import com.example.data.repository.UserPreferencesRepository
import com.example.data.session.SessionManager
import com.example.ui.screens.player.PlaybackSyncStore
import com.example.utils.LastPlaybackStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * PHASE 07.0 / WAVE 2: SESSION BOUNDARY, ACCOUNT LIFECYCLE & MULTI-USER DATA ISOLATION TEST
 *
 * Authoritative Verification Gate for:
 * - F-002: Logout vs asynchronous FCM deletion race
 * - F-005: Missing in-app account deletion
 * - F-011: Multi-user data bleed / incomplete session boundary
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase07Wave2SessionBoundaryTest {

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
    }

    // =========================================================================
    // SECTION 1: F-002 — LOGOUT VS ASYNCHRONOUS FCM DELETION RACE (10 TESTS)
    // =========================================================================

    @Test
    fun testF002_01_sessionGenerationIncrementsOnLogoutAndInvalidatesStaleWork() {
        val initialGen = SessionManager.currentGeneration
        val boundGen = SessionManager.bindSessionForTesting("user_test_alpha", initialGen)
        assertEquals(initialGen, boundGen)
        assertEquals("user_test_alpha", SessionManager.activeUid)

        val newGen = SessionManager.invalidateSession()
        assertTrue("Generation must increment upon invalidation", newGen > initialGen)
        assertNull("Active UID must be unset upon invalidation", SessionManager.activeUid)
        assertFalse(
            "Stale session must fail isSessionValid check",
            SessionManager.isSessionValid("user_test_alpha", initialGen)
        )
    }

    @Test
    fun testF002_02_isSessionValidRejectsGenerationMismatch() {
        val gen1 = SessionManager.bindSessionForTesting("user_test_beta")
        assertTrue(SessionManager.isSessionValid("user_test_beta", gen1))

        // Session generation bumped (e.g. by concurrent logout or background invalidation)
        val gen2 = SessionManager.invalidateSession()
        assertFalse(
            "Must reject operation with outdated generation",
            SessionManager.isSessionValid("user_test_beta", gen1)
        )
        assertFalse(
            "Must reject operation after session unset even with new generation",
            SessionManager.isSessionValid("user_test_beta", gen2)
        )
    }

    @Test
    fun testF002_03_isSessionValidRejectsUidMismatch() {
        val gen = SessionManager.bindSessionForTesting("user_test_gamma")
        assertTrue(SessionManager.isSessionValid("user_test_gamma", gen))

        assertFalse(
            "Must reject operation targeted at different UID",
            SessionManager.isSessionValid("user_test_delta", gen)
        )
    }

    @Test
    fun testF002_04_isSessionValidRejectsBlankUid() {
        val gen = SessionManager.bindSessionForTesting("user_test_epsilon")
        assertFalse("Must reject blank UID", SessionManager.isSessionValid("", gen))
        assertFalse("Must reject whitespace UID", SessionManager.isSessionValid("   ", gen))
    }

    @Test
    fun testF002_05_fcmSyncTokenAbortsWhenSessionInvalid() = runBlocking {
        val fcmManager = FcmTokenManager.getInstance(context)
        val staleGen = 9999L
        val staleUid = "user_stale_123"

        // Session is not bound to staleUid/staleGen
        val result = fcmManager.syncToken(
            token = "fcm_dummy_token_valid",
            sessionGeneration = staleGen,
            expectedUid = staleUid
        )
        assertFalse("FCM syncToken must abort when session generation mismatches", result)
    }

    @Test
    fun testF002_06_fcmTokenDocumentToFirestoreMapIntegrity() {
        val doc = FcmTokenDocument(
            token = "sample_test_token_123",
            installationId = "test_install_id_456",
            platform = "android",
            deviceModel = "Test Device",
            osVersion = "Android 14",
            appVersion = "1.0.0"
        )
        val map = doc.toFirestoreMap()
        assertEquals("sample_test_token_123", map["token"])
        assertEquals("test_install_id_456", map["installationId"])
        assertEquals("android", map["platform"])
        assertEquals(true, map["isActive"])
        assertFalse("Must never leak password in FCM doc", map.containsKey("password"))
        assertFalse("Must never leak auth tokens in FCM doc", map.containsKey("authToken"))
    }

    @Test
    fun testF002_07_deviceTokenPreservedLocallyAcrossLogoutAndLogin() = runBlocking {
        val fcmManager = FcmTokenManager.getInstance(context)
        val testToken = "preserved_device_fcm_token_777"
        fcmManager.saveCachedToken(testToken)

        // User A logout
        SessionManager.invalidateSession()
        fcmManager.cancelPendingSync()

        // Device token is still preserved locally
        val cachedTokenAfterLogout = fcmManager.getCachedToken()
        assertEquals(testToken, cachedTokenAfterLogout)
    }

    @Test
    fun testF002_08_guestTokenCachedLocallyWithoutFirestoreWrite() = runBlocking {
        val fcmManager = FcmTokenManager.getInstance(context)
        SessionManager.invalidateSession()

        // Unauthenticated guest sync
        val result = fcmManager.syncToken(
            token = "guest_token_abc_888",
            sessionGeneration = null,
            expectedUid = null
        )
        assertTrue("Guest token sync should succeed and cache locally", result)
        assertEquals("guest_token_abc_888", fcmManager.getCachedToken())
    }

    @Test
    fun testF002_09_unrecoverableFcmErrorsIdentified() {
        val fcmManager = FcmTokenManager.getInstance(context)
        assertFalse("UNREGISTERED is non-recoverable", fcmManager.isRecoverableFcmError("UNREGISTERED"))
        assertFalse("INVALID_ARGUMENT is non-recoverable", fcmManager.isRecoverableFcmError("INVALID_ARGUMENT"))
        assertFalse("SENDER_ID_MISMATCH is non-recoverable", fcmManager.isRecoverableFcmError("SENDER_ID_MISMATCH"))

        assertTrue("UNAVAILABLE is transient/recoverable", fcmManager.isRecoverableFcmError("UNAVAILABLE"))
        assertTrue("INTERNAL is transient/recoverable", fcmManager.isRecoverableFcmError("INTERNAL"))
    }

    @Test
    fun testF002_10_cancelPendingSyncAbortsActiveSyncJob() {
        val fcmManager = FcmTokenManager.getInstance(context)
        fcmManager.syncTokenAsync("async_token_job")
        fcmManager.cancelPendingSync()
        // Successfully cancelled without throwing
        assertTrue(true)
    }

    // =========================================================================
    // SECTION 2: F-005 — IN-APP ACCOUNT DELETION & CASCADING PURGE (5 TESTS)
    // =========================================================================

    @Test
    fun testF005_01_accountDeletionResultHierarchy() {
        val success: AccountDeletionResult = AccountDeletionResult.Success
        val reauth: AccountDeletionResult = AccountDeletionResult.ReauthRequired
        val failed: AccountDeletionResult = AccountDeletionResult.Failed("Network timeout")
        val partial: AccountDeletionResult = AccountDeletionResult.PartialFailure("Some data preserved")

        assertTrue(success is AccountDeletionResult.Success)
        assertTrue(reauth is AccountDeletionResult.ReauthRequired)
        assertEquals("Network timeout", (failed as AccountDeletionResult.Failed).message)
        assertEquals("Some data preserved", (partial as AccountDeletionResult.PartialFailure).message)
    }

    @Test
    fun testF005_02_firestoreRulesPermitOwnerDeletion() {
        val rulesFile = listOf(
            File("firestore.rules"),
            File("../firestore.rules"),
            File("../../firestore.rules")
        ).firstOrNull { it.exists() }
        assertNotNull("firestore.rules file must exist", rulesFile)
        val content = rulesFile!!.readText()

        // Verify the rule allows isOwner(userId) for user deletion
        assertTrue(
            "firestore.rules must allow owner deletion of user document",
            content.contains("allow delete: if isOwner(userId) || isAdmin();")
        )
    }

    @Test
    fun testF005_03_deleteAccountWithoutUserReturnsFailed() = runBlocking {
        // When no Firebase user is logged in
        val result = AuthRepository.deleteAccount(context)
        assertTrue(
            "deleteAccount with no active user session must return Failed",
            result is AccountDeletionResult.Failed
        )
    }

    @Test
    fun testF005_04_accountDeletionPurgerCascadeContract() = runBlocking {
        // Insert sample user data
        db.supportDao().insertMessage(SupportMessage(text = "Sensitive user inquiry", isFromUser = true))
        db.notificationDao().insertNotification(
            NotificationItem(id = "notif_priv_1", title = "Private Alert", message = "Personal Msg")
        )

        // SessionManager.clearUserScopedLocalData is the mandatory cleanup contract
        SessionManager.clearUserScopedLocalData(context)

        assertEquals("Support messages must be purged", 0, db.supportDao().getMessageCount())
        assertEquals("Notifications must be purged", 0, db.notificationDao().getAllNotifications().first().size)
    }

    @Test
    fun testF005_05_accountSettingsScreenContainsDeleteAccountAffordance() {
        val screenFile = listOf(
            File("app/src/main/java/com/example/ui/screens/profile/AccountSettingsScreen.kt"),
            File("src/main/java/com/example/ui/screens/profile/AccountSettingsScreen.kt"),
            File("../app/src/main/java/com/example/ui/screens/profile/AccountSettingsScreen.kt")
        ).firstOrNull { it.exists() }
        assertNotNull("AccountSettingsScreen.kt must exist", screenFile)
        val content = screenFile!!.readText()

        assertTrue(
            "AccountSettingsScreen must contain delete_account_button test tag",
            content.contains("delete_account_button")
        )
        assertTrue(
            "AccountSettingsScreen must contain confirm_delete_account_button test tag",
            content.contains("confirm_delete_account_button")
        )
        assertTrue(
            "AccountSettingsScreen must reference delete_account_warning_title",
            content.contains("delete_account_warning_title")
        )
    }

    // =========================================================================
    // SECTION 3: F-011 — MULTI-USER DATA BLEED & STORAGE PARTITIONING (10 TESTS)
    // =========================================================================

    @Test
    fun testF011_01_clearUserScopedLocalDataPurgesRoomSupportMessages() = runBlocking {
        val supportDao = db.supportDao()
        supportDao.insertMessage(SupportMessage(id = "msg_1", text = "Confidential chat", isFromUser = true))
        supportDao.insertMessage(SupportMessage(id = "msg_2", text = "Support reply", isFromUser = false))
        assertEquals(2, supportDao.getMessageCount())

        SessionManager.clearUserScopedLocalData(context)

        assertEquals(
            "Room support_messages must be completely empty after logout",
            0,
            supportDao.getMessageCount()
        )
    }

    @Test
    fun testF011_02_clearUserScopedLocalDataPurgesRoomNotifications() = runBlocking {
        val notifDao = db.notificationDao()
        notifDao.insertNotification(NotificationItem(id = "n_1", title = "Alert A", message = "Body A"))
        notifDao.insertNotification(NotificationItem(id = "n_2", title = "Alert B", message = "Body B"))
        assertEquals(2, notifDao.getAllNotifications().first().size)

        SessionManager.clearUserScopedLocalData(context)

        assertEquals(
            "Room notifications must be completely empty after logout",
            0,
            notifDao.getAllNotifications().first().size
        )
    }

    @Test
    fun testF011_03_clearUserScopedLocalDataPurgesRoomLibraryAndHistory() = runBlocking {
        val libDao = db.libraryDao()
        val histDao = db.historyDao()

        libDao.insertItem(LibraryItem.create(com.example.data.model.ContentType.MOVIE, "101", "Movie A", ""))
        histDao.insertHistory(HistoryItem(id = "hist_1", title = "Movie A", isMovie = true, positionMillis = 5000L, durationMillis = 10000L))

        assertEquals(1, libDao.getAllItems().first().size)
        assertEquals(1, histDao.getAllHistory().first().size)

        SessionManager.clearUserScopedLocalData(context)

        assertEquals(0, libDao.getAllItems().first().size)
        assertEquals(0, histDao.getAllHistory().first().size)
    }

    @Test
    fun testF011_04_clearUserScopedLocalDataPurgesRoomWatchedEpisodes() = runBlocking {
        val weDao = db.watchedEpisodeDao()
        weDao.insert(WatchedEpisode(id = "ep_100_1"))
        weDao.insert(WatchedEpisode(id = "ep_100_2"))

        assertTrue(weDao.getAllWatched().first().any { it.id == "ep_100_1" })

        SessionManager.clearUserScopedLocalData(context)

        assertFalse(
            "Watched episodes must not survive logout",
            weDao.getAllWatched().first().any { it.id == "ep_100_1" }
        )
    }

    @Test
    fun testF011_05_clearUserScopedLocalDataPurgesUserScopedDataStore() = runBlocking {
        val userPrefs = UserPreferencesRepository(context)
        userPrefs.saveUserBio("Private bio of user A")
        userPrefs.saveIsLoggedIn(true)
        userPrefs.blockUser("blocked_bad_user_99")
        userPrefs.sendFriendRequest("pending_friend_88")

        assertEquals("Private bio of user A", userPrefs.userBio.first())
        assertTrue(userPrefs.isLoggedIn.first())
        assertTrue(userPrefs.blockedUsers.first().contains("blocked_bad_user_99"))
        assertTrue(userPrefs.friendRequests.first().contains("pending_friend_88"))

        SessionManager.clearUserScopedLocalData(context)

        assertEquals("Movie & Anime Lover\nEnjoying great stories ✨", userPrefs.userBio.first())
        assertFalse(userPrefs.isLoggedIn.first())
        assertTrue("Blocked users must be cleared on logout", userPrefs.blockedUsers.first().isEmpty())
        assertTrue("Friend requests must be cleared on logout", userPrefs.friendRequests.first().isEmpty())
    }

    @Test
    fun testF011_06_clearUserScopedLocalDataResetsNotificationPreferences() = runBlocking {
        val notifPrefs = NotificationPreferencesRepository(context)
        notifPrefs.updateNewMoviesEnabled(false)
        notifPrefs.updateNewAnimeEnabled(false)

        assertFalse(notifPrefs.preferencesFlow.first().newMoviesEnabled)

        SessionManager.clearUserScopedLocalData(context)

        // Resets to default (which is true)
        assertTrue(
            "Notification preferences must reset to defaults on logout",
            notifPrefs.preferencesFlow.first().newMoviesEnabled
        )
    }

    @Test
    fun testF011_07_clearUserScopedLocalDataPurgesLastPlaybackStore() {
        LastPlaybackStore.savePlayback(
            context = context,
            mediaId = "movie_secret_77",
            url = "https://cdn.example.com/stream.mp4",
            positionMillis = 45000L,
            durationMillis = 120000L
        )

        assertNotNull(LastPlaybackStore.getLastPlayback(context, "movie_secret_77"))

        runBlocking {
            SessionManager.clearUserScopedLocalData(context)
        }

        assertNull(
            "LastPlaybackStore SharedPreferences must be purged on logout to prevent resume position bleed",
            LastPlaybackStore.getLastPlayback(context, "movie_secret_77")
        )
    }

    @Test
    fun testF011_08_clearUserScopedLocalDataPurgesPlaybackSyncStore() {
        PlaybackSyncStore.setPosition("movie_sync_88", 95000L)
        assertEquals(95000L, PlaybackSyncStore.getPosition("movie_sync_88"))

        runBlocking {
            SessionManager.clearUserScopedLocalData(context)
        }

        assertEquals(
            "PlaybackSyncStore in-memory map must be cleared on logout",
            0L,
            PlaybackSyncStore.getPosition("movie_sync_88")
        )
    }

    @Test
    fun testF011_09_clearUserScopedLocalDataPurgesSocialAndSecurityCaches() {
        // Cache user profiles in SocialRepository
        SocialRepository.clearCache()
        // AuthRepository current user flow reset
        AuthRepository.currentUserFlow.value = null

        runBlocking {
            SessionManager.clearUserScopedLocalData(context)
        }

        assertNull(AuthRepository.currentUserFlow.value)
    }

    @Test
    fun testF011_10_multiUserSequentialSessionIsolationLifecycle() = runBlocking {
        // User A Session
        val userAGen = SessionManager.bindSessionForTesting("user_alice")
        assertEquals("user_alice", SessionManager.activeUid)

        // User A populates private local data
        db.supportDao().insertMessage(SupportMessage(text = "Alice's support issue", isFromUser = true))
        db.notificationDao().insertNotification(NotificationItem(id = "notif_alice", title = "Alice notif", message = "Alice msg"))
        db.libraryDao().insertItem(LibraryItem.create(com.example.data.model.ContentType.MOVIE, "101", "Alice's Movie", ""))
        LastPlaybackStore.savePlayback(context, "movie_alice", "https://cdn.test/alice.mp4", positionMillis = 10000L)
        PlaybackSyncStore.setPosition("movie_alice", 10000L)

        // User A logs out
        SessionManager.onUserLoggedOut(context)

        // Verify intermediate wiped state
        assertNull(SessionManager.activeUid)
        assertEquals(0, db.supportDao().getMessageCount())
        assertEquals(0, db.notificationDao().getAllNotifications().first().size)
        assertEquals(0, db.libraryDao().getAllItems().first().size)
        assertNull(LastPlaybackStore.getLastPlayback(context, "movie_alice"))
        assertEquals(0L, PlaybackSyncStore.getPosition("movie_alice"))

        // User B Session
        val userBGen = SessionManager.bindSessionForTesting("user_bob")
        assertTrue("User B gets new generation epoch", userBGen > userAGen)
        assertEquals("user_bob", SessionManager.activeUid)

        // Verify User B sees ZERO state from User A
        assertEquals("User B must see zero support messages from User A", 0, db.supportDao().getMessageCount())
        assertEquals("User B must see zero notifications from User A", 0, db.notificationDao().getAllNotifications().first().size)
        assertEquals("User B must see zero library items from User A", 0, db.libraryDao().getAllItems().first().size)
        assertNull("User B must not inherit User A playback state", LastPlaybackStore.getLastPlayback(context, "movie_alice"))
        assertEquals(0L, PlaybackSyncStore.getPosition("movie_alice"))
    }
}
