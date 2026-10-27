package com.jolsha.tv.playback

import java.net.URI

/**
 * Utility to sanitize IPTV stream URLs and metadata for safe diagnostics and logging.
 *
 * Ensures that sensitive user credentials, Xtream Codes path tokens,
 * passwords, and query session keys are never logged in plain text.
 * Implemented using standard Java/Kotlin primitives to ensure full compatibility
 * with both Android runtime and JVM unit tests.
 */
object StreamSanitizer {

    private val SENSITIVE_PARAM_NAMES = setOf(
        "token", "auth", "pass", "password", "key", "user", "username", "secret", "sig", "signature", "session"
    )

    /**
     * Sanitizes a stream URL by masking user credentials, Xtream Codes path credentials,
     * and sensitive query parameters, while preserving protocol, host, port, streamId, and extension.
     */
    fun sanitizeUrl(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) return "<empty>"
        val trimmed = rawUrl.trim()
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme ?: "http"
            val host = uri.host ?: "unknown_host"
            val port = if (uri.port != -1) ":${uri.port}" else ""

            val path = uri.path ?: ""
            val segments = path.split('/').filter { it.isNotEmpty() }
            val sanitizedPath = if (segments.isEmpty()) {
                path
            } else {
                buildSanitizedPath(segments)
            }

            val rawQuery = uri.query
            val sanitizedQuery = sanitizeQuery(rawQuery)

            val base = "$scheme://$host$port$sanitizedPath"
            if (sanitizedQuery.isNotBlank()) "$base?$sanitizedQuery" else base
        } catch (_: Throwable) {
            // Fallback for URLs with unencoded special characters
            fallbackSanitize(trimmed)
        }
    }

    private fun buildSanitizedPath(segments: List<String>): String {
        return when {
            // Standard Xtream Codes live URL: /username/password/streamId
            segments.size == 3 -> {
                "/***/*****/${segments[2]}"
            }
            // Xtream Codes /live/username/password/streamId.m3u8 (or .ts)
            segments.size >= 4 && segments[0].equals("live", ignoreCase = true) -> {
                val rest = segments.drop(3).joinToString("/")
                "/live/***/*****/$rest"
            }
            // Xtream Codes /movie/username/password/streamId.mp4
            segments.size >= 4 && segments[0].equals("movie", ignoreCase = true) -> {
                val rest = segments.drop(3).joinToString("/")
                "/movie/***/*****/$rest"
            }
            // Xtream Codes /series/username/password/streamId.mp4
            segments.size >= 4 && segments[0].equals("series", ignoreCase = true) -> {
                val rest = segments.drop(3).joinToString("/")
                "/series/***/*****/$rest"
            }
            else -> {
                "/" + segments.joinToString("/")
            }
        }
    }

    private fun sanitizeQuery(query: String?): String {
        if (query.isNullOrBlank()) return ""
        return query.split('&').joinToString("&") { pair ->
            val idx = pair.indexOf('=')
            if (idx != -1) {
                val key = pair.substring(0, idx)
                if (SENSITIVE_PARAM_NAMES.contains(key.lowercase())) {
                    "$key=***"
                } else {
                    pair
                }
            } else {
                pair
            }
        }
    }

    private fun fallbackSanitize(url: String): String {
        var result = url.replace(Regex("://([^:]+):([^@]+)@"), "://***:***@")
        result = result.replace(Regex("(token|auth|pass|password|key|user|username|secret|sig)=[^&]+", RegexOption.IGNORE_CASE), "$1=***")
        return result
    }

    /**
     * Builds a human-readable string containing the full cause chain of an exception.
     */
    fun formatExceptionCauseChain(throwable: Throwable?): String {
        if (throwable == null) return "None"
        val sb = StringBuilder()
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < 10) {
            if (depth > 0) sb.append(" -> Caused by: ")
            sb.append(current.javaClass.name)
            if (!current.message.isNullOrBlank()) {
                sb.append(": ").append(current.message)
            }
            current = current.cause
            depth++
        }
        return sb.toString()
    }
}
