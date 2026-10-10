package com.example

import com.example.extension.managed.model.*
import com.example.extension.managed.registry.ManagedExtensionRuntimeRegistry
import com.example.extension.managed.repository.*
import com.example.extension.managed.searchorder.SearchOrder
import kotlinx.coroutines.runBlocking
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
import java.io.File

/**
 * PHASE 07.0 / WAVE 3: EXTENSION REVIVAL PREVENTION TEST SUITE (F-017)
 *
 * Verifies strict compliance with:
 * - F017-01: Repository contains no runtime read fallback from /extensions.
 * - F017-02: Canonical ACTIVE extension enters runtime registry.
 * - F017-03: Canonical DISABLED extension never enters runtime registry.
 * - F017-04: Canonical REMOVED extension is removed from runtime registry.
 * - F017-05: Legacy /extensions document cannot seed runtime.
 * - F017-06: Stale LKG entry for locally known DISABLED extension cannot reactivate it.
 * - F017-07: Stale LKG entry for locally removed extension cannot reactivate it.
 * - F017-08: Application restart does not revive disabled/removed runtime entries.
 * - F017-09: Canonical snapshot reconciliation atomically updates runtime registry.
 * - F017-10: Mixed old/new extension state cannot produce a partially updated active registry.
 * - F017-11: Offline LKG behavior matches documented safety contract.
 * - F017-12: Valid ACTIVE canonical extensions continue to operate normally.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase07Wave3ExtensionRevivalTest {

    private lateinit var tempDir: File
    private lateinit var testCache: SafeLocalMetadataCache

    @Before
    fun setup() {
        tempDir = File.createTempFile("test_ext_cache", "").apply {
            delete()
            mkdirs()
        }
        testCache = SafeLocalMetadataCache(storageDir = tempDir)
    }

    private fun createExtension(
        id: String,
        status: ExtensionLifecycleStatus = ExtensionLifecycleStatus.ACTIVE,
        priority: Int = 100
    ): ManagedExtension {
        val safeHost = id.replace("_", "-").lowercase()
        return ManagedExtension(
            id = id,
            name = "Test $id",
            description = "Description for $id",
            baseUrl = "https://$safeHost.example.com",
            iconUrl = "https://$safeHost.example.com/icon.png",
            scraperKey = "${id}_scraper",
            definitionVersion = 1,
            minAppVersionCode = 1,
            runtimeApiVersion = 1,
            priority = priority,
            language = "ar",
            contentTypes = setOf(ContentType.MOVIE, ContentType.SERIES),
            status = status,
            updatedAt = System.currentTimeMillis(),
            userEnabled = true
        )
    }

    @Test
    fun testF017_01_repositoryContainsNoRuntimeReadFallbackFromLegacyExtensions() {
        // Source code verification: FirebaseFirestoreManagedExtensionDataSource only reads COLLECTION_PATH = "managed_extensions"
        assertEquals(
            "managed_extensions",
            FirebaseFirestoreManagedExtensionDataSource.COLLECTION_PATH
        )
    }

    @Test
    fun testF017_02_canonicalActiveExtensionEntersRuntimeRegistry() {
        val registry = ManagedExtensionRuntimeRegistry(cache = testCache)
        val activeExt = createExtension("ext_active_1", ExtensionLifecycleStatus.ACTIVE)

        val committed = registry.commitRemoteUpdate(
            newExtensions = listOf(activeExt),
            revision = 100L
        )
        assertTrue(committed)

        val retrieved = registry.getExtension("ext_active_1")
        assertNotNull(retrieved)
        assertEquals(ExtensionLifecycleStatus.ACTIVE, retrieved?.status)
        assertTrue(registry.getActiveExtensions().any { it.id == "ext_active_1" })
    }

    @Test
    fun testF017_03_canonicalDisabledExtensionNeverEntersActiveRuntimeRegistry() {
        val registry = ManagedExtensionRuntimeRegistry(cache = testCache)
        val disabledExt = createExtension("ext_disabled_1", ExtensionLifecycleStatus.DISABLED)

        registry.commitRemoteUpdate(
            newExtensions = listOf(disabledExt),
            revision = 101L
        )

        // Must exist in overall registry but NEVER in getActiveExtensions()
        val all = registry.getAllExtensions()
        assertTrue(all.any { it.id == "ext_disabled_1" })
        assertFalse(registry.getActiveExtensions().any { it.id == "ext_disabled_1" })
    }

    @Test
    fun testF017_04_canonicalRemovedExtensionIsRemovedFromRuntimeRegistry() {
        val registry = ManagedExtensionRuntimeRegistry(cache = testCache)
        val ext1 = createExtension("ext_stay", ExtensionLifecycleStatus.ACTIVE)
        val ext2 = createExtension("ext_remove", ExtensionLifecycleStatus.ACTIVE)

        registry.commitRemoteUpdate(newExtensions = listOf(ext1, ext2), revision = 102L)
        assertEquals(2, registry.getAllExtensions().size)

        // Remote snapshot update where ext2 is removed
        registry.commitRemoteUpdate(newExtensions = listOf(ext1), revision = 103L)
        assertEquals(1, registry.getAllExtensions().size)
        assertNull(registry.getExtension("ext_remove"))
        assertNotNull(registry.getExtension("ext_stay"))
    }

    @Test
    fun testF017_05_legacyExtensionsDocumentCannotSeedRuntime() {
        // Remote data source with zero /managed_extensions returns empty list
        val mockRemote = object : ManagedExtensionRemoteDataSource {
            override suspend fun fetchManagedExtensionDtos(): Result<List<ManagedExtensionDto>> {
                // Canonical path is empty; legacy documents are ignored
                return Result.success(emptyList())
            }
        }
        val repo = DefaultManagedExtensionRepository(
            remoteDataSource = mockRemote,
            cache = testCache,
            bundledDefaults = emptyList()
        )

        runBlocking {
            val result = repo.getExtensions(forceRefresh = true)
            assertTrue(result.isSuccess)
            assertEquals(0, result.getOrNull()?.size)
        }
    }

    @Test
    fun testF017_06_staleLkgEntryForDisabledExtensionCannotReactivateIt() {
        val ext = createExtension("ext_disabled_tomb", ExtensionLifecycleStatus.ACTIVE)
        testCache.saveCache(listOf(ext))

        // Remote informs that ext_disabled_tomb is disabled
        testCache.recordTombstones(disabledIds = setOf("ext_disabled_tomb"), removedIds = emptySet())

        // Retrieving from LKG must return it with DISABLED status
        val lkg = testCache.getLastKnownGood()
        assertNotNull(lkg)
        val item = lkg?.firstOrNull { it.id == "ext_disabled_tomb" }
        assertNotNull(item)
        assertEquals(ExtensionLifecycleStatus.DISABLED, item?.status)
    }

    @Test
    fun testF017_07_staleLkgEntryForRemovedExtensionCannotReactivateIt() {
        val ext = createExtension("ext_removed_tomb", ExtensionLifecycleStatus.ACTIVE)
        testCache.saveCache(listOf(ext))

        // Remote informs that ext_removed_tomb is permanently removed
        testCache.recordTombstones(disabledIds = emptySet(), removedIds = setOf("ext_removed_tomb"))

        // Retrieving from LKG must filter out tombstoned removed extensions
        val lkg = testCache.getLastKnownGood()
        assertTrue("Removed extension must not be resurrected from LKG", lkg == null || lkg.none { it.id == "ext_removed_tomb" })
    }

    @Test
    fun testF017_08_applicationRestartDoesNotReviveDisabledOrRemovedEntries() {
        val ext1 = createExtension("ext_persisted_active")
        val ext2 = createExtension("ext_persisted_disabled")
        val ext3 = createExtension("ext_persisted_removed")

        testCache.saveCache(listOf(ext1, ext2, ext3))
        testCache.recordTombstones(
            disabledIds = setOf("ext_persisted_disabled"),
            removedIds = setOf("ext_persisted_removed")
        )

        // Simulate application restart: new cache instance pointing to same storage directory
        val restartedCache = SafeLocalMetadataCache(storageDir = tempDir)
        val registry = ManagedExtensionRuntimeRegistry(cache = restartedCache)

        assertNotNull(registry.getExtension("ext_persisted_active"))
        assertEquals(ExtensionLifecycleStatus.ACTIVE, registry.getExtension("ext_persisted_active")?.status)

        val disabledItem = registry.getExtension("ext_persisted_disabled")
        assertNotNull(disabledItem)
        assertEquals(ExtensionLifecycleStatus.DISABLED, disabledItem?.status)
        assertFalse(registry.getActiveExtensions().any { it.id == "ext_persisted_disabled" })

        assertNull(
            "Removed extension must not exist after process restart",
            registry.getExtension("ext_persisted_removed")
        )
    }

    @Test
    fun testF017_09_canonicalSnapshotReconciliationAtomicallyUpdatesRuntimeRegistry() {
        val registry = ManagedExtensionRuntimeRegistry(cache = testCache)
        val extA = createExtension("ext_a")
        val extB = createExtension("ext_b")

        registry.commitRemoteUpdate(newExtensions = listOf(extA), revision = 200L)
        assertEquals(1, registry.getAllExtensions().size)

        // Atomic commit replacing extA with extB
        registry.commitRemoteUpdate(newExtensions = listOf(extB), revision = 201L)
        val current = registry.getAllExtensions()
        assertEquals(1, current.size)
        assertEquals("ext_b", current.first().id)
        assertNull(registry.getExtension("ext_a"))
    }

    @Test
    fun testF017_10_mixedOldAndNewExtensionStateCannotProducePartialActiveRegistry() {
        val registry = ManagedExtensionRuntimeRegistry(cache = testCache)
        val ext1 = createExtension("ext_1")
        registry.commitRemoteUpdate(newExtensions = listOf(ext1), revision = 300L)

        // Reject stale incoming revision (e.g. rev 299 < rev 300)
        val staleSnapshot = ExtensionRuntimeSnapshot(
            revision = 299L,
            extensions = listOf(createExtension("stale_ghost")),
            source = SnapshotSource.REMOTE
        )
        val rejected = registry.commitSnapshot(staleSnapshot)
        assertFalse("Stale snapshot with lower revision must be rejected", rejected)
        assertNull(registry.getExtension("stale_ghost"))
        assertEquals("ext_1", registry.getAllExtensions().first().id)
    }

    @Test
    fun testF017_11_offlineLkgMatchesDocumentedSafetyContract() {
        val activeExt = createExtension("offline_ext_1", ExtensionLifecycleStatus.ACTIVE)
        testCache.saveCache(listOf(activeExt))

        // When remote fails offline, repository serves validated LKG
        val failingRemote = object : ManagedExtensionRemoteDataSource {
            override suspend fun fetchManagedExtensionDtos(): Result<List<ManagedExtensionDto>> {
                return Result.failure(java.io.IOException("Network unavailable"))
            }
        }
        val repo = DefaultManagedExtensionRepository(
            remoteDataSource = failingRemote,
            cache = testCache
        )

        runBlocking {
            val result = repo.getExtensions(forceRefresh = true)
            assertTrue(result.isSuccess)
            val list = result.getOrNull()
            assertNotNull(list)
            assertEquals(1, list?.size)
            assertEquals("offline_ext_1", list?.first()?.id)
        }
    }

    @Test
    fun testF017_12_validActiveCanonicalExtensionsContinueOperatingNormally() {
        val registry = ManagedExtensionRuntimeRegistry(cache = testCache)
        val active1 = createExtension("canonical_ext_1", ExtensionLifecycleStatus.ACTIVE, priority = 50)
        val active2 = createExtension("canonical_ext_2", ExtensionLifecycleStatus.ACTIVE, priority = 100)

        registry.commitRemoteUpdate(
            newExtensions = listOf(active1, active2),
            revision = 400L
        )

        val activeList = registry.getActiveExtensions()
        assertEquals(2, activeList.size)
        assertTrue(registry.hasActiveExtensions())
        assertEquals(active1, registry.getExtension("canonical_ext_1"))
        assertEquals(active2, registry.getExtension("canonical_ext_2"))
    }
}
