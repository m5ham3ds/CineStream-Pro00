package com.example.extension.managed.runtime.web

import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 05Q Forensic Tests for CloudflareChallengeDetector.
 *
 * Verifies precise classification between:
 * 1. CLOUDFLARE_CHALLENGE (Turnstile, Managed, Interactive, JS challenge)
 * 2. HTTP_403 (Standard non-Cloudflare forbidden)
 * 3. HTTP_404 (Not Found)
 * 4. DNS_FAILURE
 * 5. SITE_UNAVAILABLE
 * 6. PARSER_FAILURE
 * 7. EMBED_FAILURE
 * 8. NO_CHALLENGE (Clean page)
 */
class CloudflareChallengeDetectorTest {

    @Test
    fun test01_detectsCloudflareTurnstileChallenge() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><title>Just a moment...</title></head>
            <body>
                <div id="turnstile-wrapper">
                    <iframe src="https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/b/turnstile/if/ov2/av0/rcv0/0/asdf"></iframe>
                </div>
            </body>
            </html>
        """.trimIndent()

        val result = CloudflareChallengeDetector.detect(
            html = html,
            statusCode = 403,
            headers = mapOf("server" to "cloudflare", "cf-ray" to "1234567890abcdef-DUS"),
            pageTitle = "Just a moment..."
        )

        assertTrue("Must be classified as Cloudflare challenge", result.isChallenge)
        assertEquals(CloudflareChallengeDetector.FailureCategory.CLOUDFLARE_CHALLENGE, result.failureCategory)
        assertEquals(CloudflareChallengeDetector.ChallengeType.TURNSTILE, result.challengeType)
    }

    @Test
    fun test02_detectsInteractiveTurnstileChallenge() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><title>Just a moment...</title></head>
            <body>
                <form id="challenge-form">
                    <div id="challenge-stage">
                        <input type="checkbox" id="cf-turnstile-response" />
                        <label>Verify you are human</label>
                    </div>
                </form>
            </body>
            </html>
        """.trimIndent()

        val result = CloudflareChallengeDetector.detect(
            html = html,
            statusCode = 403,
            headers = mapOf("server" to "cloudflare"),
            pageTitle = "Just a moment..."
        )

        assertTrue(result.isChallenge)
        assertTrue("Interactive challenge must require interaction", result.requiresInteraction)
        assertEquals(CloudflareChallengeDetector.FailureCategory.CLOUDFLARE_CHALLENGE, result.failureCategory)
    }

    @Test
    fun test03_standardHttp403_isNotCloudflareChallenge() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><title>403 Forbidden</title></head>
            <body>
                <h1>403 Forbidden</h1>
                <p>You don't have permission to access this resource on this server.</p>
                <hr><address>Apache/2.4.41 (Ubuntu) Server at example.com Port 443</address>
            </body>
            </html>
        """.trimIndent()

        val result = CloudflareChallengeDetector.detect(
            html = html,
            statusCode = 403,
            headers = mapOf("server" to "Apache/2.4.41 (Ubuntu)"),
            pageTitle = "403 Forbidden"
        )

        assertFalse("Standard 403 must NOT be classified as Cloudflare challenge", result.isChallenge)
        assertEquals(CloudflareChallengeDetector.FailureCategory.HTTP_403, result.failureCategory)
        assertEquals(CloudflareChallengeDetector.ChallengeType.NONE, result.challengeType)
    }

    @Test
    fun test04_http404_isNotCloudflareChallenge() {
        val result = CloudflareChallengeDetector.detect(
            html = "<html><body>404 Not Found</body></html>",
            statusCode = 404,
            pageTitle = "Not Found"
        )

        assertFalse(result.isChallenge)
        assertEquals(CloudflareChallengeDetector.FailureCategory.HTTP_404, result.failureCategory)
    }

    @Test
    fun test05_dnsFailure_isIdentifiedCorrectly() {
        val result = CloudflareChallengeDetector.detect(
            exceptionMessage = "java.net.UnknownHostException: Unable to resolve host 'w1.anime4up.rest': No address associated with hostname"
        )

        assertFalse(result.isChallenge)
        assertEquals(CloudflareChallengeDetector.FailureCategory.DNS_FAILURE, result.failureCategory)
    }

    @Test
    fun test06_siteUnavailable_isIdentifiedCorrectly() {
        val result = CloudflareChallengeDetector.detect(
            statusCode = 502,
            exceptionMessage = "java.net.ConnectException: Failed to connect to witanime.pics/104.21.5.12:443"
        )

        assertFalse(result.isChallenge)
        assertEquals(CloudflareChallengeDetector.FailureCategory.SITE_UNAVAILABLE, result.failureCategory)
    }

    @Test
    fun test07_parserFailure_isIdentifiedCorrectly() {
        val result = CloudflareChallengeDetector.detect(
            statusCode = 200,
            exceptionMessage = "Selector element not found: div.episodes-list a"
        )

        assertFalse(result.isChallenge)
        assertEquals(CloudflareChallengeDetector.FailureCategory.PARSER_FAILURE, result.failureCategory)
    }

    @Test
    fun test08_embedFailure_isIdentifiedCorrectly() {
        val result = CloudflareChallengeDetector.detect(
            statusCode = 200,
            exceptionMessage = "Video player error in embed iframe: playerjs error loading media"
        )

        assertFalse(result.isChallenge)
        assertEquals(CloudflareChallengeDetector.FailureCategory.EMBED_FAILURE, result.failureCategory)
    }

    @Test
    fun test09_cleanHtml_isNoChallenge() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><title>مشاهدة الحلقة 1 - EgyDead</title></head>
            <body>
                <ul class="serversList">
                    <li data-link="https://direct.stream.org/stream.m3u8">Server 1</li>
                </ul>
            </body>
            </html>
        """.trimIndent()

        val result = CloudflareChallengeDetector.detect(
            html = html,
            statusCode = 200,
            pageTitle = "مشاهدة الحلقة 1 - EgyDead"
        )

        assertFalse(result.isChallenge)
        assertEquals(CloudflareChallengeDetector.FailureCategory.NONE, result.failureCategory)
    }
}
