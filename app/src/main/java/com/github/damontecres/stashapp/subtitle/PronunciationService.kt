package com.github.damontecres.stashapp.subtitle

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Pronunciation service using backend API
 */
class PronunciationService private constructor(context: Context) {
    private val context: Context = context.applicationContext
    private var mediaPlayer: MediaPlayer? = null
    private var currentServer: StashServer? = null
    
    // Track temporary files for cleanup
    private val tempFiles = ConcurrentHashMap<String, Long>() // file path -> creation time
    private var currentTempFile: String? = null // Current file being played
    
    // Cache for pre-downloaded audio files: word_language -> file path
    private val audioCache = ConcurrentHashMap<String, String>() // cache key -> file path
    
    /**
     * Set the current server for API requests
     */
    fun setServer(server: StashServer?) {
        currentServer = server
        Log.d(TAG, "Server set: ${server?.url}")
        // Clean up old temp files when server changes
        cleanupOldTempFiles()
    }
    
    /**
     * Build pronunciation URL from backend API
     */
    private fun getPronunciationUrl(word: String, language: String): String? {
        val server = currentServer ?: run {
            Log.w(TAG, "No server configured for pronunciation")
            return null
        }
        
        val langCode = when (language.lowercase()) {
            "zh", "zh-cn" -> "zh-CN"
            else -> language.lowercase()
        }
        
        val baseUrl = server.url.trimEnd('/')
        val encodedText = URLEncoder.encode(word, "UTF-8")
        val encodedLang = URLEncoder.encode(langCode, "UTF-8")
        val url = "$baseUrl/tts/pronounce?text=$encodedText&lang=$encodedLang"
        
        return url
    }
    
