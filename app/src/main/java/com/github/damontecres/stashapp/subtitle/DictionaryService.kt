package com.github.damontecres.stashapp.subtitle

import android.util.Log
import com.apollographql.apollo.ApolloClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dictionary service for word lookups using Ollama backend
 * Note: Ollama functionality is not available in the current GraphQL schema
 */
class DictionaryService(
    private val apolloClient: ApolloClient?
) {
    private val cache = mutableMapOf<String, DictionaryEntry>()
    private val MAX_CACHE_SIZE = 100
    
    /**
     * Look up a word in the dictionary
     * Note: This functionality is currently not available as the GraphQL mutation is not in the schema
     */
    suspend fun lookup(
        word: String,
        language: String = "en",
        context: String = ""
    ): DictionaryEntry? {
        return withContext(Dispatchers.IO) {
            val cacheKey = if (context.isNotEmpty()) {
                "${word.lowercase()}_${language}_${context.take(50)}"
            } else {
                "${word.lowercase()}_${language}"
            }
            
            // Check cache first
            cache[cacheKey]?.let { return@withContext it }
            
            // Ollama functionality is not available in the current GraphQL schema
            Log.w(TAG, "Dictionary lookup not available - Ollama mutation not in schema")
            return@withContext createBasicEntry(word, language, "功能不可用")
        }
    }
    
    private fun createBasicEntry(
        word: String,
        language: String,
        reason: String = "Definition not available"
    ): DictionaryEntry {
        return DictionaryEntry(
            word = word,
            definitions = listOf(
                DictionaryDefinition(
                    partOfSpeech = "unknown",
                    meaning = "$word ($language) - $reason"
                )
            )
        )
    }
    
    /**
     * Clear cache
     */
    fun clearCache() {
        cache.clear()
    }
    
    /**
     * Get cache size
     */
    fun getCacheSize(): Int = cache.size
    
    companion object {
        private const val TAG = "DictionaryService"
    }
}

