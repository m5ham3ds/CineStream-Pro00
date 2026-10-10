package com.example

import com.example.data.model.CanonicalSubscriptionTier
import com.example.data.model.CanonicalTransactionType
import com.example.data.model.DailyLoginState
import com.example.data.model.RewardedAdClaimRequest
import com.example.data.repository.CryptographicUpdateVerifier
import com.example.data.repository.UpdatePackageVerifier
import com.example.extension.managed.playback.PlaybackAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * PHASE 07.0 / UNIFIED IMPLEMENTATION WAVE 5-7 VERIFICATION ASSETS
 *
 * Prepared for WAVE 8 verification.
 * DO NOT EXECUTE IN WAVE 5-7.
 */
class Phase07Wave567VerificationTest {

    // =========================================================================
    // 5A: ECONOMY & ADVERTISING AUTHORITY VERIFICATION
    // =========================================================================

    @Test
    fun test5A_01_canonicalDailyLoginLadderIntegrity() {
        // Enforce canonical ladder [10, 15, 20, 25, 30, 40, 50]
        val dailyLoginState = DailyLoginState()
        val expected = listOf(10L, 15L, 20L, 25L, 30L, 40L, 50L)
        assertEquals(expected, dailyLoginState.rewardLadder)
    }

    @Test
    fun test5A_02_rewardedAdClaimRequestHasIdempotentIdentity() {
        val req1 = RewardedAdClaimRequest(userId = "user_1")
        val req2 = RewardedAdClaimRequest(userId = "user_1")
        assertNotNull(req1.requestId)
        assertNotNull(req2.requestId)
        // Unique request IDs prevent duplicate double-grants
        assertTrue(req1.requestId != req2.requestId)
    }

    @Test
    fun test5A_03_canonicalTransactionTypesPreserved() {
        assertEquals("DAILY_LOGIN", CanonicalTransactionType.DAILY_LOGIN)
        assertEquals("REWARDED_AD", CanonicalTransactionType.REWARDED_AD)
        assertEquals("TASK_REWARD", CanonicalTransactionType.TASK_REWARD)
        assertEquals("SUBSCRIPTION_REDEMPTION", CanonicalTransactionType.SUBSCRIPTION_REDEMPTION)
    }

    // =========================================================================
    // 5B: PERFORMANCE & UI CONCURRENCY VERIFICATION
    // =========================================================================

    @Test
    fun test5B_01_playbackAttemptCandidateIsolation() {
        val attempt = PlaybackAttempt.createNew()
        assertFalse(attempt.isCandidateCancelled("candidate_1"))
        attempt.cancelCandidate("candidate_1")
        assertTrue(attempt.isCandidateCancelled("candidate_1"))
        assertFalse(attempt.isCandidateCancelled("candidate_2"))
    }

    // =========================================================================
    // 5C: RELEASE HARDENING & COMPLIANCE VERIFICATION
    // =========================================================================

    @Test
    fun test5C_01_cryptographicUpdateVerifierRejectsMissingAuthority() {
        val tempFile = Files.createTempFile("test_apk", ".apk").toFile()
        tempFile.writeText("sample apk content")

        val result = kotlinx.coroutines.runBlocking {
            CryptographicUpdateVerifier.verifyPackage(
                apkFile = tempFile,
                expectedSha256 = null,
                signature = null,
                publicKey = null
            )
        }

        assertTrue(result is UpdatePackageVerifier.VerificationResult.BlockedMissingSignatureAuthority)
        tempFile.delete()
    }

    @Test
    fun test5C_02_cryptographicUpdateVerifierSha256Validation() {
        val tempFile = Files.createTempFile("test_apk", ".apk").toFile()
        tempFile.writeText("hello cinestream")
        val expectedSha = CryptographicUpdateVerifier.calculateSha256(tempFile)

        // F-007 Fix: Unauthenticated SHA-256 checksum without signature authority must fail-closed
        val blockedResult = kotlinx.coroutines.runBlocking {
            CryptographicUpdateVerifier.verifyPackage(
                apkFile = tempFile,
                expectedSha256 = expectedSha,
                signature = null,
                publicKey = null
            )
        }
        assertTrue(blockedResult is UpdatePackageVerifier.VerificationResult.BlockedMissingSignatureAuthority)

        // Checksum mismatch must fail integrity verification
        val failResult = kotlinx.coroutines.runBlocking {
            CryptographicUpdateVerifier.verifyPackage(
                apkFile = tempFile,
                expectedSha256 = "invalid_hash_000000000000000000000000000000000000000000000000000000000000",
                signature = null,
                publicKey = null
            )
        }
        assertTrue(failResult is UpdatePackageVerifier.VerificationResult.Failed)
        tempFile.delete()
    }