    /**
     * Check if audio output is available (not muted, volume > 0)
     */
    private fun checkAudioAvailable(): Boolean {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
            val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val isMuted = volume == 0
            val isSilentMode = audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT ||
                              audioManager.ringerMode == AudioManager.RINGER_MODE_VIBRATE
            
            if (isMuted || isSilentMode) {
                Log.w(TAG, "Audio output may be unavailable - volume: $volume, silent mode: $isSilentMode")
            }
            
            return !isMuted
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check audio availability", e)
            return true
        }
    }
    
    /**
     * Register a temporary file for tracking
     */
    private fun registerTempFile(file: File) {
        val path = file.absolutePath
        tempFiles[path] = System.currentTimeMillis()
        currentTempFile = path
        Log.d(TAG, "Registered temp file: $path")
    }
    
    /**
     * Download audio file from URL to a temporary file
     */
    private suspend fun downloadAudioFile(url: String): File {
        val tempFile = File(context.cacheDir, "pronunciation_${System.currentTimeMillis()}.mp3")
        registerTempFile(tempFile)
        
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 10000
        connection.setRequestProperty("User-Agent", "StashApp/1.0")
        
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
        
        if (finalConnection.responseCode != HttpURLConnection.HTTP_OK) {
            finalConnection.disconnect()
            deleteTempFile(tempFile.absolutePath)
            throw Exception("HTTP error: ${finalConnection.responseCode} - ${finalConnection.responseMessage}")
        }
        
        // Check content type
        val contentType = finalConnection.contentType ?: ""
        if (!contentType.startsWith("audio/", ignoreCase = true) &&
            !contentType.startsWith("application/octet-stream", ignoreCase = true)) {
            finalConnection.disconnect()
            deleteTempFile(tempFile.absolutePath)
            throw Exception("服务器返回的不是音频文件 (Content-Type: $contentType)")
        }
        
        val inputStream: InputStream = finalConnection.inputStream
        FileOutputStream(tempFile).use { output ->
            inputStream.copyTo(output)
        }
        inputStream.close()
        finalConnection.disconnect()
        
        return tempFile
    }
    
    /**
     * Delete a temporary file and remove from tracking
     */
    private fun deleteTempFile(filePath: String) {
        try {
            val file = File(filePath)
            if (file.exists()) {
                val deleted = file.delete()
                if (deleted) {
                    Log.d(TAG, "Deleted temp file: $filePath")
                } else {
                    Log.w(TAG, "Failed to delete temp file: $filePath")
                }
            }
            tempFiles.remove(filePath)
            if (currentTempFile == filePath) {
                currentTempFile = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting temp file: $filePath", e)
            tempFiles.remove(filePath)
        }
    }
    
    /**
     * Clean up old temporary files (older than 1 hour)
     */
    private fun cleanupOldTempFiles() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val now = System.currentTimeMillis()
                val maxAge = 60 * 60 * 1000L // 1 hour
                val filesToDelete = mutableListOf<String>()
                
                tempFiles.forEach { (path, creationTime) ->
                    if (now - creationTime > maxAge) {
                        filesToDelete.add(path)
                    }
                }
                
                // Also clean up old cached audio files
                val cacheKeysToRemove = mutableListOf<String>()
                audioCache.forEach { (cacheKey, filePath) ->
                    val file = File(filePath)
                    if (!file.exists()) {
                        cacheKeysToRemove.add(cacheKey)
                    } else {
                        val lastModified = file.lastModified()
                        if (now - lastModified > maxAge) {
                            cacheKeysToRemove.add(cacheKey)
                            filesToDelete.add(filePath)
                        }
                    }
                }
                cacheKeysToRemove.forEach { audioCache.remove(it) }
                
                filesToDelete.forEach { path ->
                    deleteTempFile(path)
                }
                
                // Also scan cacheDir for any orphaned pronunciation files
                val cacheDir = context.cacheDir
                if (cacheDir.exists()) {
                    val files = cacheDir.listFiles { file ->
                        file.name.startsWith("pronunciation_") && file.name.endsWith(".mp3")
                    }
                    files?.forEach { file ->
                        val filePath = file.absolutePath
                        if (!tempFiles.containsKey(filePath) && !audioCache.values.contains(filePath)) {
                            // Not tracked, check if it's old
                            val lastModified = file.lastModified()
                            if (now - lastModified > maxAge) {
                                try {
                                    if (file.delete()) {
                                        Log.d(TAG, "Cleaned up orphaned temp file: $filePath")
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to delete orphaned temp file: $filePath", e)
                                }
                            }
                        }
                    }
                }
                
                if (filesToDelete.isNotEmpty() || cacheKeysToRemove.isNotEmpty()) {
                    Log.d(TAG, "Cleaned up ${filesToDelete.size} old temp files and ${cacheKeysToRemove.size} cache entries")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during temp file cleanup", e)
            }
        }
    }
    
    /**
     * Pre-download audio file for a word (called when dictionary dialog opens)
     */
    suspend fun preloadPronunciation(word: String, language: String = "en"): Result<File> {
        val cacheKey = "${word}_$language"
        
        // Check if already cached
        audioCache[cacheKey]?.let { cachedPath ->
            val cachedFile = File(cachedPath)
            if (cachedFile.exists()) {
                Log.d(TAG, "Audio already cached for word: '$word'")
                return Result.success(cachedFile)
            } else {
                // File was deleted, remove from cache
                audioCache.remove(cacheKey)
            }
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val url = getPronunciationUrl(word, language)
                if (url == null) {
                    return@withContext Result.failure(Exception("No server configured or invalid URL"))
                }
                
                val tempFile = downloadAudioFile(url)
                audioCache[cacheKey] = tempFile.absolutePath
                Log.d(TAG, "Pre-loaded audio for word: '$word', file: ${tempFile.absolutePath}")
                Result.success(tempFile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to preload pronunciation for word: '$word'", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * Play pronunciation for a word using backend API
     * If the file was preloaded, use the cached file; otherwise download it
     */
    suspend fun playPronunciation(word: String, language: String = "en"): Result<Unit> {
        return withContext(Dispatchers.Main) {
            try {
                val url = getPronunciationUrl(word, language)
                if (url == null) {
                    return@withContext Result.failure(Exception("No server configured or invalid URL"))
                }
                
                checkAudioAvailable()
                stop()
                delay(50)
                
                val cacheKey = "${word}_$language"
                val cachedFilePath = audioCache[cacheKey]
                val tempFile = if (cachedFilePath != null && File(cachedFilePath).exists()) {
                    // Use cached file
                    Log.d(TAG, "Using cached audio file for word: '$word'")
                    File(cachedFilePath)
                } else {
                    // Download if not cached
                    Log.d(TAG, "Audio not cached, downloading for word: '$word'")
                    withContext(Dispatchers.IO) {
                        val file = downloadAudioFile(url)
                        audioCache[cacheKey] = file.absolutePath
                        file
                    }
                }
                
                suspendCancellableCoroutine { continuation ->
                    val player = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build()
                        )
                        
                        setOnPreparedListener { mp ->
                            mp.start()
                        }
                        
                        setOnCompletionListener { mp ->
                            mp.release()
                            mediaPlayer = null
                            // Don't delete cached files - they'll be cleaned up by cleanupOldTempFiles
                            // Only delete if not in cache (shouldn't happen, but safety check)
                            currentTempFile?.let { filePath ->
                                val cacheKey = "${word}_$language"
                                if (audioCache[cacheKey] != filePath) {
                                    deleteTempFile(filePath)
                                }
                            }
                            
                            if (continuation.isActive) {
                                continuation.resume(Unit)
                            }
                        }
                        
                        setOnErrorListener { mp, what, extra ->
                            mp.release()
                            mediaPlayer = null
                            // Remove from cache on error, but don't delete file immediately
                            // (it might be useful for debugging)
                            val cacheKey = "${word}_$language"
                            audioCache.remove(cacheKey)
                            
                            val error = Exception("Playback failed - what: $what, extra: $extra")
                            if (continuation.isActive) {
                                continuation.resumeWithException(error)
                            }
                            false
                        }
                    }
                    
                    mediaPlayer = player
                    currentTempFile = tempFile.absolutePath
                    
                    // Use the file we already have (either from cache or downloaded)
                    CoroutineScope(continuation.context).launch(Dispatchers.IO) {
                        withContext(Dispatchers.Main) {
                            try {
                                if (!continuation.isCancelled && player === mediaPlayer) {
                                    player.setDataSource(tempFile.absolutePath)
                                    player.prepareAsync()
                                } else {
                                    player.release()
                                    mediaPlayer = null
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to set data source: ${e.message}", e)
                                player.release()
                                mediaPlayer = null
                                if (continuation.isActive) {
                                    continuation.resumeWithException(Exception("Failed to play pronunciation: ${e.message}"))
                                }
                            }
                        }
                    }
                }
                
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play pronunciation for word: '$word'", e)
                mediaPlayer?.release()
                mediaPlayer = null
                // Remove from cache on error
                val cacheKey = "${word}_$language"
                audioCache.remove(cacheKey)
                
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
            currentTempFile?.let { deleteTempFile(it) }
        }
    }
    
    /**
     * Shutdown pronunciation service
     */
    fun shutdown() {
        stop()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val filesToDelete = tempFiles.keys.toList()
                filesToDelete.forEach { deleteTempFile(it) }
                
                // Clear audio cache
                val cacheFilesToDelete = audioCache.values.toList()
                cacheFilesToDelete.forEach { filePath ->
                    try {
                        val file = File(filePath)
                        if (file.exists() && file.delete()) {
                            Log.d(TAG, "Cleaned up cached audio file on shutdown: $filePath")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete cached audio file on shutdown: $filePath", e)
                    }
                }
                audioCache.clear()
                
                val cacheDir = context.cacheDir
                if (cacheDir.exists()) {
                    val files = cacheDir.listFiles { file ->
                        file.name.startsWith("pronunciation_") && file.name.endsWith(".mp3")
                    }
                    files?.forEach { file ->
                        try {
                            if (file.delete()) {
                                Log.d(TAG, "Cleaned up temp file on shutdown: ${file.absolutePath}")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to delete temp file on shutdown: ${file.absolutePath}", e)
                        }
                    }
                }
                
                if (filesToDelete.isNotEmpty() || cacheFilesToDelete.isNotEmpty()) {
                    Log.d(TAG, "Cleaned up ${filesToDelete.size} temp files and ${cacheFilesToDelete.size} cached audio files on shutdown")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during shutdown cleanup", e)
            }
        }
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

