package com.github.damontecres.stashapp.subtitle

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import android.util.LruCache
import com.github.damontecres.stashapp.util.StashServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Pronunciation service using backend API
 */
class PronunciationService private constructor(context: Context) {
    private val context: Context = context.applicationContext
    private var mediaPlayer: MediaPlayer? = null
    private var currentServer: StashServer? = null

    // Single owned scope for background file IO so work is structured and cancellable.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Track temporary files for cleanup
    private val tempFiles = ConcurrentHashMap<String, Long>() // file path -> creation time
    private var currentTempFile: String? = null // Current file being played

    // Bounded cache for pre-downloaded audio files: word_language -> file path.
    // Evicted entries have their backing file deleted (entryRemoved below).
    private val audioCache = object : LruCache<String, String>(MAX_AUDIO_CACHE) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: String, newValue: String?) {
            if (oldValue != newValue) {
                try {
                    val f = File(oldValue)
                    if (f.exists()) f.delete()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete evicted audio file: $oldValue", e)
                }
                tempFiles.remove(oldValue)
            }
        }
    }

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

        val baseUrl = com.github.damontecres.stashapp.util.StashClient.getServerRoot(server.url).trimEnd('/')
        val encodedText = URLEncoder.encode(word, "UTF-8")
        val encodedLang = URLEncoder.encode(langCode, "UTF-8")
        return "$baseUrl/tts/pronounce?text=$encodedText&lang=$encodedLang"
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
        Log.d(TAG, "Registered temp file: $path")
    }

    /**
     * Download audio file from URL to a temporary file. Runs on Dispatchers.IO.
     */
    private suspend fun downloadAudioFile(url: String): File {
        return withContext(Dispatchers.IO) {
            val tempFile = File(context.cacheDir, "pronunciation_${System.currentTimeMillis()}.mp3")
            registerTempFile(tempFile)

            val server = currentServer ?: throw Exception("未连接到服务器")
            // Bound the whole call so a stalled/trickling stream cannot hang forever.
            val client = server.okHttpClient.newBuilder()
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "StashApp/1.0")
                .build()

            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    deleteTempFile(tempFile.absolutePath)
                    throw IOException("HTTP error: ${response.code} - ${response.message}")
                }

                val body = response.body ?: run {
                    deleteTempFile(tempFile.absolutePath)
                    throw IOException("Response body is empty")
                }

                // Check content type
                val contentType = body.contentType()?.toString() ?: ""
                if (!contentType.startsWith("audio/", ignoreCase = true) &&
                    !contentType.startsWith("application/octet-stream", ignoreCase = true)
                ) {
                    body.close()
                    deleteTempFile(tempFile.absolutePath)
                    throw Exception("服务器返回的不是音频文件 (Content-Type: $contentType)")
                }

                // Copy with an upper bound so a misbehaving endpoint cannot fill the cache.
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buf = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_AUDIO_BYTES) {
                                throw IOException("音频文件过大")
                            }
                            output.write(buf, 0, n)
                        }
                    }
                }
                tempFile
            } catch (e: Exception) {
                deleteTempFile(tempFile.absolutePath)
                throw e
            }
        }
    }

    /**
     * Delete a temporary file and remove from tracking
     */
    private fun deleteTempFile(filePath: String) {
        try {
            val file = File(filePath)
            if (file.exists()) {
                if (file.delete()) {
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
        serviceScope.launch {
            try {
                val now = System.currentTimeMillis()
                val maxAge = 60 * 60 * 1000L // 1 hour
                val cachedPaths = audioCache.snapshot().values.toSet()
                val filesToDelete = mutableListOf<String>()

                tempFiles.forEach { (path, creationTime) ->
                    if (now - creationTime > maxAge && !cachedPaths.contains(path)) {
                        filesToDelete.add(path)
                    }
                }

                filesToDelete.forEach { path -> deleteTempFile(path) }

                // Also scan cacheDir for any orphaned pronunciation files
                val cacheDir = context.cacheDir
                if (cacheDir.exists()) {
                    val files = cacheDir.listFiles { file ->
                        file.name.startsWith("pronunciation_") && file.name.endsWith(".mp3")
                    }
                    files?.forEach { file ->
                        val filePath = file.absolutePath
                        if (!tempFiles.containsKey(filePath) && !cachedPaths.contains(filePath)) {
                            if (now - file.lastModified() > maxAge) {
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

                if (filesToDelete.isNotEmpty()) {
                    Log.d(TAG, "Cleaned up ${filesToDelete.size} old temp files")
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
        audioCache.get(cacheKey)?.let { cachedPath ->
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
                    ?: return@withContext Result.failure(Exception("No server configured or invalid URL"))

                val tempFile = downloadAudioFile(url)
                audioCache.put(cacheKey, tempFile.absolutePath)
                Log.d(TAG, "Pre-loaded audio for word: '$word', file: ${tempFile.absolutePath}")
                Result.success(tempFile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to preload pronunciation for word: '$word'", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Play pronunciation for a word using backend API.
     * Resolves the file (cache hit or download) on IO, then plays on the main thread.
     */
    suspend fun playPronunciation(word: String, language: String = "en"): Result<Unit> {
        return try {
            val url = getPronunciationUrl(word, language)
                ?: return Result.failure(Exception("No server configured or invalid URL"))

            val cacheKey = "${word}_$language"

            // Resolve the audio file entirely off the main thread.
            val tempFile = withContext(Dispatchers.IO) {
                val cachedFilePath = audioCache.get(cacheKey)
                if (cachedFilePath != null && File(cachedFilePath).exists()) {
                    Log.d(TAG, "Using cached audio file for word: '$word'")
                    File(cachedFilePath)
                } else {
                    Log.d(TAG, "Audio not cached, downloading for word: '$word'")
                    val file = downloadAudioFile(url)
                    audioCache.put(cacheKey, file.absolutePath)
                    file
                }
            }

            // Play on the main thread (MediaPlayer must be created/used on a single looper).
            withContext(Dispatchers.Main) {
                checkAudioAvailable()
                stop()
                delay(50)
                playFile(tempFile, cacheKey)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play pronunciation for word: '$word'", e)
            withContext(Dispatchers.Main) {
                mediaPlayer?.release()
                mediaPlayer = null
            }
            Result.failure(e)
        }
    }

    /**
     * Create, prepare and start a MediaPlayer for [tempFile]. Suspends until playback
     * completes or fails, and releases the player on cancellation.
     */
    private suspend fun playFile(tempFile: File, cacheKey: String) =
        suspendCancellableCoroutine<Unit> { continuation ->
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )

                setOnPreparedListener { mp -> mp.start() }

                setOnCompletionListener { mp ->
                    mp.release()
                    if (mediaPlayer === mp) mediaPlayer = null
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }

                setOnErrorListener { mp, what, extra ->
                    mp.release()
                    if (mediaPlayer === mp) mediaPlayer = null
                    // Drop the (possibly corrupt) cache entry; its file is removed via entryRemoved.
                    audioCache.remove(cacheKey)
                    if (continuation.isActive) {
                        continuation.resumeWithException(Exception("Playback failed - what: $what, extra: $extra"))
                    }
                    true // handled; don't also fire onCompletion
                }
            }

            mediaPlayer = player
            currentTempFile = tempFile.absolutePath

            // Always release the native player if the coroutine is cancelled mid-playback.
            continuation.invokeOnCancellation {
                try {
                    player.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing player on cancellation", e)
                }
                if (mediaPlayer === player) mediaPlayer = null
            }

            try {
                player.setDataSource(tempFile.absolutePath)
                player.prepareAsync()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set data source: ${e.message}", e)
                try {
                    player.release()
                } catch (_: Exception) {
                }
                if (mediaPlayer === player) mediaPlayer = null
                if (continuation.isActive) {
                    continuation.resumeWithException(Exception("Failed to play pronunciation: ${e.message}"))
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

            // Only delete the temp file if it is NOT a cached (reusable) file.
            currentTempFile?.let { path ->
                if (!audioCache.snapshot().containsValue(path)) {
                    deleteTempFile(path)
                }
            }
            currentTempFile = null
        }
    }

    /**
     * Shutdown pronunciation service (release the player and clear caches/files).
     * Note: this is a process-wide singleton — only call this when the whole app is
     * tearing down, not from a per-screen ViewModel.
     */
    fun shutdown() {
        stop()
        serviceScope.launch {
            try {
                tempFiles.keys.toList().forEach { deleteTempFile(it) }
                audioCache.evictAll() // entryRemoved deletes each backing file

                val cacheDir = context.cacheDir
                if (cacheDir.exists()) {
                    cacheDir.listFiles { file ->
                        file.name.startsWith("pronunciation_") && file.name.endsWith(".mp3")
                    }?.forEach { file ->
                        try {
                            file.delete()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to delete temp file on shutdown: ${file.absolutePath}", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during shutdown cleanup", e)
            }
        }
    }

    companion object {
        private const val TAG = "PronunciationService"
        private const val MAX_AUDIO_CACHE = 50
        private const val MAX_AUDIO_BYTES = 25L * 1024 * 1024 // 25 MB per clip
        private const val CALL_TIMEOUT_SECONDS = 30L

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
