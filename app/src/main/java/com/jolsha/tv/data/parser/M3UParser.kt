package com.jolsha.tv.data.parser

import com.jolsha.tv.data.model.Channel
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Robust M3U and M3U8 playlist parser for IPTV streams.
 *
 * Features:
 * - Parses EXTINF lines with tvg-id, tvg-name, tvg-logo, group-title.
 * - Extracts attributes regardless of order, whitespace differences, and quoted/unquoted values.
 * - Extracts channel name from the text after the last comma, falling back to tvg-name.
 * - Cleanly handles unicode channel names (Bengali, Hindi, Arabic, etc.), comments, and blank lines.
 * - Preserves duplicate channel names and identical URLs without deduplication, assigning unique composite IDs.
 * - Assigns 1-based sequential channelNumber and 0-based originalIndex.
 */
class M3UParser {

    companion object {
        // Matches key="value", key='value', or key=unquotedValue
        private val ATTRIBUTE_REGEX = Regex("""([a-zA-Z0-9_\-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,]+))""")

        private val VALID_SCHEMES = setOf(
            "http", "https", "rtmp", "rtmps", "rtsp", "rtsps", "udp", "rtp", "mms", "mmsh", "content", "file", "hls"
        )

        fun parse(content: String): List<Channel> = M3UParser().parse(content)

        fun parse(reader: Reader): List<Channel> = M3UParser().parse(reader)

        fun parse(inputStream: InputStream, charset: Charset = StandardCharsets.UTF_8): List<Channel> =
            M3UParser().parse(inputStream, charset)
    }

    /**
     * Parses an M3U/M3U8 string into a list of [Channel] items.
     */
    fun parse(content: String): List<Channel> {
        return parse(StringReader(content))
    }

    /**
     * Parses an M3U/M3U8 stream with specified charset (defaults to UTF-8).
     */
    fun parse(inputStream: InputStream, charset: Charset = StandardCharsets.UTF_8): List<Channel> {
        return parse(BufferedReader(InputStreamReader(inputStream, charset)))
    }

    /**
     * Parses M3U/M3U8 content from a [Reader].
     */
    fun parse(reader: Reader): List<Channel> {
        val bufferedReader = if (reader is BufferedReader) reader else BufferedReader(reader)
        val channels = mutableListOf<Channel>()
        var index = 0

        var pendingMetadata: ExtInfMetadata? = null

        bufferedReader.forEachLine { rawLine ->
            var line = rawLine.trim()
            // Strip BOM if present at the start of any line
            if (line.startsWith("\uFEFF")) {
                line = line.substring(1).trim()
            }

            if (line.isEmpty()) {
                // Skip empty lines
                return@forEachLine
            }

            val isExtInf = line.startsWith("#EXTINF", ignoreCase = true) &&
                (line.length == 7 || line[7] == ':' || line[7].isWhitespace())

            if (isExtInf) {
                // If a new EXTINF is encountered before the previous one got a URL,
                // the previous entry was malformed, so we overwrite it.
                pendingMetadata = parseExtInf(line)
            } else if (line.startsWith("#EXTGRP:", ignoreCase = true)) {
                // Optional #EXTGRP: tag for group title fallback
                val grp = line.substringAfter(":").trim()
                if (grp.isNotEmpty()) {
                    val current = pendingMetadata
                    if (current != null && current.groupTitle.isBlank()) {
                        pendingMetadata = current.copy(groupTitle = grp)
                    }
                }
            } else if (line.startsWith("#")) {
                // Ignore other metadata or comment lines (#EXTM3U, #EXTVLCOPT, etc.)
                // Pending metadata is preserved for when the stream URL appears.
            } else {
                // Non-comment line -> Stream URL candidate
                val rawUrl = line.trim()
                val url = if (rawUrl.contains(' ')) rawUrl.replace(" ", "%20") else rawUrl
                if (isValidStreamUrl(url)) {
                    val metadata = pendingMetadata
                    val tvgId = metadata?.tvgId ?: ""
                    val tvgName = metadata?.tvgName ?: ""
                    val logoUrl = metadata?.logoUrl ?: ""
                    val rawGroup = metadata?.groupTitle?.trim() ?: ""
                    val category = if (rawGroup.isNotEmpty()) rawGroup else "OTHER"

                    val parsedTitle = metadata?.title ?: ""
                    val name = parsedTitle.ifBlank {
                        tvgName.ifBlank {
                            "Channel ${index + 1}"
                        }
                    }

                    // Composite ID guarantees uniqueness even for duplicate names/URLs
                    val id = "ch_${index}_${url.hashCode()}"
                    val channelNumber = index + 1
                    val originalIndex = index

                    channels.add(
                        Channel(
                            id = id,
                            name = name,
                            streamUrl = url,
                            category = category,
                            tvgId = tvgId,
                            tvgName = tvgName,
                            logoUrl = logoUrl,
                            channelNumber = channelNumber,
                            originalIndex = originalIndex
                        )
                    )
                    index++
                }
                // Reset pending metadata after consuming the URL line (or skipping if invalid)
                pendingMetadata = null
            }
        }

        return channels
    }

    private data class ExtInfMetadata(
        val tvgId: String = "",
        val tvgName: String = "",
        val logoUrl: String = "",
        val groupTitle: String = "",
        val title: String = ""
    )

    private fun parseExtInf(line: String): ExtInfMetadata {
        val commaIndex = line.lastIndexOf(',')
        val attributesPart = if (commaIndex != -1) line.substring(0, commaIndex) else line
        var titlePart = if (commaIndex != -1) line.substring(commaIndex + 1).trim() else ""

        var tvgId = ""
        var tvgName = ""
        var logoUrl = ""
        var groupTitle = ""

        ATTRIBUTE_REGEX.findAll(attributesPart).forEach { match ->
            val key = match.groupValues[1].lowercase()
            val rawValue = match.groups[2]?.value
                ?: match.groups[3]?.value
                ?: match.groups[4]?.value
                ?: ""
            val value = rawValue.trim('"', '\'').trim()

            when (key) {
                "tvg-id" -> tvgId = value
                "tvg-name" -> tvgName = value
                "tvg-logo" -> logoUrl = value
                "group-title", "tvg-group" -> groupTitle = value
            }
        }

        if (titlePart.isBlank()) {
            val afterTag = line.replaceFirst(
                Regex("""^#EXTINF\s*:?\s*[-+]?[0-9]*\.?[0-9]*\s*""", RegexOption.IGNORE_CASE),
                ""
            )
            if (!afterTag.contains("=")) {
                titlePart = afterTag.trim('"', '\'').trim()
            }
        }

        return ExtInfMetadata(
            tvgId = tvgId,
            tvgName = tvgName,
            logoUrl = logoUrl,
            groupTitle = groupTitle,
            title = titlePart
        )
    }

    private fun isValidStreamUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return false
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        return scheme in VALID_SCHEMES && trimmed.length > schemeEnd + 3
    }
}
