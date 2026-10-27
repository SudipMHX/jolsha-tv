package com.jolsha.tv.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [StreamSanitizer] verifying stream URL sanitization,
 * credential masking, and exception cause chain formatting.
 */
class StreamSanitizerTest {

    @Test
    fun testEmptyAndBlankUrls() {
        assertEquals("<empty>", StreamSanitizer.sanitizeUrl(null))
        assertEquals("<empty>", StreamSanitizer.sanitizeUrl(""))
        assertEquals("<empty>", StreamSanitizer.sanitizeUrl("   "))
    }

    @Test
    fun testXtreamCodesStandardLiveUrl() {
        val rawUrl = "http://10.189.188.190:8080/7654321/1234567/156"
        val sanitized = StreamSanitizer.sanitizeUrl(rawUrl)

        assertFalse("Raw username must not appear in sanitized URL", sanitized.contains("7654321"))
        assertFalse("Raw password must not appear in sanitized URL", sanitized.contains("1234567"))
        assertTrue("Host and port must be preserved", sanitized.contains("10.189.188.190:8080"))
        assertTrue("Stream ID must be preserved", sanitized.endsWith("/156"))
    }

    @Test
    fun testXtreamCodesLiveM3u8Url() {
        val rawUrl = "http://iptv.example.com:8080/live/myuser/mypassword/9876.m3u8"
        val sanitized = StreamSanitizer.sanitizeUrl(rawUrl)

        assertFalse("Username must be masked", sanitized.contains("myuser"))
        assertFalse("Password must be masked", sanitized.contains("mypassword"))
        assertTrue("Live prefix must be preserved", sanitized.contains("/live/"))
        assertTrue("Stream ID and extension must be preserved", sanitized.endsWith("9876.m3u8"))
    }

    @Test
    fun testUrlWithSensitiveQueryParams() {
        val rawUrl = "http://stream.example.com/live/ch1.m3u8?token=SECRET_TOKEN_XYZ&auth=AUTH_123&quality=hd"
        val sanitized = StreamSanitizer.sanitizeUrl(rawUrl)

        assertFalse("Token must not be visible", sanitized.contains("SECRET_TOKEN_XYZ"))
        assertFalse("Auth must not be visible", sanitized.contains("AUTH_123"))
        assertTrue("Token parameter name must be masked", sanitized.contains("token=***"))
        assertTrue("Auth parameter name must be masked", sanitized.contains("auth=***"))
        assertTrue("Safe parameter must be preserved", sanitized.contains("quality=hd"))
    }

    @Test
    fun testUrlWithBasicAuthUserInfo() {
        val rawUrl = "http://admin:secret123@iptv.server.net:8000/stream.ts"
        val sanitized = StreamSanitizer.sanitizeUrl(rawUrl)

        assertFalse("Password in user info must not be visible", sanitized.contains("secret123"))
    }

    @Test
    fun testExceptionCauseChainFormatting() {
        val root = IllegalArgumentException("Root cause error")
        val middle = RuntimeException("Middle wrapper", root)
        val top = IllegalStateException("Top level failure", middle)

        val formatted = StreamSanitizer.formatExceptionCauseChain(top)

        assertTrue(formatted.contains("IllegalStateException: Top level failure"))
        assertTrue(formatted.contains("RuntimeException: Middle wrapper"))
        assertTrue(formatted.contains("IllegalArgumentException: Root cause error"))
        assertTrue(formatted.contains("-> Caused by:"))
    }

    @Test
    fun testExceptionCauseChainWithNull() {
        assertEquals("None", StreamSanitizer.formatExceptionCauseChain(null))
    }
}