    @Test
    fun test5C_03_cryptographicUpdateVerifierAuthenticatedSignatureVerification() {
        val tempFile = Files.createTempFile("test_apk_signed", ".apk").toFile()
        tempFile.writeText("authenticated release payload")
        val expectedSha = CryptographicUpdateVerifier.calculateSha256(tempFile)

        // Generate ephemeral RSA keypair for testing signature boundary
        val kpg = java.security.KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()

        // Sign the file
        val signer = java.security.Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        tempFile.inputStream().use { stream ->
            val buf = ByteArray(8192)
            var read: Int
            while (stream.read(buf).also { read = it } > 0) {
                signer.update(buf, 0, read)
            }
        }
        val sigBytes = signer.sign()
        val sigBase64 = java.util.Base64.getEncoder().encodeToString(sigBytes)
        val pubKeyBase64 = java.util.Base64.getEncoder().encodeToString(keyPair.public.encoded)

        // Genuine signed update with matching checksum and trusted public key verifies successfully
        val verifiedResult = kotlinx.coroutines.runBlocking {
            CryptographicUpdateVerifier.verifyPackage(
                apkFile = tempFile,
                expectedSha256 = expectedSha,
                signature = sigBase64,
                publicKey = pubKeyBase64
            )
        }
        assertTrue(verifiedResult is UpdatePackageVerifier.VerificationResult.Verified)

        // Corrupted signature is rejected
        val badSigResult = kotlinx.coroutines.runBlocking {
            CryptographicUpdateVerifier.verifyPackage(
                apkFile = tempFile,
                expectedSha256 = expectedSha,
                signature = java.util.Base64.getEncoder().encodeToString("corrupted_sig".toByteArray()),
                publicKey = pubKeyBase64
            )
        }
        assertTrue(badSigResult is UpdatePackageVerifier.VerificationResult.Failed)

        tempFile.delete()
    }

    @Test
    fun test5C_04_signatureWithoutTrustedKeyFailsClosed() {
        val tempFile = Files.createTempFile("test_apk_no_key", ".apk").toFile()
        tempFile.writeText("unsigned or unanchored content")
        val expectedSha = CryptographicUpdateVerifier.calculateSha256(tempFile)

        // Ensure pinned key is null
        CryptographicUpdateVerifier.pinnedPublicKey = null

        // Even with a signature string, if no trusted authority key exists, must return BlockedMissingSignatureAuthority
        val result = kotlinx.coroutines.runBlocking {
            CryptographicUpdateVerifier.verifyPackage(
                apkFile = tempFile,
                expectedSha256 = expectedSha,
                signature = "dummy_signature_value",
                publicKey = null
            )
        }
        assertTrue(result is UpdatePackageVerifier.VerificationResult.BlockedMissingSignatureAuthority)
        tempFile.delete()
    }

    @Test
    fun test5C_05_metadataSuppliedKeyCannotEstablishTrust() {
        val tempFile = Files.createTempFile("test_apk_attacker", ".apk").toFile()
        tempFile.writeText("attacker payload")
        val attackerSha = CryptographicUpdateVerifier.calculateSha256(tempFile)

        // Attacker creates their own keypair and signs their payload
        val attackerKpg = java.security.KeyPairGenerator.getInstance("RSA")
        attackerKpg.initialize(2048)
        val attackerKeyPair = attackerKpg.generateKeyPair()

        val signer = java.security.Signature.getInstance("SHA256withRSA")
        signer.initSign(attackerKeyPair.private)
        tempFile.inputStream().use { stream ->
            val buf = ByteArray(8192)
            var read: Int
            while (stream.read(buf).also { read = it } > 0) {
                signer.update(buf, 0, read)
            }
        }
        val attackerSigBase64 = java.util.Base64.getEncoder().encodeToString(signer.sign())
        val attackerPubKeyBase64 = java.util.Base64.getEncoder().encodeToString(attackerKeyPair.public.encoded)

        // Authentic release key is pinned in the verifier
        val genuineKpg = java.security.KeyPairGenerator.getInstance("RSA")
        genuineKpg.initialize(2048)
        val genuineKeyPair = genuineKpg.generateKeyPair()
        val genuinePubKeyBase64 = java.util.Base64.getEncoder().encodeToString(genuineKeyPair.public.encoded)

        CryptographicUpdateVerifier.pinnedPublicKey = genuinePubKeyBase64

        // Update metadata passes attacker key, but verifier must NOT trust the metadata key
        val result = kotlinx.coroutines.runBlocking {
            // When verifying via AppUpdateManager or with pinned authority,
            // attacker signature verified against genuine pinned key fails
            CryptographicUpdateVerifier.verifyPackage(
                apkFile = tempFile,
                expectedSha256 = attackerSha,
                signature = attackerSigBase64,
                publicKey = attackerPubKeyBase64 // Attacker-controlled metadata public key
            )
        }
        // Result must be Failed (cryptographic signature rejected against genuine anchor)
        // NOT Verified!
        assertTrue(result is UpdatePackageVerifier.VerificationResult.Failed)

        // Reset pinned key
        CryptographicUpdateVerifier.pinnedPublicKey = null
        tempFile.delete()
    }

