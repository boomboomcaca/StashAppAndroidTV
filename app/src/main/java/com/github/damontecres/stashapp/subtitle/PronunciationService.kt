package com.github.damontecres.stashapp.subtitle

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import com.github.damontecres.stashapp.util.Constants
import com.github.damontecres.stashapp.util.StashServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Pronunciation service using backend API
 */
class PronunciationService private constructor(context: Context) {
    private val context: Context = context.applicationContext
    private var mediaPlayer: MediaPlayer? = null
    private var currentServer: StashServer? = null
    
    /**
     * Set the current server for API requests
     */
    fun setServer(server: StashServer?) {
        currentServer = server
        Log.d(TAG, "Server set: ${server?.url}")
    }
    
    /**
     * Build pronunciation URL from backend API
     */
    private fun getPronunciationUrl(word: String, language: String): String? {
        val server = currentServer ?: run {
            Log.w(TAG, "No server configured for pronunciation")
            return null
        }
        
        // Convert language codes (same as backend)
        val langCode = when (language.lowercase()) {
            "zh", "zh-cn" -> "zh-CN"
            else -> language.lowercase()
        }
        
        // Build URL: /tts/pronounce?text={word}&lang={lang} (pronunciation endpoint)
        val baseUrl = server.url.trimEnd('/')
        val encodedText = URLEncoder.encode(word, "UTF-8")
        val encodedLang = URLEncoder.encode(langCode, "UTF-8")
        val url = "$baseUrl/tts/pronounce?text=$encodedText&lang=$encodedLang"
        
        Log.d(TAG, "Pronunciation URL: $url")
        return url
    }
    
    /**
     * Check if audio output is available (not muted, volume > 0)
     */
    private fun checkAudioAvailable(): Boolean {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager == null) {
                Log.w(TAG, "AudioManager is null")
                return true // Assume available if can't check
            }
            
            val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val isMuted = volume == 0
            val isSilentMode = audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT ||
                              audioManager.ringerMode == AudioManager.RINGER_MODE_VIBRATE
            
            Log.d(TAG, "Audio check - volume: $volume, muted: $isMuted, silent mode: $isSilentMode")
            
            if (isMuted || isSilentMode) {
                Log.w(TAG, "Audio output may be unavailable - volume: $volume, silent mode: $isSilentMode")
            }
            
