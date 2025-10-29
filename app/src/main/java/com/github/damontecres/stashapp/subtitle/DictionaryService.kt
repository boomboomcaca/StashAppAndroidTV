package com.github.damontecres.stashapp.subtitle

import android.util.Log
import com.apollographql.apollo.ApolloClient
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
                
                // TODO: Uncomment when GraphQL types are generated
                // This requires the OllamaExplainWord.graphql to be processed and types generated
                // For now, return a basic entry indicating dictionary lookup is not available
                // 
                // val input = OllamaExplainWordInput(
                //     word = word,
                //     context = context,
                //     language = language
                // )
                // 
                // val result = apolloClient.mutation(OllamaExplainWordMutation(input))
                //     .execute()
                // 
                // val response = result.data?.ollamaExplainWord
                // if (response != null) {
                //     val entry = DictionaryEntry(
                //         word = response.word,
                //         pronunciation = response.pronunciation,
                //         definitions = response.definitions.map { def ->
                //             DictionaryDefinition(
                //                 partOfSpeech = def.partOfSpeech ?: "unknown",
                //                 meaning = def.meaning,
                //                 examples = def.examples?.filterNotNull() ?: emptyList()
                //             )
                //         },
                //         etymology = response.etymology
                //     )
                //     
                //     if (cache.size >= MAX_CACHE_SIZE) {
                //         val firstKey = cache.keys.firstOrNull()
                //         if (firstKey != null) {
                //             cache.remove(firstKey)
                //         }
                //     }
                //     cache[cacheKey] = entry
                //     
                //     return@withContext entry
                // }
                
                // Temporary: Return basic entry until GraphQL types are available
                Log.d(TAG, "Dictionary lookup temporarily disabled - GraphQL types not generated yet")
                return@withContext createBasicEntry(word, language, "词典查询功能待启用（需要重新编译以生成 GraphQL 类型）")
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

