package com.github.damontecres.stashapp.subtitle

/**
 * Data models for enhanced subtitle functionality
 */

data class SubtitleCue(
    val startTime: Double, // in seconds
    val endTime: Double,   // in seconds
    val text: String
)

data class WordSegment(
    val word: String,
    val startIndex: Int,
    val endIndex: Int,
    val isSelected: Boolean = false
)

data class DictionaryDefinition(
    val partOfSpeech: String,
    val meaning: String,
    val examples: List<String> = emptyList()
)

data class DictionaryEntry(
    val word: String,
    val pronunciation: String? = null,
    val definitions: List<DictionaryDefinition>,
    val etymology: String? = null,
    val frequency: Int? = null
)

data class FavoriteWord(
    val word: String,
    val language: String
)

