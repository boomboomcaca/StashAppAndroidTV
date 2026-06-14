package com.github.damontecres.stashapp.subtitle

import android.util.Log
import com.github.damontecres.stashapp.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Parser for WebVTT (Web Video Text Tracks) and SRT (SubRip) subtitle files
 */
object VttParser {
    private const val TAG = "VttParser"

    /** Maximum number of characters we will read from a subtitle response (~10M chars). */
    private const val MAX_SUBTITLE_CHARS = 10 * 1024 * 1024

    // A single timestamp component. Hours are optional (WebVTT allows MM:SS.mmm),
    // and SRT uses a comma before the milliseconds.
    private const val VTT_TIME = """\d{1,3}:\d{2}(?::\d{2})?\.\d{3}"""
    private const val SRT_TIME = """\d{1,3}:\d{2}(?::\d{2})?[.,]\d{3}"""

    private val VTT_TIME_LINE = Regex("""^($VTT_TIME)\s*-->\s*($VTT_TIME)""")
    private val VTT_TIME_ANY = Regex("""$VTT_TIME\s*-->\s*$VTT_TIME""")
    private val SRT_TIME_LINE = Regex("""^($SRT_TIME)\s*-->\s*($SRT_TIME)""")
    private val SRT_TIME_ANY = Regex("""$SRT_TIME\s*-->\s*$SRT_TIME""")
    private val SEQ_NUMBER = Regex("""^\d+$""")
    private val HTML_TAG = Regex("<[^>]*>")

    /**
     * Detect subtitle format (VTT or SRT) and parse content
     */
    fun parseSubtitles(content: String, url: String? = null): List<SubtitleCue> {
        // Strip a leading UTF-8 BOM (U+FEFF) which is not whitespace and would otherwise
        // defeat header/timestamp detection.
        val clean = content.removePrefix("﻿")
        val format = detectFormat(clean, url)
        return when (format) {
            SubtitleFormat.SRT -> parseSrt(clean)
            SubtitleFormat.VTT -> parseVtt(clean)
        }
    }

    /**
     * Detect subtitle format from content or URL
     */
    private fun detectFormat(content: String, url: String?): SubtitleFormat {
        // Check URL extension first
        url?.let {
            if (it.endsWith(".srt", ignoreCase = true)) {
                return SubtitleFormat.SRT
            }
            if (it.endsWith(".vtt", ignoreCase = true)) {
                return SubtitleFormat.VTT
            }
        }

        // Check content header
        val firstLine = content.trim().split("\n").firstOrNull()?.trim() ?: ""
        if (firstLine == "WEBVTT" || firstLine.startsWith("WEBVTT")) {
            return SubtitleFormat.VTT
        }

        // Default to SRT if starts with number (SRT format)
        if (firstLine.matches(SEQ_NUMBER)) {
            return SubtitleFormat.SRT
        }

        // Check for SRT timestamp format (comma before milliseconds)
        if (content.contains(Regex("""\d{1,3}:\d{2}(?::\d{2})?,\d{3}\s*-->\s*\d{1,3}:\d{2}(?::\d{2})?,\d{3}"""))) {
            return SubtitleFormat.SRT
        }

        // Check for VTT timestamp format (dot before milliseconds)
        if (content.contains(VTT_TIME_ANY)) {
            return SubtitleFormat.VTT
        }

        // Default to VTT (server always provides VTT format)
        return SubtitleFormat.VTT
    }

    /**
     * Parse VTT content string into list of cues
     */
    fun parseVtt(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val lines = content.split("\n")

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()

            // Skip WEBVTT header and empty lines
            if (line == "WEBVTT" || line.isEmpty() || line.startsWith("NOTE")) {
                i++
                continue
            }

            // Look for timestamp line
            val timeMatch = VTT_TIME_LINE.find(line)
            if (timeMatch != null) {
                val startTime = parseVttTime(timeMatch.groupValues[1])
                val endTime = parseVttTime(timeMatch.groupValues[2])

                i++ // Move to text lines
                val textBuilder = StringBuilder()

                // Collect text lines until empty line or next timestamp
                while (i < lines.size) {
                    val nextLine = lines[i].trim()
                    if (nextLine.isEmpty() || VTT_TIME_LINE.containsMatchIn(nextLine)) {
                        break
                    }
                    if (textBuilder.isNotEmpty()) {
                        textBuilder.append("\n")
                    }
                    // Remove HTML tags
                    textBuilder.append(nextLine.replace(HTML_TAG, ""))
                    i++
                }

                val text = textBuilder.toString().trim()
                // Skip cues whose timestamps could not be parsed (defensive)
                if (text.isNotEmpty() && startTime != null && endTime != null) {
                    cues.add(SubtitleCue(startTime, endTime, text))
                }
            } else {
                i++
            }
        }

