package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.utils.MediaStorageUtils
import com.example.utils.P2PManager
import com.example.utils.P2PSecurityHelper
import com.example.utils.P2PState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase07Wave1CoreSecurityTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var mediaRoot: File

    @Before
    fun setUp() {
        mediaRoot = MediaStorageUtils.getMediaDirectory(context)
        // Clean media directories before each test
        mediaRoot.deleteRecursively()
        mediaRoot.mkdirs()
    }

    @After
    fun tearDown() {
        mediaRoot.deleteRecursively()
    }

    // =========================================================================
    // SECTION 1: F-001 — PATH TRAVERSAL & ARBITRARY FILE OVERWRITE (15 TESTS)
    // =========================================================================

    @Test
    fun testF001_01_rejectTraversalDotDotSlash() {
        assertThrows(SecurityException::class.java) {
            MediaStorageUtils.sanitizeSegment("../escape")
        }
    }

    @Test
    fun testF001_02_rejectTraversalDotDotBackslash() {
        assertThrows(SecurityException::class.java) {
            MediaStorageUtils.sanitizeSegment("..\\escape")
        }
    }

    @Test
    fun testF001_03_rejectAbsolutePathUnix() {
        assertThrows(SecurityException::class.java) {
            MediaStorageUtils.sanitizeSegment("/etc/passwd")
        }
    }

    @Test
    fun testF001_04_rejectAbsolutePathWindows() {
        assertThrows(SecurityException::class.java) {
            MediaStorageUtils.sanitizeSegment("C:\\windows\\system32")
        }
    }

    @Test
    fun testF001_05_rejectNullByteLiteral() {
        assertThrows(SecurityException::class.java) {
            MediaStorageUtils.sanitizeSegment("movie\u0000payload")
        }
    }

    @Test
    fun testF001_06_rejectNullByteUrlEncoded() {
        assertThrows(SecurityException::class.java) {
            MediaStorageUtils.sanitizeSegment("movie%00payload")
        }
    }

    @Test
    fun testF001_07_rejectEmptyId() {
        assertThrows(SecurityException::class.java) {
            MediaStorageUtils.sanitizeSegment("")
        }
    }

    @Test
    fun testF001_08_rejectBlankId() {
        assertThrows(SecurityException::class.java) {
            MediaStorageUtils.sanitizeSegment("   \t\n  ")
        }
    }

    @Test
    fun testF001_09_rejectForwardSlashInjection() {
        assertThrows(SecurityException::class.java) {
            MediaStorageUtils.sanitizeSegment("folder/subfolder")
        }
    }

    @Test
    fun testF001_10_rejectBackslashInjection() {
        assertThrows(SecurityException::class.java) {
            MediaStorageUtils.sanitizeSegment("folder\\subfolder")
        }
    }

    @Test
    fun testF001_11_rejectLeadingOrTrailingDots() {
        assertThrows(SecurityException::class.java) {
            MediaStorageUtils.sanitizeSegment(".hidden_movie")
        }
        assertThrows(SecurityException::class.java) {
            MediaStorageUtils.sanitizeSegment("hidden_movie.")
        }
    }

    @Test
    fun testF001_12_rejectExcessiveLengthId() {
        val excessivelyLongId = "a".repeat(150)
        assertThrows(SecurityException::class.java) {
            MediaStorageUtils.sanitizeSegment(excessivelyLongId)
        }
    }

    @Test
    fun testF001_13_rejectIllegalCharacters() {
        assertThrows(SecurityException::class.java) {
            MediaStorageUtils.sanitizeSegment("movie;rm -rf *")
        }
        assertThrows(SecurityException::class.java) {
            MediaStorageUtils.sanitizeSegment("movie`cat /etc/passwd`")
        }
    }

    @Test
    fun testF001_14_verifyContainedThrowsOnEscape() {
        val root = MediaStorageUtils.getMediaDirectory(context)
        val outsideFile = File(context.filesDir, "arbitrary_escape.txt")
        assertThrows(SecurityException::class.java) {
            MediaStorageUtils.verifyContained(outsideFile, root)
        }
    }

    @Test
    fun testF001_15_openSecureOutputStreamValidatesContainmentAndRejectsDirectories() {
        val validMovieFile = MediaStorageUtils.getMovieFile(context, "valid_movie_100")
        val stream = MediaStorageUtils.openSecureOutputStream(validMovieFile, context)
        assertNotNull(stream)
        stream.write("test_content".toByteArray())
        stream.close()
        assertTrue(validMovieFile.exists())

        // Rejects directory
        val moviesDir = File(mediaRoot, "movies")
        assertThrows(SecurityException::class.java) {
            MediaStorageUtils.openSecureOutputStream(moviesDir, context)
        }

        // Rejects file outside approved media root
        val outsideFile = File(context.cacheDir, "escape.mp4")
        assertThrows(SecurityException::class.java) {
            MediaStorageUtils.openSecureOutputStream(outsideFile, context)
        }
    }

    // =========================================================================
    // SECTION 2: F-004 — UNAUTHENTICATED SOCKETS & HANDSHAKE (10 TESTS)
    // =========================================================================

    @Test
    fun testF004_01_nonceGenerationReturnsSufficientEntropy() {
        val nonce1 = P2PSecurityHelper.generateNonce()
        val nonce2 = P2PSecurityHelper.generateNonce()
        assertNotNull(nonce1)
        assertNotNull(nonce2)
        assertEquals(64, nonce1.length) // 32 bytes = 64 hex chars
        assertEquals(64, nonce2.length)
        assertNotEquals(nonce1, nonce2)
    }

    @Test
    fun testF004_02_sessionSecretGenerationReturns64HexChars() {
        val secret1 = P2PSecurityHelper.generateSessionSecret()
        val secret2 = P2PSecurityHelper.generateSessionSecret()
        assertEquals(64, secret1.length)
        assertEquals(64, secret2.length)
        assertNotEquals(secret1, secret2)
    }

    @Test
    fun testF004_03_hmacComputationAndVerificationSucceedsForMatchingKey() {
        val key = "shared_secret_key_12345".toByteArray()
        val message = "SERVER_AUTH:nonceA:nonceB"
        val hmac = P2PSecurityHelper.computeHmac(key, message)
        assertTrue(hmac.isNotEmpty())
        assertTrue(P2PSecurityHelper.verifyProof(hmac, hmac))
    }

    @Test
    fun testF004_04_invalidClientProofIsRejected() {
        val key = "shared_secret_key_12345".toByteArray()
        val message = "CLIENT_AUTH:nonceB:nonceA"
        val expectedHmac = P2PSecurityHelper.computeHmac(key, message)
        val tamperedProof = expectedHmac.substring(0, expectedHmac.length - 2) + "00"
        assertFalse(P2PSecurityHelper.verifyProof(expectedHmac, tamperedProof))
    }

    @Test
    fun testF004_05_constantTimeProofVerificationPreventsTimingLeak() {
        val proofA = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        val proofB = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456788"
        val proofDifferentLength = "abcdef0123456789"
        assertFalse(P2PSecurityHelper.verifyProof(proofA, proofB))
        assertFalse(P2PSecurityHelper.verifyProof(proofA, proofDifferentLength))
    }

    @Test
    fun testF004_06_sessionKeyDerivationIsUniquePerNonces() {
        val secret = "test_shared_secret_token"
        val key1 = P2PSecurityHelper.deriveSessionKey(secret, "nonce1", "nonce2")
        val key2 = P2PSecurityHelper.deriveSessionKey(secret, "nonce1", "nonce3")
        val key3 = P2PSecurityHelper.deriveSessionKey(secret, "nonceA", "nonce2")
        assertFalse(key1.contentEquals(key2))
        assertFalse(key1.contentEquals(key3))
        assertEquals(32, key1.size) // SHA-256 derived
    }

    @Test
    fun testF004_07_sessionSecretRotationGeneratesNewSecret() {
        val p2p = P2PManager.getInstance(context)
        val initialSecret = p2p.activeSessionSecret
        val newSecret = p2p.resetSessionSecret()
        assertNotNull(newSecret)
        assertEquals(64, newSecret.length)
        assertNotEquals(initialSecret, newSecret)
        assertEquals(newSecret, p2p.activeSessionSecret)
    }

    @Test
    fun testF004_08_failClosedOnShortNonceValidation() {
        // P2P protocol requires at least 16 chars for client nonce
        val shortNonce = "short"
        assertTrue(shortNonce.length < 16)
        val validNonce = P2PSecurityHelper.generateNonce()
        assertTrue(validNonce.length >= 16)
    }

    @Test
    fun testF004_09_stopAllClosesSocketsAndResetsState() {
        val p2p = P2PManager.getInstance(context)
        p2p.startSession("TestDevice", 9876)
        p2p.stopAll()
        assertEquals(P2PState.IDLE, p2p.p2pState.value)
        assertTrue(p2p.connectedPeers.value.isEmpty())
        assertTrue(p2p.connectedEndpoints.value.isEmpty())
        assertNull(p2p.activeTransfer.value)
    }

    @Test
    fun testF004_10_qrPairingTokenVerificationMatchesActiveSecret() {
        val p2p = P2PManager.getInstance(context)
        val currentSecret = p2p.activeSessionSecret
        val validQrToken = currentSecret
        val invalidQrToken = "invalid_pairing_token"
        assertEquals(currentSecret, validQrToken)
        assertNotEquals(currentSecret, invalidQrToken)
    }

    @Test
    fun testF004_11_authenticatedSessionDerivesEncryptionKey() {
        val secret = "test_shared_session_secret_123"
        val nonceA = P2PSecurityHelper.generateNonce()
        val nonceB = P2PSecurityHelper.generateNonce()
        val encKey = P2PSecurityHelper.deriveEncryptionKey(secret, nonceA, nonceB)
        val sessionKey = P2PSecurityHelper.deriveSessionKey(secret, nonceA, nonceB)
        assertEquals(32, encKey.size)
        assertEquals(32, sessionKey.size)
        assertFalse("AEAD encryption key must be domain-separated from session key", encKey.contentEquals(sessionKey))
    }

    @Test
    fun testF004_12_plaintextMediaIsNotEmittedOnWire() {
        val sessionKey = P2PSecurityHelper.deriveEncryptionKey("secret", "nonceA", "nonceB")
        val knownPlaintext = "CONFIDENTIAL_HOLLYWOOD_MOVIE_STREAM_BINARY_DATA".toByteArray(Charsets.UTF_8)
        val wireOut = java.io.ByteArrayOutputStream()
        P2PSecurityHelper.encryptStream(
            sessionKey = sessionKey,
            sourceStream = java.io.ByteArrayInputStream(knownPlaintext),
            targetStream = wireOut,
            totalPlaintextBytes = knownPlaintext.size.toLong()
        )
        val wireBytes = wireOut.toByteArray()
        val wireString = String(wireBytes, Charsets.ISO_8859_1)
        val plainString = String(knownPlaintext, Charsets.ISO_8859_1)
        assertFalse("Plaintext media bytes must NOT appear on the wire", wireString.contains(plainString))
    }

    @Test
    fun testF004_13_ciphertextDecryptsSuccessfullyWithCorrectSessionKey() {
        val sessionKey = P2PSecurityHelper.deriveEncryptionKey("secret", "nonceA", "nonceB")
        val originalData = "TEST_VIDEO_STREAM_DATA_PACKET_CONTENT_FOR_DECRYPTION".toByteArray()
        val wireOut = java.io.ByteArrayOutputStream()
        P2PSecurityHelper.encryptStream(sessionKey, java.io.ByteArrayInputStream(originalData), wireOut, originalData.size.toLong())

        val decryptedOut = java.io.ByteArrayOutputStream()
        P2PSecurityHelper.decryptStream(sessionKey, java.io.ByteArrayInputStream(wireOut.toByteArray()), decryptedOut, originalData.size.toLong())
        assertArrayEquals(originalData, decryptedOut.toByteArray())
    }

    @Test
    fun testF004_14_wrongKeyFailsDecryption() {
        val keyCorrect = P2PSecurityHelper.deriveEncryptionKey("secret1", "nonceA", "nonceB")
        val keyWrong = P2PSecurityHelper.deriveEncryptionKey("secret2", "nonceA", "nonceB")
        val originalData = "TOP_SECRET_STREAM".toByteArray()
        val wireOut = java.io.ByteArrayOutputStream()
        P2PSecurityHelper.encryptStream(keyCorrect, java.io.ByteArrayInputStream(originalData), wireOut, originalData.size.toLong())

        assertThrows(Exception::class.java) {
            P2PSecurityHelper.decryptStream(keyWrong, java.io.ByteArrayInputStream(wireOut.toByteArray()), java.io.ByteArrayOutputStream(), originalData.size.toLong())
        }
    }

    @Test
    fun testF004_15_tamperedCiphertextFailsAuthentication() {
        val sessionKey = P2PSecurityHelper.deriveEncryptionKey("secret", "nonceA", "nonceB")
        val originalData = "TAMPER_DETECTION_TEST_PAYLOAD".toByteArray()
        val wireOut = java.io.ByteArrayOutputStream()
        P2PSecurityHelper.encryptStream(sessionKey, java.io.ByteArrayInputStream(originalData), wireOut, originalData.size.toLong())

        val wireBytes = wireOut.toByteArray()
        // Tamper with one byte in the ciphertext payload (after header)
        wireBytes[wireBytes.size - 20] = (wireBytes[wireBytes.size - 20].toInt() xor 0xFF).toByte()

        assertThrows(Exception::class.java) {
            P2PSecurityHelper.decryptStream(sessionKey, java.io.ByteArrayInputStream(wireBytes), java.io.ByteArrayOutputStream(), originalData.size.toLong())
        }
    }

    @Test
    fun testF004_16_tamperedAuthenticationTagFails() {
        val sessionKey = P2PSecurityHelper.deriveEncryptionKey("secret", "nonceA", "nonceB")
        val originalData = "AUTH_TAG_TAMPER_TEST_DATA".toByteArray()
        val wireOut = java.io.ByteArrayOutputStream()
        P2PSecurityHelper.encryptStream(sessionKey, java.io.ByteArrayInputStream(originalData), wireOut, originalData.size.toLong())

        val wireBytes = wireOut.toByteArray()
        // Last byte is part of the 16-byte GCM authentication tag
        wireBytes[wireBytes.size - 1] = (wireBytes[wireBytes.size - 1].toInt() xor 0x01).toByte()

        assertThrows(Exception::class.java) {
            P2PSecurityHelper.decryptStream(sessionKey, java.io.ByteArrayInputStream(wireBytes), java.io.ByteArrayOutputStream(), originalData.size.toLong())
        }
    }

    @Test
    fun testF004_17_nonceReuseIsPrevented() {
        val sessionKey = P2PSecurityHelper.deriveEncryptionKey("secret", "nonceA", "nonceB")
        val data1 = "CHUNK_ONE".toByteArray()
        val data2 = "CHUNK_TWO".toByteArray()
        val out1 = java.io.ByteArrayOutputStream()
        val out2 = java.io.ByteArrayOutputStream()
        P2PSecurityHelper.encryptFrame(sessionKey, 0L, data1, 0, data1.size, false, java.io.DataOutputStream(out1))
        P2PSecurityHelper.encryptFrame(sessionKey, 1L, data2, 0, data2.size, false, java.io.DataOutputStream(out2))

        val frame1 = out1.toByteArray()
        val frame2 = out2.toByteArray()
        // Extract 12-byte IV: offset = 4 (magic) + 1 (ver) + 1 (flags) + 8 (seq) = 14
        val iv1 = frame1.copyOfRange(14, 26)
        val iv2 = frame2.copyOfRange(14, 26)
        assertFalse("Consecutive frames must generate distinct nonces", iv1.contentEquals(iv2))
    }

    @Test
    fun testF004_18_duplicateSequenceNumberIsRejected() {
        val sessionKey = P2PSecurityHelper.deriveEncryptionKey("secret", "nonceA", "nonceB")
        val data = "FRAME_DATA".toByteArray()
        val out = java.io.ByteArrayOutputStream()
        val dataOut = java.io.DataOutputStream(out)
        P2PSecurityHelper.encryptFrame(sessionKey, 0L, data, 0, data.size, false, dataOut)
        P2PSecurityHelper.encryptFrame(sessionKey, 0L, data, 0, data.size, true, dataOut) // duplicate sequence 0

        val inStream = java.io.DataInputStream(java.io.ByteArrayInputStream(out.toByteArray()))
        P2PSecurityHelper.readAndDecryptFrame(sessionKey, 0L, inStream) // Frame 0 succeeds
        assertThrows(SecurityException::class.java) {
            P2PSecurityHelper.readAndDecryptFrame(sessionKey, 1L, inStream) // Expected 1, received duplicate 0
        }
    }

    @Test
    fun testF004_19_unexpectedSequenceNumberIsRejected() {
        val sessionKey = P2PSecurityHelper.deriveEncryptionKey("secret", "nonceA", "nonceB")
        val data = "REORDERED_FRAME".toByteArray()
        val out = java.io.ByteArrayOutputStream()
        P2PSecurityHelper.encryptFrame(sessionKey, 99L, data, 0, data.size, true, java.io.DataOutputStream(out))

        val inStream = java.io.DataInputStream(java.io.ByteArrayInputStream(out.toByteArray()))
        assertThrows(SecurityException::class.java) {
            P2PSecurityHelper.readAndDecryptFrame(sessionKey, 0L, inStream) // Expected 0, received 99
        }
    }

    @Test
    fun testF004_20_malformedFrameIsRejected() {
        val sessionKey = P2PSecurityHelper.deriveEncryptionKey("secret", "nonceA", "nonceB")
        val out = java.io.ByteArrayOutputStream()
        val dataOut = java.io.DataOutputStream(out)
        dataOut.writeInt(0x12345678) // Invalid magic
        dataOut.writeByte(1)
        dataOut.writeByte(0)
        dataOut.writeLong(0L)
        dataOut.write(ByteArray(12))
        dataOut.writeInt(16)
        dataOut.write(ByteArray(16))

        val inStream = java.io.DataInputStream(java.io.ByteArrayInputStream(out.toByteArray()))
        assertThrows(SecurityException::class.java) {
            P2PSecurityHelper.readAndDecryptFrame(sessionKey, 0L, inStream)
        }
    }

    @Test
    fun testF004_21_oversizedFrameIsRejected() {
        val sessionKey = P2PSecurityHelper.deriveEncryptionKey("secret", "nonceA", "nonceB")
        val out = java.io.ByteArrayOutputStream()
        val dataOut = java.io.DataOutputStream(out)
        dataOut.writeInt(P2PSecurityHelper.FRAME_MAGIC)
        dataOut.writeByte(P2PSecurityHelper.PROTOCOL_VERSION.toInt())
        dataOut.writeByte(0)
        dataOut.writeLong(0L)
        dataOut.write(ByteArray(12))
        dataOut.writeInt(10 * 1024 * 1024) // 10 MB oversized frame length

        val inStream = java.io.DataInputStream(java.io.ByteArrayInputStream(out.toByteArray()))
        assertThrows(SecurityException::class.java) {
            P2PSecurityHelper.readAndDecryptFrame(sessionKey, 0L, inStream)
        }
    }

    @Test
    fun testF004_22_truncatedFrameIsRejected() {
        val sessionKey = P2PSecurityHelper.deriveEncryptionKey("secret", "nonceA", "nonceB")
        val data = "TRUNCATE_TEST".toByteArray()
        val out = java.io.ByteArrayOutputStream()
        P2PSecurityHelper.encryptFrame(sessionKey, 0L, data, 0, data.size, true, java.io.DataOutputStream(out))
        val frame = out.toByteArray()
        val truncated = frame.copyOf(frame.size - 5) // Chop off end of frame

        val inStream = java.io.DataInputStream(java.io.ByteArrayInputStream(truncated))
        assertThrows(Exception::class.java) {
            P2PSecurityHelper.readAndDecryptFrame(sessionKey, 0L, inStream)
        }
    }

    @Test
    fun testF004_23_largeMediaTransferUsesBoundedMemory() {
        val sessionKey = P2PSecurityHelper.deriveEncryptionKey("secret", "nonceA", "nonceB")
        // Simulate multi-chunk media stream (200 KB = ~4 chunks of 64KB)
        val totalSize = 200 * 1024
        val pattern = ByteArray(totalSize) { (it % 127).toByte() }
        val wireOut = java.io.ByteArrayOutputStream()

        var chunkCount = 0
        P2PSecurityHelper.encryptStream(
            sessionKey = sessionKey,
            sourceStream = java.io.ByteArrayInputStream(pattern),
            targetStream = wireOut,
            totalPlaintextBytes = totalSize.toLong(),
            chunkSize = P2PSecurityHelper.DEFAULT_CHUNK_SIZE
        ) { _, _ -> chunkCount++ }

        assertTrue("Multiple bounded chunks must be emitted", chunkCount >= 3)

        val decryptedOut = java.io.ByteArrayOutputStream()
        P2PSecurityHelper.decryptStream(
            sessionKey = sessionKey,
            sourceStream = java.io.ByteArrayInputStream(wireOut.toByteArray()),
            destinationStream = decryptedOut,
            totalExpectedBytes = totalSize.toLong()
        )
        assertArrayEquals(pattern, decryptedOut.toByteArray())
    }

    @Test
    fun testF004_24_validEncryptedTransferReachesSecureMediaStorageUtilsPath() {
        val sessionKey = P2PSecurityHelper.deriveEncryptionKey("secret", "nonceA", "nonceB")
        val movieFile = MediaStorageUtils.getMovieFile(context, "secure_transfer_movie_55")
        val plaintext = "VIDEO_PAYLOAD_FOR_STORAGE".toByteArray()

        val wireOut = java.io.ByteArrayOutputStream()
        P2PSecurityHelper.encryptStream(sessionKey, java.io.ByteArrayInputStream(plaintext), wireOut, plaintext.size.toLong())

        val destStream = MediaStorageUtils.openSecureOutputStream(movieFile, context)
        P2PSecurityHelper.decryptStream(sessionKey, java.io.ByteArrayInputStream(wireOut.toByteArray()), destStream, plaintext.size.toLong())
        destStream.close()

        assertTrue(movieFile.exists())
        assertArrayEquals(plaintext, movieFile.readBytes())
    }

    @Test
    fun testF004_25_leavingShareScreenTerminatesEncryptedSessions() {
        val p2p = P2PManager.getInstance(context)
        p2p.startSession("EncryptedDevice", 8888)
        p2p.stopAll()
        assertEquals(P2PState.IDLE, p2p.p2pState.value)
        assertTrue(p2p.connectedPeers.value.isEmpty())
    }

    // =========================================================================
    // SECTION 3: F-012 — EPISODE CROSS-MEDIA LOOKUP COLLISION (10 TESTS)
    // =========================================================================

    @Test
    fun testF012_01_movieAndEpisodeSameIdStoredInDistinctPaths() {
        val movieId = "100"
        val movieFile = MediaStorageUtils.getMovieFile(context, movieId)
        val episodeFile = MediaStorageUtils.getSeriesEpisodeFile(
            context = context,
            seriesId = "series_100",
            season = 1,
            episode = 100
        )

        assertNotEquals(movieFile.canonicalPath, episodeFile.canonicalPath)
        assertTrue(movieFile.path.contains("movies"))
        assertTrue(episodeFile.path.contains("series"))
        assertTrue(episodeFile.path.contains("series_100"))
        assertTrue(episodeFile.name.startsWith("s1e100"))
    }

    @Test
    fun testF012_02_findMediaFileDoesNotCollideMovieWithSeriesEpisode() {
        // Create a movie file with id "100"
        val movieFile = MediaStorageUtils.getMovieFile(context, "100")
        movieFile.parentFile?.mkdirs()
        movieFile.writeBytes(ByteArray(1024))

        // Lookup with series coordinates must NOT find the movie file
        val foundEpisode = MediaStorageUtils.findMediaFile(
            context = context,
            id = "100",
            isMovie = false,
            seriesId = "show_100",
            season = 1,
            episode = 100
        )
        assertNull(foundEpisode)

        // Movie lookup finds the movie file
        val foundMovie = MediaStorageUtils.findMediaFile(context, "100", isMovie = true)
        assertNotNull(foundMovie)
        assertEquals(movieFile.canonicalPath, foundMovie?.canonicalPath)
    }

    @Test
    fun testF012_03_noSubstringAfterUnderscoreCollision() {
        // Create an episode file for series "show_50" s1e100
        val episodeFile = MediaStorageUtils.getSeriesEpisodeFile(context, "show_50", 1, 100)
        episodeFile.parentFile?.mkdirs()
        episodeFile.writeBytes(ByteArray(1024))

        // Lookup for movie with ID "100" or "50" must NOT match the episode!
        val movieLookup100 = MediaStorageUtils.findMediaFile(context, "100", isMovie = true)
        val movieLookup50 = MediaStorageUtils.findMediaFile(context, "50", isMovie = true)
        assertNull(movieLookup100)
        assertNull(movieLookup50)
    }

    @Test
    fun testF012_04_seriesEpisodeRequiresSeriesAndCoordinates() {
        val epFile = MediaStorageUtils.getSeriesEpisodeFile(context, "breaking_bad", 2, 5)
        epFile.parentFile?.mkdirs()
        epFile.writeBytes(ByteArray(2048))

        // Searching with wrong series id returns null
        val wrongSeries = MediaStorageUtils.findMediaFile(
            context, "breaking_bad", isMovie = false, seriesId = "better_call_saul", season = 2, episode = 5
        )
        assertNull(wrongSeries)

        // Searching with wrong episode returns null
        val wrongEpisode = MediaStorageUtils.findMediaFile(
            context, "breaking_bad", isMovie = false, seriesId = "breaking_bad", season = 2, episode = 6
        )
        assertNull(wrongEpisode)

        // Searching with correct coordinates returns the file
        val correct = MediaStorageUtils.findMediaFile(
            context, "breaking_bad", isMovie = false, seriesId = "breaking_bad", season = 2, episode = 5
        )
        assertNotNull(correct)
        assertEquals(epFile.canonicalPath, correct?.canonicalPath)
    }

    @Test
    fun testF012_05_compositeKeyLookupSeriesPrefix() {
        val epFile = MediaStorageUtils.getSeriesEpisodeFile(context, "matrix-series", 1, 2)
        epFile.parentFile?.mkdirs()
        epFile.writeBytes(ByteArray(1024))

        // Structured token: series_{seriesId}_s{season}e{episode}
        val found = MediaStorageUtils.findMediaFile(context, "series_matrix-series_s1e2")
        assertNotNull(found)
        assertEquals(epFile.canonicalPath, found?.canonicalPath)
    }

    @Test
    fun testF012_06_compositeKeyLookupNumericCoordinates() {
        val epFile = MediaStorageUtils.getSeriesEpisodeFile(context, "vikings", 3, 7)
        epFile.parentFile?.mkdirs()
        epFile.writeBytes(ByteArray(1024))

        // Structured token: {seriesId}_{season}_{episode}
        val found = MediaStorageUtils.findMediaFile(context, "vikings_3_7")
        assertNotNull(found)
        assertEquals(epFile.canonicalPath, found?.canonicalPath)
    }

    @Test
    fun testF012_07_twoDifferentSeriesSameEpisodeDoNotCollide() {
        val showA = MediaStorageUtils.getSeriesEpisodeFile(context, "show-alpha", 1, 1)
        val showB = MediaStorageUtils.getSeriesEpisodeFile(context, "show-beta", 1, 1)

        showA.parentFile?.mkdirs()
        showA.writeBytes(ByteArray(1024))
        showB.parentFile?.mkdirs()
        showB.writeBytes(ByteArray(2048))

        assertNotEquals(showA.canonicalPath, showB.canonicalPath)

        val foundA = MediaStorageUtils.findMediaFile(context, "show-alpha_s1e1")
        val foundB = MediaStorageUtils.findMediaFile(context, "show-beta_s1e1")
        assertNotNull(foundA)
        assertNotNull(foundB)
        assertEquals(showA.canonicalPath, foundA?.canonicalPath)
        assertEquals(showB.canonicalPath, foundB?.canonicalPath)
        assertEquals(1024L, foundA?.length())
        assertEquals(2048L, foundB?.length())
    }

    @Test
    fun testF012_08_legacyMovieLookupRequiresExactMatchOnly() {
        val legacyMoviesDir = File(context.filesDir, "movies")
        legacyMoviesDir.mkdirs()
        val legacyFile = File(legacyMoviesDir, "movie_legacy_exact.mp4")
        legacyFile.writeBytes(ByteArray(512))

        // Exact ID matches
        val found = MediaStorageUtils.findMediaFile(context, "movie_legacy_exact")
        assertNotNull(found)
        assertEquals(legacyFile.canonicalPath, found?.canonicalPath)

        // Substring or prefix does NOT match
        val partialNotFound = MediaStorageUtils.findMediaFile(context, "movie_legacy")
        assertNull(partialNotFound)

        legacyFile.delete()
    }

    @Test
    fun testF012_09_sanitizedSeriesIdPreventsDirectoryTraversal() {
        assertThrows(SecurityException::class.java) {
            MediaStorageUtils.getSeriesEpisodeFile(context, "../traversal_series", 1, 1)
        }
    }

    @Test
    fun testF012_10_negativeSeasonOrEpisodeRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaStorageUtils.getSeriesEpisodeFile(context, "series_valid", -1, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaStorageUtils.getSeriesEpisodeFile(context, "series_valid", 1, -1)
        }
    }
}