            return !isMuted
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check audio availability", e)
            return true // Assume available if check fails
        }
    }
    
    /**
     * Internal class to track playback attempt state
     */
    private class PlaybackAttempt(
        val word: String,
        val url: String,
        var currentMethod: Int = 0
    )
    
    /**
     * Play pronunciation for a word using backend API
     */
    suspend fun playPronunciation(word: String, language: String = "en"): Result<Unit> {
        return withContext(Dispatchers.Main) {
            try {
                Log.d(TAG, "Requesting pronunciation for word: '$word', language: $language")
                
                // Get pronunciation URL
                val url = getPronunciationUrl(word, language)
                if (url == null) {
                    return@withContext Result.failure(Exception("No server configured or invalid URL"))
                }
                
                // Check audio availability
                if (!checkAudioAvailable()) {
                    Log.w(TAG, "Audio output may be unavailable - volume may be 0 or device in silent mode")
                    // Continue anyway, some devices may still play through speakers
                }
                
                // Stop any currently playing audio
                stop()
                
                // Small delay to ensure stop() completes
                delay(50)
                
                val attempt = PlaybackAttempt(word, url)
                var lastError: Exception? = null
                
                // Use suspendCancellableCoroutine to handle async errors
                suspendCancellableCoroutine { continuation ->
                    val player = MediaPlayer().apply {
                        setAudioStreamType(android.media.AudioManager.STREAM_MUSIC)
                        
                        setOnPreparedListener { mp ->
                            Log.d(TAG, "MediaPlayer prepared for Method ${attempt.currentMethod}, starting playback for word: '$word'")
                            mp.start()
                        }
                        
                        setOnCompletionListener { mp ->
                            Log.d(TAG, "Pronunciation playback completed for word: '$word'")
                            mp.release()
                            mediaPlayer = null
                            if (continuation.isActive) {
                                continuation.resume(Unit)
                            }
                        }
                        
                        setOnErrorListener { mp, what, extra ->
                            Log.e(TAG, "MediaPlayer error for Method ${attempt.currentMethod}, word: '$word', what: $what, extra: $extra")
                            
                            // Try next method if available
                            attempt.currentMethod++
                            
                            when (attempt.currentMethod) {
                                1 -> {
                                    // Try Method 2
                                    Log.d(TAG, "Method 1 failed, trying Method 2")
                                    try {
                                        mp.reset()
                                        mp.setDataSource(context, Uri.parse(attempt.url))
                                        mp.prepareAsync()
                                        // Will continue to wait for result
                                        return@setOnErrorListener true
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Method 2 setup failed: ${e.message}")
                                        attempt.currentMethod++
                                    }
                                }
                                2 -> {
                                    // Try Method 3
                                    Log.d(TAG, "Method 2 failed, trying Method 3 (downloading)")
                                    try {
                                        mp.reset()
                                        // Download in background thread using continuation's context
                                        CoroutineScope(continuation.context).launch(Dispatchers.IO) {
                                            try {
                                                val tempFile = File(context.cacheDir, "pronunciation_${System.currentTimeMillis()}.mp3")
                                                tempFile.deleteOnExit()
                                                
                                                val connection = URL(attempt.url).openConnection() as HttpURLConnection
                                                connection.connectTimeout = 5000
                                                connection.readTimeout = 10000
                                                connection.setRequestProperty("User-Agent", "StashApp/1.0")
                                                
                                                // Add API key header if available
                                                val server = currentServer
                                                if (!server?.apiKey.isNullOrBlank()) {
                                                    connection.setRequestProperty(Constants.STASH_API_HEADER, server!!.apiKey!!.trim())
                                                }
                                                
                                                connection.connect()
                                                
                                                // Handle redirects (302, 301, etc.)
                                                var finalConnection = connection
                                                var redirectCount = 0
                                                while (redirectCount < 5 && (finalConnection.responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                                                        finalConnection.responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                                                        finalConnection.responseCode == HttpURLConnection.HTTP_SEE_OTHER)) {
                                                    redirectCount++
                                                    val location = finalConnection.getHeaderField("Location")
                                                    if (location != null) {
                                                        finalConnection.disconnect()
                                                        finalConnection = URL(location).openConnection() as HttpURLConnection
                                                        finalConnection.connectTimeout = 5000
                                                        finalConnection.readTimeout = 10000
                                                        finalConnection.setRequestProperty("User-Agent", "StashApp/1.0")
                                                        if (!server?.apiKey.isNullOrBlank()) {
                                                            finalConnection.setRequestProperty(Constants.STASH_API_HEADER, server!!.apiKey!!.trim())
                                                        }
                                                        finalConnection.connect()
                                                    } else {
                                                        break
                                                    }
                                                }
                                                
                                                if (finalConnection.responseCode == HttpURLConnection.HTTP_OK) {
                                                    // Check content type to ensure it's audio
                                                    val contentType = finalConnection.contentType ?: ""
                                                    if (!contentType.startsWith("audio/", ignoreCase = true) &&
                                                        !contentType.startsWith("application/octet-stream", ignoreCase = true)) {
                                                        finalConnection.disconnect()
                                                        val error = Exception("服务器返回的不是音频文件 (Content-Type: $contentType)")
                                                        withContext(Dispatchers.Main) {
                                                            mp.release()
                                                            mediaPlayer = null
                                                            if (continuation.isActive) {
                                                                continuation.resumeWithException(error)
                                                            }
                                                        }
                                                        return@launch
                                                    }
                                                    
                                                    val inputStream: InputStream = finalConnection.inputStream
                                                    FileOutputStream(tempFile).use { output ->
                                                        inputStream.copyTo(output)
                                                    }
                                                    inputStream.close()
                                                    finalConnection.disconnect()
                                                    
                                                    Log.d(TAG, "Audio downloaded to temp file: ${tempFile.absolutePath}")
                                                    
                                                    // Set data source on main thread (MediaPlayer requires main thread)
                                                    withContext(Dispatchers.Main) {
                                                        try {
                                                            if (!continuation.isCancelled && mp === mediaPlayer) {
                                                                mp.setDataSource(tempFile.absolutePath)
                                                                mp.prepareAsync()
                                                                // Will continue to wait for result
                                                            } else {
                                                                mp.release()
                                                                mediaPlayer = null
                                                            }
                                                        } catch (e: Exception) {
                                                            Log.e(TAG, "Method 3 setup failed: ${e.message}")
                                                            mp.release()
                                                            mediaPlayer = null
                                                            if (continuation.isActive) {
                                                                continuation.resumeWithException(Exception("All playback methods failed: ${e.message}"))
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    finalConnection.disconnect()
                                                    val error = Exception("HTTP error: ${finalConnection.responseCode} - ${finalConnection.responseMessage}")
                                                    withContext(Dispatchers.Main) {
                                                        mp.release()
                                                        mediaPlayer = null
                                                        if (continuation.isActive) {
                                                            continuation.resumeWithException(error)
                                                        }
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                    mp.release()
                                                    mediaPlayer = null
                                                    if (continuation.isActive) {
                                                        continuation.resumeWithException(Exception("Failed to download audio: ${e.message}"))
                                                    }
                                                }
                                            }
                                        }
                                        return@setOnErrorListener true
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Method 3 setup failed: ${e.message}")
                                    }
                                }
                            }
                            
                            // All methods exhausted
                            mp.release()
                            mediaPlayer = null
                            val error = Exception("All playback methods failed - what: $what, extra: $extra")
                            if (continuation.isActive) {
                                continuation.resumeWithException(error)
                            }
                            false
                        }
                    }
                    
                    mediaPlayer = player
                    
                    // Try Method 1: Direct URL string
                    var method1Succeeded = false
                    try {
                        player.setDataSource(url)
                        player.prepareAsync()
                        method1Succeeded = true
                        Log.d(TAG, "Method 1: setDataSource with URL string succeeded")
                    } catch (e: Exception) {
                        Log.w(TAG, "Method 1 failed synchronously: ${e.message}, trying Method 2")
                        lastError = e
                        attempt.currentMethod = 1
                        
                        // Try Method 2 synchronously
                        try {
                            player.reset()
                            player.setDataSource(context, Uri.parse(url))
                            player.prepareAsync()
                            method1Succeeded = true
                            Log.d(TAG, "Method 2: setDataSource with Uri succeeded")
                        } catch (e2: Exception) {
                            Log.w(TAG, "Method 2 failed synchronously: ${e2.message}, trying Method 3")
                            lastError = e2
                            attempt.currentMethod = 2
                            
                            // Try Method 3 synchronously
                            try {
                                player.reset()
                                
                                val tempFile = File(context.cacheDir, "pronunciation_${System.currentTimeMillis()}.mp3")
                                tempFile.deleteOnExit()
                                
                                val connection = URL(url).openConnection() as HttpURLConnection
                                connection.connectTimeout = 5000
                                connection.readTimeout = 10000
                                connection.setRequestProperty("User-Agent", "StashApp/1.0")
                                
                                // Add API key header if available
                                val server = currentServer
                                if (!server?.apiKey.isNullOrBlank()) {
                                    connection.setRequestProperty(Constants.STASH_API_HEADER, server!!.apiKey!!.trim())
                                }
                                
                                connection.connect()
                                
                                // Handle redirects
                                var finalConnection = connection
                                var redirectCount = 0
                                while (redirectCount < 5 && (finalConnection.responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                                        finalConnection.responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                                        finalConnection.responseCode == HttpURLConnection.HTTP_SEE_OTHER)) {
                                    redirectCount++
                                    val location = finalConnection.getHeaderField("Location")
                                    if (location != null) {
                                        finalConnection.disconnect()
                                        finalConnection = URL(location).openConnection() as HttpURLConnection
                                        finalConnection.connectTimeout = 5000
                                        finalConnection.readTimeout = 10000
                                        finalConnection.setRequestProperty("User-Agent", "StashApp/1.0")
                                        if (!server?.apiKey.isNullOrBlank()) {
                                            finalConnection.setRequestProperty(Constants.STASH_API_HEADER, server!!.apiKey!!.trim())
                                        }
                                        finalConnection.connect()
                                    } else {
                                        break
                                    }
                                }
                                
                                if (finalConnection.responseCode == HttpURLConnection.HTTP_OK) {
                                    // Check content type
                                    val contentType = finalConnection.contentType ?: ""
                                    if (!contentType.startsWith("audio/", ignoreCase = true) &&
                                        !contentType.startsWith("application/octet-stream", ignoreCase = true)) {
                                        finalConnection.disconnect()
                                        throw Exception("服务器返回的不是音频文件 (Content-Type: $contentType)")
                                    }
                                    
                                    val inputStream: InputStream = finalConnection.inputStream
                                    FileOutputStream(tempFile).use { output ->
                                        inputStream.copyTo(output)
                                    }
                                    inputStream.close()
                                    finalConnection.disconnect()
                                    
                                    Log.d(TAG, "Audio downloaded to temp file: ${tempFile.absolutePath}")
                                    player.setDataSource(tempFile.absolutePath)
                                    player.prepareAsync()
                                    method1Succeeded = true
                                    Log.d(TAG, "Method 3: setDataSource with temp file succeeded")
                } else {
                                    finalConnection.disconnect()
                                    throw Exception("HTTP error: ${finalConnection.responseCode} - ${finalConnection.responseMessage}")
                                }
                            } catch (e3: Exception) {
                                Log.e(TAG, "All methods failed synchronously: ${e3.message}", e3)
                                player.release()
                                mediaPlayer = null
                                continuation.resumeWithException(Exception("Failed to play pronunciation: ${e3.message}"))
                                return@suspendCancellableCoroutine
                            }
                        }
                    }
                    
                    if (!method1Succeeded) {
                        // This shouldn't happen if we reach here, but just in case
                        player.release()
                        mediaPlayer = null
                        continuation.resumeWithException(lastError ?: Exception("Failed to play pronunciation"))
                    }
                }
                
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play pronunciation for word: '$word'", e)
                mediaPlayer?.release()
                mediaPlayer = null
                Result.failure(e)
            }
        }
    }
    
    /**
     * Stop any currently playing audio
     */
    fun stop() {
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping MediaPlayer", e)
            }
            mediaPlayer = null
        }
    }
    
    /**
     * Shutdown pronunciation service
     */
    fun shutdown() {
        stop()
    }
    
    companion object {
        private const val TAG = "PronunciationService"
        
        @Volatile
        private var INSTANCE: PronunciationService? = null
        
        fun getInstance(context: Context): PronunciationService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PronunciationService(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}

