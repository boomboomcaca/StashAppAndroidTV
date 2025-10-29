package com.github.damontecres.stashapp.subtitle

import android.util.Log
import com.github.damontecres.stashapp.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Parser for WebVTT (Web Video Text Tracks) and SRT (SubRip) subtitle files
 */
object VttParser {
    private const val TAG = "VttParser"

    /**
     * Detect subtitle format (VTT or SRT) and parse content
     */
    fun parseSubtitles(content: String, url: String? = null): List<SubtitleCue> {
        // Try to detect format from URL or content
        val format = detectFormat(content, url)
        return when (format) {
            SubtitleFormat.SRT -> parseSrt(content)
            SubtitleFormat.VTT -> parseVtt(content)
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
        if (firstLine.matches(Regex("""^\d+$"""))) {
            return SubtitleFormat.SRT
        }
        
        // Check for SRT timestamp format (00:00:00,000 --> 00:00:00,000)
        if (content.contains(Regex("""\d{2}:\d{2}:\d{2},\d{3}\s*-->\s*\d{2}:\d{2}:\d{2},\d{3}"""))) {
            return SubtitleFormat.SRT
        }
        
        // Check for VTT timestamp format (00:00:00.000 --> 00:00:00.000)
        if (content.contains(Regex("""\d{2}:\d{2}:\d{2}\.\d{3}\s*-->\s*\d{2}:\d{2}:\d{2}\.\d{3}"""))) {
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
            
            // Look for timestamp line (format: 00:00:00.000 --> 00:00:00.000)
            val timeMatch = Regex("""^(\d{2}:\d{2}:\d{2}\.\d{3})\s*-->\s*(\d{2}:\d{2}:\d{2}\.\d{3})""").find(line)
            if (timeMatch != null) {
                val startTimeStr = timeMatch.groupValues[1]
                val endTimeStr = timeMatch.groupValues[2]
                
                val startTime = parseVttTime(startTimeStr)
                val endTime = parseVttTime(endTimeStr)
                
                i++ // Move to text lines
                val textBuilder = StringBuilder()
                
                // Collect text lines until empty line or next timestamp
                while (i < lines.size) {
                    val nextLine = lines[i].trim()
                    if (nextLine.isEmpty() || nextLine.matches(Regex("""^\d{2}:\d{2}:\d{2}\.\d{3}\s*-->\s*\d{2}:\d{2}:\d{2}\.\d{3}"""))) {
                        break
                    }
                    if (textBuilder.isNotEmpty()) {
                        textBuilder.append("\n")
                    }
                    // Remove HTML tags
                    textBuilder.append(nextLine.replace(Regex("<[^>]*>"), ""))
                    i++
                }
                
                val text = textBuilder.toString().trim()
                if (text.isNotEmpty()) {
                    cues.add(SubtitleCue(startTime, endTime, text))
                }
            } else {
                i++
            }
        }
        
        return cues
    }
    
    /**
     * Parse VTT timestamp string to seconds
     * Format: HH:MM:SS.mmm
     */
    private fun parseVttTime(timeStr: String): Double {
        val parts = timeStr.split(":")
        val hours = parts[0].toInt()
        val minutes = parts[1].toInt()
        val secondsParts = parts[2].split(".")
        val seconds = secondsParts[0].toInt()
        val milliseconds = if (secondsParts.size > 1) secondsParts[1].toInt() else 0
        
        return hours * 3600.0 + minutes * 60.0 + seconds + milliseconds / 1000.0
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
            
            // SRT format:
            // 1. Sequence number (optional, can be skipped)
            // 2. Timestamp line: 00:00:00,000 --> 00:00:00,000
            // 3. Text lines
            // 4. Empty line
            
            // Check if line is a sequence number (just digits)
            if (line.matches(Regex("""^\d+$"""))) {
                i++ // Skip sequence number
                if (i >= lines.size) break
            }
            
            // Look for timestamp line (format: 00:00:00,000 --> 00:00:00,000)
            val timeMatch = Regex("""^(\d{2}:\d{2}:\d{2}[.,]\d{3})\s*-->\s*(\d{2}:\d{2}:\d{2}[.,]\d{3})""").find(lines[i].trim())
            if (timeMatch != null) {
                val startTimeStr = timeMatch.groupValues[1].replace(',', '.')
                val endTimeStr = timeMatch.groupValues[2].replace(',', '.')
                
                val startTime = parseVttTime(startTimeStr) // Same format after replacing comma
                val endTime = parseVttTime(endTimeStr)
                
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
                            if (potentialTimestamp.matches(Regex("""^\d{2}:\d{2}:\d{2}[.,]\d{3}\s*-->\s*\d{2}:\d{2}:\d{2}[.,]\d{3}"""))) {
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
                    if (nextLine.matches(Regex("""^\d{2}:\d{2}:\d{2}[.,]\d{3}\s*-->\s*\d{2}:\d{2}:\d{2}[.,]\d{3}"""))) {
                        break
                    }
                    
                    // Check if this line is just a sequence number
                    if (nextLine.matches(Regex("""^\d+$"""))) {
                        // Might be sequence number of next subtitle, but also might be text
                        // Check if next line is a timestamp
                        if (i + 1 < lines.size) {
                            val nextNextLine = lines[i + 1].trim()
                            if (nextNextLine.matches(Regex("""^\d{2}:\d{2}:\d{2}[.,]\d{3}\s*-->\s*\d{2}:\d{2}:\d{2}[.,]\d{3}"""))) {
                                break // It's a sequence number, stop here
                            }
                        }
                    }
                    
                    if (textBuilder.isNotEmpty()) {
                        textBuilder.append("\n")
                    }
                    // Remove HTML tags and styling (SRT may contain some HTML)
                    val cleanedLine = nextLine.replace(Regex("<[^>]*>"), "")
                    textBuilder.append(cleanedLine)
                    i++
                }
                
                val text = textBuilder.toString().trim()
                if (text.isNotEmpty()) {
                    cues.add(SubtitleCue(startTime, endTime, text))
                }
            } else {
                i++
            }
        }
        
        return cues
    }
    
