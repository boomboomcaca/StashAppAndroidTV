package com.github.damontecres.stashapp.subtitle

import android.util.Log
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.github.damontecres.stashapp.api.OllamaExplainWordMutation
import com.github.damontecres.stashapp.api.type.OllamaExplainWordInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dictionary service for word lookups using Ollama backend
 */
class DictionaryService(
    private val apolloClient: ApolloClient?
) {
    private val cache = mutableMapOf<String, DictionaryEntry>()
    private val MAX_CACHE_SIZE = 100
    
    /**
     * Look up a word in the dictionary
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
            
            try {
                if (apolloClient == null) {
                    Log.w(TAG, "ApolloClient is null, cannot lookup word")
                    return@withContext createBasicEntry(word, language, "服务不可用")
                }
                
                // Use GraphQL mutation to lookup word
                val input = OllamaExplainWordInput(
                    word = word,
                    context = context,
                    language = Optional.presentIfNotNull(language.takeIf { it.isNotEmpty() })
                )
                
                val mutation = OllamaExplainWordMutation(input)
                val result = apolloClient.mutation(mutation).execute()
                
                val response = result.data?.ollamaExplainWord
                if (response != null) {
                    val entry = DictionaryEntry(
                        word = response.word,
                        pronunciation = response.pronunciation,
                        definitions = response.definitions.map { def ->
                            DictionaryDefinition(
                                partOfSpeech = def.partOfSpeech.ifEmpty { "unknown" },
                                meaning = def.meaning,
                                examples = def.examples.filterNotNull()
                            )
                        },
                        etymology = response.etymology
                    )
                    
                    // Cache the entry with size limit
                    if (cache.size >= MAX_CACHE_SIZE) {
                        val firstKey = cache.keys.firstOrNull()
                        if (firstKey != null) {
                            cache.remove(firstKey)
                        }
                    }
                    cache[cacheKey] = entry
                    
                    Log.d(TAG, "Dictionary lookup successful for word: $word")
                    return@withContext entry
                } else {
                    // Handle errors
                    if (result.errors != null && result.errors!!.isNotEmpty()) {
                        val errorMsg = result.errors!!.joinToString(", ") { it.message }
                        Log.w(TAG, "Dictionary lookup errors for word: $word - $errorMsg")
                        return@withContext createBasicEntry(word, language, "查询错误: $errorMsg")
                    }
                    if (result.exception != null) {
                        Log.e(TAG, "Dictionary lookup exception for word: $word", result.exception)
                        return@withContext createBasicEntry(word, language, "查词失败: ${result.exception!!.message}")
                    }
                    Log.w(TAG, "Dictionary lookup returned no data for word: $word")
                    return@withContext createBasicEntry(word, language, "未找到释义")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Dictionary lookup failed for word: $word", e)
                return@withContext createBasicEntry(word, language, "查词失败: ${e.message}")
            }
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