        return cues.sortedBy { it.startTime }
    }

    /**
     * Parse VTT/SRT timestamp string to seconds.
     * Supports HH:MM:SS.mmm and the no-hours MM:SS.mmm form. Returns null if it
     * cannot be parsed so the caller can skip the offending cue rather than aborting
     * the whole file.
     */
    private fun parseVttTime(rawTimeStr: String): Double? {
        val timeStr = rawTimeStr.replace(',', '.')
        val parts = timeStr.split(":")
        return try {
            when (parts.size) {
                3 -> {
                    val hours = parts[0].toInt()
                    val minutes = parts[1].toInt()
                    val secParts = parts[2].split(".")
                    val seconds = secParts[0].toInt()
                    val millis = secParts.getOrNull(1)?.toInt() ?: 0
                    hours * 3600.0 + minutes * 60.0 + seconds + millis / 1000.0
                }
                2 -> {
                    val minutes = parts[0].toInt()
                    val secParts = parts[1].split(".")
                    val seconds = secParts[0].toInt()
                    val millis = secParts.getOrNull(1)?.toInt() ?: 0
                    minutes * 60.0 + seconds + millis / 1000.0
                }
                else -> null
            }
        } catch (e: NumberFormatException) {
            Log.w(TAG, "Failed to parse timestamp: $rawTimeStr", e)
            null
        }
    }

    /**
     * Parse SRT content string into list of cues
     */
    fun parseSrt(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val lines = content.split("\n")

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()

            // Skip empty lines
            if (line.isEmpty()) {
                i++
                continue
            }

            // Check if line is a sequence number (just digits)
            if (line.matches(SEQ_NUMBER)) {
                i++ // Skip sequence number
                if (i >= lines.size) break
            }

            // Look for timestamp line
            val timeMatch = SRT_TIME_LINE.find(lines[i].trim())
            if (timeMatch != null) {
                val startTime = parseVttTime(timeMatch.groupValues[1])
                val endTime = parseVttTime(timeMatch.groupValues[2])

                i++ // Move to text lines
                val textBuilder = StringBuilder()

                // Collect text lines until empty line or next subtitle block
                while (i < lines.size) {
                    val nextLine = lines[i].trim()
                    if (nextLine.isEmpty()) {
                        // Check if next non-empty line is a timestamp (next subtitle)
                        var nextNonEmpty = i + 1
                        while (nextNonEmpty < lines.size && lines[nextNonEmpty].trim().isEmpty()) {
                            nextNonEmpty++
                        }
                        if (nextNonEmpty < lines.size) {
                            val potentialTimestamp = lines[nextNonEmpty].trim()
                            if (SRT_TIME_LINE.containsMatchIn(potentialTimestamp)) {
                                break
                            }
                        }
                        // Single empty line, continue (might be part of text)
                        if (textBuilder.isNotEmpty()) {
                            textBuilder.append("\n")
                        }
                        i++
                        continue
                    }

                    // Check if this line is a timestamp (next subtitle started)
                    if (SRT_TIME_LINE.containsMatchIn(nextLine)) {
                        break
                    }

                    // Check if this line is just a sequence number
                    if (nextLine.matches(SEQ_NUMBER)) {
                        // Might be sequence number of next subtitle, but also might be text
                        if (i + 1 < lines.size) {
                            val nextNextLine = lines[i + 1].trim()
                            if (SRT_TIME_LINE.containsMatchIn(nextNextLine)) {
                                break // It's a sequence number, stop here
                            }
                        }
                    }

                    if (textBuilder.isNotEmpty()) {
                        textBuilder.append("\n")
                    }
                    // Remove HTML tags and styling (SRT may contain some HTML)
                    textBuilder.append(nextLine.replace(HTML_TAG, ""))
                    i++
                }

                val text = textBuilder.toString().trim()
                if (text.isNotEmpty() && startTime != null && endTime != null) {
                    cues.add(SubtitleCue(startTime, endTime, text))
                }
            } else {
                i++
            }
        }

        return cues.sortedBy { it.startTime }
    }

    /**
     * Load and parse subtitle file from URL (supports VTT and SRT).
     *
     * The Stash API key is only attached when [url] resolves to the same host as
     * [serverUrl]; this prevents leaking the key to an arbitrary host if a caption
     * URL points elsewhere. Only http/https URLs are accepted.
     */
    suspend fun loadVttFromUrl(
        url: String,
        apiKey: String? = null,
        serverUrl: String? = null,
    ): Result<List<SubtitleCue>> {
        return withContext(Dispatchers.IO) {
            try {
                val parsedUrl = try {
                    URL(url)
                } catch (e: Exception) {
                    return@withContext Result.failure(Exception("无效的字幕地址: $url"))
                }
                val scheme = parsedUrl.protocol?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    return@withContext Result.failure(Exception("不支持的字幕协议: $scheme"))
                }

                val connection = parsedUrl.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 10000

                // Only send the API key to the configured Stash host.
                if (!apiKey.isNullOrBlank() && hostMatches(parsedUrl, serverUrl)) {
                    connection.setRequestProperty(Constants.STASH_API_HEADER, apiKey.trim())
                }

                try {
                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        // Reject oversized responses up-front when the length is known.
                        val declaredLength = connection.contentLengthLong
                        if (declaredLength > MAX_SUBTITLE_CHARS) {
                            return@withContext Result.failure(Exception("字幕文件过大"))
                        }

                        val content = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            readBounded(reader)
                        }

                        // Check if content is HTML (login page) - indicates authentication failure
                        if (content.trimStart().startsWith("<!DOCTYPE", ignoreCase = true) ||
                            content.trimStart().startsWith("<html", ignoreCase = true)
                        ) {
                            return@withContext Result.failure(Exception("需要认证：收到 HTML 登录页面而非字幕内容"))
                        }

                        val cues = parseSubtitles(content, url)
                        Result.success(cues)
                    } else {
                        Result.failure(Exception("HTTP ${connection.responseCode}: ${connection.responseMessage}"))
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load subtitle from URL: $url", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Load and parse subtitle file from InputStream (supports VTT and SRT)
     */
    suspend fun loadVttFromStream(inputStream: InputStream, url: String? = null): Result<List<SubtitleCue>> {
        return try {
            val content = inputStream.bufferedReader(Charsets.UTF_8).use { readBounded(it) }
            Result.success(parseSubtitles(content, url))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load subtitle from stream", e)
            Result.failure(e)
        }
    }

    /** Read a reader fully but abort if it exceeds [MAX_SUBTITLE_CHARS]. */
    private fun readBounded(reader: java.io.Reader): String {
        val sb = StringBuilder()
        val buf = CharArray(8192)
        var total = 0
        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_SUBTITLE_CHARS) {
                throw IOException("字幕文件过大")
            }
            sb.append(buf, 0, n)
        }
        return sb.toString()
    }

    private fun hostMatches(target: URL, serverUrl: String?): Boolean {
        if (serverUrl.isNullOrBlank()) return false
        return try {
            val normalized = if (serverUrl.startsWith("http://", true) || serverUrl.startsWith("https://", true)) {
                serverUrl
            } else {
                "http://$serverUrl"
            }
            val serverHost = URL(normalized).host
            target.host.equals(serverHost, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Subtitle format enumeration
     */
    private enum class SubtitleFormat {
        VTT, SRT
    }

    /**
     * Get current cue based on playback time.
     * Cues are sorted by start time at parse time, so this uses a binary search.
     */
    fun getCurrentCue(cues: List<SubtitleCue>, currentTime: Double): SubtitleCue? {
        val index = getCurrentCueIndex(cues, currentTime)
        return if (index >= 0) cues[index] else null
    }

    /**
     * Get the index of the cue active at [currentTime], or -1 if none.
     * Assumes [cues] is sorted by start time (binary search).
     */
    fun getCurrentCueIndex(cues: List<SubtitleCue>, currentTime: Double): Int {
        if (cues.isEmpty()) return -1

        var lo = 0
        var hi = cues.size - 1
        var candidate = -1
        // Find the last cue whose startTime <= currentTime.
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startTime <= currentTime) {
                candidate = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }

        if (candidate >= 0 && currentTime <= cues[candidate].endTime) {
            return candidate
        }
        // Handle (rare) overlapping cues: the immediately preceding cue may still be active.
        if (candidate - 1 >= 0 &&
            currentTime >= cues[candidate - 1].startTime &&
            currentTime <= cues[candidate - 1].endTime
        ) {
            return candidate - 1
        }
        return -1
    }
}