    @Test
    fun test5C_06_tamperedApkAfterSigningRejected() {
        val tempFile = Files.createTempFile("test_apk_tampered", ".apk").toFile()
        tempFile.writeText("original content before tampering")

        val kpg = java.security.KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()

        val signer = java.security.Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        tempFile.inputStream().use { stream ->
            val buf = ByteArray(8192)
            var read: Int
            while (stream.read(buf).also { read = it } > 0) {
                signer.update(buf, 0, read)
            }
        }
        val sigBase64 = java.util.Base64.getEncoder().encodeToString(signer.sign())
        val pubKeyBase64 = java.util.Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val originalSha = CryptographicUpdateVerifier.calculateSha256(tempFile)

        // Tamper with the APK file content after signing
        tempFile.writeText("TAMPERED modified payload")

        val result = kotlinx.coroutines.runBlocking {
            CryptographicUpdateVerifier.verifyPackage(
                apkFile = tempFile,
                expectedSha256 = originalSha,
                signature = sigBase64,
                publicKey = pubKeyBase64
            )
        }
        // Tampered file must be rejected (either SHA mismatch or signature verification failure)
        assertTrue(result is UpdatePackageVerifier.VerificationResult.Failed)
        tempFile.delete()
    }

    @Test
    fun test5C_07_malformedSignatureOrMetadataRejectedWithoutCrashing() {
        val tempFile = Files.createTempFile("test_apk_malformed", ".apk").toFile()
        tempFile.writeText("some content")

        val kpg = java.security.KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()
        val pubKeyBase64 = java.util.Base64.getEncoder().encodeToString(keyPair.public.encoded)

        // Malformed non-base64 signature string
        val malformedSigResult = kotlinx.coroutines.runBlocking {
            CryptographicUpdateVerifier.verifyPackage(
                apkFile = tempFile,
                expectedSha256 = null,
                signature = "!!!not_valid_base64_%%%@@@***",
                publicKey = pubKeyBase64
            )
        }
        assertTrue(malformedSigResult is UpdatePackageVerifier.VerificationResult.Failed)

        // Malformed public key string
        val malformedKeyResult = kotlinx.coroutines.runBlocking {
            CryptographicUpdateVerifier.verifyPackage(
                apkFile = tempFile,
                expectedSha256 = null,
                signature = java.util.Base64.getEncoder().encodeToString("sig".toByteArray()),
                publicKey = "malformed_key_string"
            )
        }
        assertTrue(malformedKeyResult is UpdatePackageVerifier.VerificationResult.Failed)

        tempFile.delete()
    }

    @Test
    fun test5C_08_appUpdateManagerRejectsMetadataSuppliedKeyAsTrustAnchor() {
        val tempFile = Files.createTempFile("test_apk_mgr", ".apk").toFile()
        tempFile.writeText("malicious payload")
        val sha = CryptographicUpdateVerifier.calculateSha256(tempFile)

        // Attacker creates their own keypair and signs
        val kpg = java.security.KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val attackerKeyPair = kpg.generateKeyPair()

        val signer = java.security.Signature.getInstance("SHA256withRSA")
        signer.initSign(attackerKeyPair.private)
        tempFile.inputStream().use { stream ->
            val buf = ByteArray(8192)
            var read: Int
            while (stream.read(buf).also { read = it } > 0) {
                signer.update(buf, 0, read)
            }
        }
        val sig = java.util.Base64.getEncoder().encodeToString(signer.sign())
        val attackerPubKey = java.util.Base64.getEncoder().encodeToString(attackerKeyPair.public.encoded)

        // Untrusted update info carrying attacker's public key in metadata
        val updateInfo = com.example.data.repository.AppUpdateInfo(
            versionCode = 999L,
            versionName = "999.0",
            downloadUrl = "https://example.com/update.apk",
            expectedSha256 = sha,
            signature = sig,
            publicKey = attackerPubKey
        )

        // When pinnedPublicKey is null (production baseline without fabricated release key)
        CryptographicUpdateVerifier.pinnedPublicKey = null

        val result = kotlinx.coroutines.runBlocking {
            com.example.data.repository.AppUpdateManager.verifyDownloadedUpdate(
                apkFile = tempFile,
                updateInfo = updateInfo
            )
        }
        // MUST NOT trust updateInfo.publicKey! Must fail-closed as BlockedMissingSignatureAuthority
        assertTrue(result is UpdatePackageVerifier.VerificationResult.BlockedMissingSignatureAuthority)

        tempFile.delete()
    }
}
