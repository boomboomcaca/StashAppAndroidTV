package com.github.damontecres.stashapp.subtitle

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Service for managing favorite words
 * Uses SharedPreferences for local storage
 */
class FavoritesService(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "favorite_words",
        Context.MODE_PRIVATE
    )
    
    private val json = Json { ignoreUnknownKeys = true }
    
    private val favoritesFlow = MutableStateFlow<List<FavoriteWord>>(emptyList())
    
    init {
        loadFavorites()
    }
    
    /**
     * Get all favorite words
     */
    fun getFavorites(): Flow<List<FavoriteWord>> = favoritesFlow.asStateFlow()
    
    /**
     * Get all favorite words as list
     */
    fun getFavoritesList(): List<FavoriteWord> = favoritesFlow.value
    
    /**
     * Check if a word is favorite
     */
    fun isFavorite(word: String, language: String): Boolean {
        val key = getCacheKey(word, language)
        return favoritesFlow.value.any { getCacheKey(it.word, it.language) == key }
    }
    
    /**
     * Add a word to favorites
     */
    suspend fun addFavorite(word: String, language: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val favorite = FavoriteWordSerializable(word, language)
                val current = favoritesFlow.value.map { it.toSerializable() }.toMutableList()
                
                val key = getCacheKey(word, language)
                if (!current.any { getCacheKey(it.word, it.language) == key }) {
                    current.add(favorite)
                    val updated = current.map { it.toFavorite() }
                    favoritesFlow.value = updated
                    saveFavorites(updated)
                    true
                } else {
                    false // Already exists
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add favorite", e)
                false
            }
        }
    }
    
    /**
     * Remove a word from favorites
     */
    suspend fun removeFavorite(word: String, language: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val key = getCacheKey(word, language)
                val current = favoritesFlow.value.map { it.toSerializable() }.toMutableList()
                val removed = current.removeAll { getCacheKey(it.word, it.language) == key }
                
                if (removed) {
                    val updated = current.map { it.toFavorite() }
                    favoritesFlow.value = updated
                    saveFavorites(updated)
                }
                removed
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove favorite", e)
                false
            }
        }
    }
    
    private fun getCacheKey(word: String, language: String): String {
        return "$word:$language"
    }
    
    private fun loadFavorites() {
        try {
            val jsonStr = prefs.getString("favorites", "[]")
            if (jsonStr != null) {
                val serialized = json.decodeFromString<List<FavoriteWordSerializable>>(jsonStr)
                favoritesFlow.value = serialized.map { it.toFavorite() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load favorites", e)
            favoritesFlow.value = emptyList()
        }
    }
    
    private fun saveFavorites(favorites: List<FavoriteWord>) {
        try {
            val serialized = favorites.map { it.toSerializable() }
            val jsonStr = json.encodeToString(serialized)
            prefs.edit().putString("favorites", jsonStr).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save favorites", e)
        }
    }
    
    companion object {
        private const val TAG = "FavoritesService"
    }
}

// For JSON serialization
@Serializable
data class FavoriteWordSerializable(
    val word: String,
    val language: String
)

fun FavoriteWord.toSerializable() = FavoriteWordSerializable(word, language)
fun FavoriteWordSerializable.toFavorite() = FavoriteWord(word, language)