    /**
     * Load and parse subtitle file from URL (supports VTT and SRT)
     */
    suspend fun loadVttFromUrl(url: String, apiKey: String? = null): Result<List<SubtitleCue>> {
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 10000
                
                // Add API key header if provided
                if (!apiKey.isNullOrBlank()) {
                    connection.setRequestProperty(Constants.STASH_API_HEADER, apiKey.trim())
                }
                
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val inputStream = connection.inputStream
                    val content = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    
                    // Check if content is HTML (login page) - indicates authentication failure
                    if (content.trimStart().startsWith("<!DOCTYPE", ignoreCase = true) ||
                        content.trimStart().startsWith("<html", ignoreCase = true)) {
                        return@withContext Result.failure(Exception("需要认证：收到 HTML 登录页面而非字幕内容"))
                    }
                    
                    val cues = parseSubtitles(content, url)
                    Result.success(cues)
                } else {
                    Result.failure(Exception("HTTP ${connection.responseCode}: ${connection.responseMessage}"))
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
            val content = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            Result.success(parseSubtitles(content, url))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load subtitle from stream", e)
            Result.failure(e)
        }
    }
    
    /**
     * Subtitle format enumeration
     */
    private enum class SubtitleFormat {
        VTT, SRT
    }
    
    /**
     * Get current cue based on playback time
     */
    fun getCurrentCue(cues: List<SubtitleCue>, currentTime: Double): SubtitleCue? {
        return cues.firstOrNull { currentTime >= it.startTime && currentTime <= it.endTime }
    }
    
    /**
     * Get current cue index
     */
    fun getCurrentCueIndex(cues: List<SubtitleCue>, currentTime: Double): Int {
        return cues.indexOfFirst { currentTime >= it.startTime && currentTime <= it.endTime }
    }
}

