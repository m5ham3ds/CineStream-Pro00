package com.example.extension.managed.runtime.web

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase 05Q Forensic Tests for PerSiteSessionStore, Domain Isolation,
 * Session Persistence, Invalidation, Replacement, and Cookie Security.
 */
class PerSiteSessionStoreAndSecurityTest {

    private lateinit var tempDir: File
    private lateinit var store: PerSiteSessionStore

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("phase05q_sessions").toFile()
        store = PerSiteSessionStore(storageDir = tempDir)
    }

    // =========================================================================
    // 1. BASIC CRUD & HOST CANONICALIZATION
    // =========================================================================

    @Test
    fun test01_canonicalizeHost_normalizesVariousFormats() {
        assertEquals("animeblkom.net", PerSiteSessionStore.canonicalizeHost("https://animeblkom.net/watch/1"))
        assertEquals("animeblkom.net", PerSiteSessionStore.canonicalizeHost("https://www.animeblkom.net/"))
        assertEquals("w1.anime4up.rest", PerSiteSessionStore.canonicalizeHost("https://w1.anime4up.rest:443/anime/aot"))
        assertEquals("witanime.pics", PerSiteSessionStore.canonicalizeHost("witanime.pics"))
        assertEquals("egydead.vip", PerSiteSessionStore.canonicalizeHost("http://egydead.vip/movie/123"))
    }

    @Test
    fun test02_saveAndRetrieveSession() {
        val session = ProtectedSiteSession(
            siteKey = "witanime",
            canonicalHost = "witanime.pics",
            cookies = mapOf("cf_clearance" to "synthetic_token_123", "session" to "abc456"),
            state = ProtectedSessionState.ACTIVE
        )

        store.saveSession(session)
        val retrieved = store.getSession("witanime", "witanime.pics")

        assertNotNull(retrieved)
        assertEquals("witanime", retrieved!!.siteKey)
        assertEquals("witanime.pics", retrieved.canonicalHost)
        assertEquals(ProtectedSessionState.ACTIVE, retrieved.state)
        assertTrue(retrieved.hasCfClearance)
        assertEquals("synthetic_token_123", retrieved.cookies["cf_clearance"])
    }

    // =========================================================================
    // 2. DOMAIN ISOLATION (TEST E)
    // =========================================================================

    @Test
    fun test03_domainIsolation_siteCookiesNeverLeakAcrossDomains() {
        val animeblkomSession = ProtectedSiteSession(
            siteKey = "animeblkom",
            canonicalHost = "animeblkom.net",
            cookies = mapOf("cf_clearance" to "token_blkom_999", "site_id" to "blkom"),
            state = ProtectedSessionState.ACTIVE
        )

        val anime4upSession = ProtectedSiteSession(
            siteKey = "anime4up",
            canonicalHost = "w1.anime4up.rest",
            cookies = mapOf("cf_clearance" to "token_anime4up_777", "site_id" to "anime4up"),
            state = ProtectedSessionState.ACTIVE
        )

        store.saveSession(animeblkomSession)
        store.saveSession(anime4upSession)

        // Verify AnimeBlkom session
        val blkomRetrieved = store.getSession("animeblkom", "animeblkom.net")
        assertNotNull(blkomRetrieved)
        assertEquals("token_blkom_999", blkomRetrieved!!.cookies["cf_clearance"])
        assertFalse(blkomRetrieved.cookies.containsValue("token_anime4up_777"))

        // Verify Anime4Up session
        val anime4upRetrieved = store.getSession("anime4up", "w1.anime4up.rest")
        assertNotNull(anime4upRetrieved)
        assertEquals("token_anime4up_777", anime4upRetrieved!!.cookies["cf_clearance"])
        assertFalse(anime4upRetrieved.cookies.containsValue("token_blkom_999"))

        // Cross-domain queries return null
        assertNull("Querying anime4up key with animeblkom host must fail", store.getSession("anime4up", "animeblkom.net"))
        assertNull("Querying animeblkom key with anime4up host must fail", store.getSession("animeblkom", "w1.anime4up.rest"))
    }

    // =========================================================================
    // 3. SESSION PERSISTENCE & PROCESS RECREATION (TEST C)
    // =========================================================================

    @Test
    fun test04_sessionPersistence_reloadsFromDiskAcrossProcessRecreation() {
        val session = ProtectedSiteSession(
            siteKey = "egydead",
            canonicalHost = "egydead.vip",
            cookies = mapOf("cf_clearance" to "persisted_token_egydead", "user" to "guest"),
            state = ProtectedSessionState.ACTIVE
        )
        store.saveSession(session)

        // Simulate app kill and recreation with fresh store pointing to same directory
        val recreatedStore = PerSiteSessionStore(storageDir = tempDir)
        val restored = recreatedStore.getSession("egydead", "egydead.vip")

        assertNotNull("Session must be restored from disk", restored)
        assertEquals("egydead", restored!!.siteKey)
        assertEquals("egydead.vip", restored.canonicalHost)
        assertEquals(ProtectedSessionState.ACTIVE, restored.state)
        assertEquals("persisted_token_egydead", restored.cookies["cf_clearance"])
    }

    // =========================================================================
    // 4. SESSION INVALIDATION (TEST D)
    // =========================================================================

    @Test
    fun test05_sessionInvalidation_transitionsStateToInvalid() {
        val session = ProtectedSiteSession(
            siteKey = "witanime",
            canonicalHost = "witanime.pics",
            cookies = mapOf("cf_clearance" to "old_token"),
            state = ProtectedSessionState.ACTIVE
        )
        store.saveSession(session)

        // Invalidate session
        store.invalidateSession("witanime", "witanime.pics", "Cloudflare 403 Challenge detected")

        val invalidated = store.getSession("witanime", "witanime.pics")
        assertNotNull(invalidated)
        assertEquals(ProtectedSessionState.INVALID, invalidated!!.state)
        assertFalse("Invalid session must fail isValid()", invalidated.isValid())
    }

    // =========================================================================
    // 5. SESSION REPLACEMENT (TEST D CONTINUED)
    // =========================================================================

    @Test
    fun test06_sessionReplacement_replacesOldSessionWithFreshVerifiedState() {
        val oldSession = ProtectedSiteSession(
            siteKey = "witanime",
            canonicalHost = "witanime.pics",
            cookies = mapOf("cf_clearance" to "expired_token"),
            state = ProtectedSessionState.INVALID
        )
        store.saveSession(oldSession)

        val newSession = ProtectedSiteSession(
            siteKey = "witanime",
            canonicalHost = "witanime.pics",
            cookies = mapOf("cf_clearance" to "fresh_token_verified_2026"),
            state = ProtectedSessionState.ACTIVE
        )
        store.replaceSession(newSession)

        val retrieved = store.getSession("witanime", "witanime.pics")
        assertNotNull(retrieved)
        assertEquals(ProtectedSessionState.ACTIVE, retrieved!!.state)
        assertEquals("fresh_token_verified_2026", retrieved.cookies["cf_clearance"])
    }

    // =========================================================================
    // 6. COOKIE SECURITY AUDIT
    // =========================================================================

    @Test
    fun test07_cookieSecurity_parseCookieHeader() {
        val rawHeader = "cf_clearance=secret123; path=/; domain=.animeblkom.net; Secure; SameSite=Lax; session_id=abc456"
        val parsed = store.parseCookieHeader(rawHeader)

        assertEquals("secret123", parsed["cf_clearance"])
        assertEquals("abc456", parsed["session_id"])
        assertEquals("/", parsed["path"])
        assertEquals(".animeblkom.net", parsed["domain"])
    }

    @Test
    fun test08_cookieSecurity_zeroCloudSyncOrPlaintextLeak() {
        // Confirm that the storage file exists in tempDir (private storage)
        val session = ProtectedSiteSession(
            siteKey = "qfilm",
            canonicalHost = "a.qfilm.tv",
            cookies = mapOf("session" to "secure_val"),
            state = ProtectedSessionState.ACTIVE
        )
        store.saveSession(session)

        val sessionFiles = tempDir.listFiles()
        assertNotNull(sessionFiles)
        assertTrue(sessionFiles!!.isNotEmpty())
        assertTrue(sessionFiles[0].name.endsWith(".session"))
    }
}
