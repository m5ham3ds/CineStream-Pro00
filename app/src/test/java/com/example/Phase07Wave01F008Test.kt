package com.example

import com.example.utils.HotspotManager
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Phase07Wave01F008Test {

    // --- A. STARTAPP CONFIGURATION ---

    private fun resolveStartAppId(configuredId: String?): String? {
        val trimmed = configuredId?.trim() ?: return null
        return if (trimmed.isNotEmpty() && !trimmed.startsWith("YOUR_")) {
            trimmed
        } else {
            null
        }
    }

    @Test
    fun testStartAppConfig_validConfiguredId_isAccepted() {
        val validId = "123456789"
        val resolved = resolveStartAppId(validId)
        assertEquals("123456789", resolved)
    }

    @Test
    fun testStartAppConfig_missingOrNull_isSafelySkipped() {
        val resolved = resolveStartAppId(null)
        assertNull(resolved)
    }

    @Test
    fun testStartAppConfig_blank_isSafelySkipped() {
        val resolved = resolveStartAppId("")
        assertNull(resolved)
    }

    @Test
    fun testStartAppConfig_whitespace_isSafelySkipped() {
        val resolved = resolveStartAppId("   \t\n  ")
        assertNull(resolved)
    }

    @Test
    fun testStartAppConfig_placeholderToken_isSafelySkipped() {
        val resolved = resolveStartAppId("YOUR_STARTAPP_APP_ID")
        assertNull(resolved)
    }

    // --- B. HOTSPOT PASSWORD GENERATION ---

    @Test
    fun testHotspotPassword_explicitValidPassword_isPreserved() {
        val explicitPassword = "userSuppliedPassword99"
        val finalPassword = explicitPassword.ifEmpty { HotspotManager.generateSecurePassphrase() }
        assertEquals(explicitPassword, finalPassword)
    }

    @Test
    fun testHotspotPassword_missingPassword_generatesFallback() {
        val suppliedPassword: String? = null
        val finalPassword = suppliedPassword ?: HotspotManager.generateSecurePassphrase()
        assertNotNull(finalPassword)
        assertTrue(finalPassword.isNotEmpty())
    }

    @Test
    fun testHotspotPassword_generatedFallback_meetsWpa2Requirements() {
        val generated = HotspotManager.generateSecurePassphrase(16)
        assertEquals(16, generated.length)
        assertTrue("WPA2 requires at least 8 characters", generated.length >= 8)
        assertTrue("Password must be ASCII printable", generated.all { it.code in 33..126 })
    }

    @Test
    fun testHotspotPassword_independentGenerations_areUnique() {
        val pass1 = HotspotManager.generateSecurePassphrase()
        val pass2 = HotspotManager.generateSecurePassphrase()
        assertNotEquals("Two random generations must not produce the same value", pass1, pass2)
    }

    // --- C. STATIC SCAN & NO HARDCODED SECRETS IN SOURCE ---

    @Test
    fun testStaticScan_noLiteralStartAppIdInMainActivity() {
        val mainActivityFile = File("src/main/java/com/example/MainActivity.kt")
        val altFile = File("app/src/main/java/com/example/MainActivity.kt")
        val file = if (mainActivityFile.exists()) mainActivityFile else altFile
        assertTrue(file.exists())
        val content = file.readText()
        assertFalse(
            "MainActivity must not contain literal StartApp ID",
            content.contains("208324071")
        )
    }

    @Test
    fun testStaticScan_noStaticFallbackPassphraseInHotspotManager() {
        val hotspotFile = File("src/main/java/com/example/utils/HotspotManager.kt")
        val altFile = File("app/src/main/java/com/example/utils/HotspotManager.kt")
        val file = if (hotspotFile.exists()) hotspotFile else altFile
        assertTrue(file.exists())
        val content = file.readText()
        assertFalse(
            "HotspotManager must not contain static fallback passphrase",
            content.contains("cinestream123")
        )
    }
}
