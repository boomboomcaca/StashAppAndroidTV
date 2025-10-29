package com.github.damontecres.stashapp.subtitle

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Pronunciation service using Android Text-to-Speech
 */
class PronunciationService private constructor(context: Context) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    
    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                Log.d(TAG, "TTS initialized successfully")
            } else {
                Log.e(TAG, "TTS initialization failed")
            }
        }
    }
    
    /**
     * Play pronunciation for a word
     */
    suspend fun playPronunciation(word: String, language: String = "en"): Result<Unit> {
        return withContext(Dispatchers.Main) {
            try {
                if (!isInitialized || tts == null) {
                    return@withContext Result.failure(Exception("TTS not initialized"))
                }
                
                val locale = mapLanguageToLocale(language)
                val setLangResult = tts!!.setLanguage(locale)
                
                if (setLangResult == TextToSpeech.LANG_MISSING_DATA ||
                    setLangResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.w(TAG, "Language not supported: $language, using default")
                }
                
                // Stop any currently playing speech
                tts!!.stop()
                
                // Speak the word
                val result = tts!!.speak(
                    word,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "pronunciation_${word}_${language}"
                )
                
                if (result == TextToSpeech.ERROR) {
                    Result.failure(Exception("TTS speak error"))
                } else {
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play pronunciation for word: $word", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * Stop any currently playing speech
     */
    fun stop() {
        tts?.stop()
    }
    
    /**
     * Shutdown TTS engine
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
    
    private fun mapLanguageToLocale(language: String): Locale {
        return when (language.lowercase()) {
            "zh", "zh-cn" -> Locale.SIMPLIFIED_CHINESE
            "zh-tw" -> Locale.TRADITIONAL_CHINESE
            "en" -> Locale.ENGLISH
            "es" -> Locale("es", "ES")
            "fr" -> Locale.FRENCH
            "de" -> Locale.GERMAN
            "ja" -> Locale.JAPANESE
            "ko" -> Locale.KOREAN
            "ru" -> Locale("ru", "RU")
            else -> Locale.ENGLISH // Default to English
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

