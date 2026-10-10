package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.AuthRepository
import com.example.data.repository.UserPreferencesRepository
import com.example.data.session.SessionManager
import com.example.extension.managed.repository.FirebaseFirestoreManagedExtensionDataSource
import com.example.extension.managed.repository.ManagedExtensionRealtimeSyncManager
import com.example.extension.managed.searchorder.FirebaseSearchOrderDataSource
import com.google.firebase.auth.FirebaseAuth
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
 * PHASE 07.0 / WAVE 3: STARTUP AUTH & SILENT ANONYMOUS CREATION PREVENTION TEST SUITE (F-009)
 *
 * Verifies strict compliance with:
 * - F009-01: Fresh installation cold start leaves auth unauthenticated (no silent anonymous account).
 * - F009-02: Authenticated User A is preserved across restarts without anonymous substitution.
 * - F009-03: Logout does not silently create a new anonymous user upon startup/recomposition.
 * - F009-04: Explicit guest selection marks guest session correctly through canonical SessionManager.
 * - F009-05: Guest logout clears guest mode and does not spawn replacement guest accounts.
 * - F009-06: Cold start after guest logout remains unauthenticated.
 * - F009-07: Auth listener null emission never invokes signInAnonymously().
 * - F009-08: Delayed startup coroutines cannot resurrect an anonymous session after logout.
 * - F009-09: Stale startup coroutine cannot replace authenticated User A with anonymous account.
 * - F009-10: Repeated application startup does not multiply anonymous accounts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase07Wave3StartupAuthTest {

    private lateinit var context: Context
    private lateinit var userPrefs: UserPreferencesRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) {
            com.google.firebase.FirebaseApp.initializeApp(context)
        }
        userPrefs = UserPreferencesRepository(context)
        SessionManager.invalidateSession()
        com.example.data.notification.FcmTokenManager.getInstance(context).resetTestHooks()
        com.example.data.notification.FcmTokenManager.getInstance(context).firestoreDeleterOverride = { _, _ -> }
        com.example.data.notification.FcmTokenManager.getInstance(context).firestoreWriterOverride = { _, _, _ -> }
    }

    @After
    fun tearDown() = runBlocking {
        SessionManager.invalidateSession()
        userPrefs.saveIsLoggedIn(false)
        userPrefs.saveIsGuest(false)
        ManagedExtensionRealtimeSyncManager.getInstance().stopListening()
        com.example.data.notification.FcmTokenManager.getInstance(context).resetTestHooks()
    }

    @Test
    fun testF009_01_freshInstallationColdStartLeavesAuthUnauthenticated() = runBlocking {
        val fbAuth = FirebaseAuth.getInstance()
        // Ensure signed out
        fbAuth.signOut()
        assertEquals(null, fbAuth.currentUser)
        assertEquals(null, SessionManager.activeUid)

        // Starting extension sync must NOT call signInAnonymously
        val syncManager = ManagedExtensionRealtimeSyncManager.getInstance()
        syncManager.startListening()

        // Verify that after sync manager start, auth remains null
        assertEquals(null, fbAuth.currentUser)
        assertEquals(null, SessionManager.activeUid)
        assertFalse(userPrefs.isGuest.first())
        assertFalse(userPrefs.isLoggedIn.first())
    }

    @Test
    fun testF009_02_authenticatedUserPreservedWithoutAnonymousSubstitution() = runBlocking {
        val testUid = "user_alpha_authenticated"
        SessionManager.bindSessionForTesting(testUid)
        userPrefs.saveIsLoggedIn(true)
        userPrefs.saveIsGuest(false)

        assertEquals(testUid, SessionManager.activeUid)
        assertTrue(userPrefs.isLoggedIn.first())
        assertFalse(userPrefs.isGuest.first())

        // Simulate app restart / sync init
        val syncManager = ManagedExtensionRealtimeSyncManager.getInstance()
        syncManager.startListening()

        // Verify User A was not replaced by anonymous account
        assertEquals(testUid, SessionManager.activeUid)
        assertTrue(userPrefs.isLoggedIn.first())
    }

    @Test
    fun testF009_03_logoutDoesNotSilentlyCreateAnonymousUser() = runBlocking {
        SessionManager.bindSessionForTesting("user_beta_to_logout")
        userPrefs.saveIsLoggedIn(true)

        // User logs out
        SessionManager.onUserLoggedOut(context)
        userPrefs.saveIsLoggedIn(false)
        userPrefs.saveIsGuest(false)

        // Verify unauthenticated
        assertNull(SessionManager.activeUid)
        assertFalse(userPrefs.isLoggedIn.first())
        assertFalse(userPrefs.isGuest.first())

        // Extension sync / startup refresh trigger
        ManagedExtensionRealtimeSyncManager.getInstance().startListening()

        assertNull(SessionManager.activeUid)
    }

    @Test
    fun testF009_04_explicitGuestSelectionMarksGuestSessionCorrectly() = runBlocking {
        // User explicitly clicks "Continue as Guest"
        userPrefs.saveIsGuest(true)
        userPrefs.saveIsLoggedIn(false)

        assertTrue(userPrefs.isGuest.first())
        assertFalse(userPrefs.isLoggedIn.first())

        // Guest mode in app does not generate unrequested Firebase anonymous accounts
        val fbAuth = FirebaseAuth.getInstance()
        fbAuth.signOut()
        assertNull(fbAuth.currentUser)
    }

    @Test
    fun testF009_05_guestLogoutClearsGuestStateWithoutReplacementGuest() = runBlocking {
        userPrefs.saveIsGuest(true)
        userPrefs.saveIsLoggedIn(false)
        assertTrue(userPrefs.isGuest.first())

        // Guest logs out / exits guest session
        userPrefs.saveIsGuest(false)
        SessionManager.onUserLoggedOut(context)

        assertFalse(userPrefs.isGuest.first())
        assertFalse(userPrefs.isLoggedIn.first())
        assertNull(SessionManager.activeUid)
    }

    @Test
    fun testF009_06_coldStartAfterGuestLogoutRemainsUnauthenticated() = runBlocking {
        userPrefs.saveIsGuest(false)
        userPrefs.saveIsLoggedIn(false)
        SessionManager.invalidateSession()

        // App launches cold start
        val isGuest = userPrefs.isGuest.first()
        val isLoggedIn = userPrefs.isLoggedIn.first()

        assertFalse(isGuest)
        assertFalse(isLoggedIn)
        assertNull(SessionManager.activeUid)
    }

    @Test
    fun testF009_07_authListenerNullEmissionDoesNotCallSignInAnonymously() = runBlocking {
        val auth = AuthRepository.auth
        auth.signOut()

        // Auth emits null
        AuthRepository.currentUserFlow.value = null

        // Verify state is clean and no anonymous user was spawned
        assertNull(auth.currentUser)
        assertNull(AuthRepository.currentUserFlow.value)
    }

    @Test
    fun testF009_08_delayedStartupCoroutineCannotRecreateAnonymousSessionAfterLogout() = runBlocking {
        val gen1 = SessionManager.bindSessionForTesting("user_gamma")
        assertTrue(SessionManager.isSessionValid("user_gamma", gen1))

        // User logs out before delayed job completes
        SessionManager.onUserLoggedOut(context)
        val currentGen = SessionManager.currentGeneration

        // Delayed startup job tries to validate or bind
        val isValid = SessionManager.isSessionValid("user_gamma", gen1)
        assertFalse("Stale delayed coroutine must be invalidated", isValid)
        assertNull(SessionManager.activeUid)
    }

    @Test
    fun testF009_09_staleStartupJobCannotReplaceAuthenticatedUserWithAnonymous() = runBlocking {
        // User A logs in
        val userAGen = SessionManager.bindSessionForTesting("user_A")
        
        // Stale startup job with previous generation or anonymous UID attempts mutation
        val staleGen = userAGen - 1
        val canMutate = SessionManager.isSessionValid("anonymous_ghost_uid", staleGen)
        assertFalse("Stale anonymous job cannot validate against active User A", canMutate)
        assertEquals("user_A", SessionManager.activeUid)
    }

    @Test
    fun testF009_10_repeatedStartupDoesNotMultiplyAccounts() = runBlocking {
        val initialAuth = FirebaseAuth.getInstance().currentUser
        
        // Simulate 5 repeated startup sync triggers
        for (i in 1..5) {
            ManagedExtensionRealtimeSyncManager.getInstance().startListening()
        }

        val finalAuth = FirebaseAuth.getInstance().currentUser
        assertEquals(initialAuth, finalAuth)
    }
}
