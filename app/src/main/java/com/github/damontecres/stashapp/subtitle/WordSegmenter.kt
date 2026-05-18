package com.github.damontecres.stashapp.subtitle

/**
 * Word segmentation for different languages
 */
class WordSegmenter(
    private val language: String = "en",
    private val enablePunctuation: Boolean = false,
    private val minWordLength: Int = 1
) {
    /**
     * Segment text into words based on language
     */
    fun segmentText(text: String): List<WordSegment> {
        return when (language) {
            "zh", "zh-CN", "zh-TW" -> segmentChinese(text)
            "ja" -> segmentJapanese(text)
            "ko" -> segmentKorean(text)
            else -> segmentLatin(text)
        }
    }
    
    /**
     * Latin-based languages (English, Spanish, French, etc.)
     */
    private fun segmentLatin(text: String): List<WordSegment> {
        val segments = mutableListOf<WordSegment>()
        val wordRegex = Regex("""\b\w+\b""")
        
        wordRegex.findAll(text).forEach { matchResult ->
            val word = matchResult.value
            if (word.length >= minWordLength) {
                segments.add(
                    WordSegment(
                        word = word,
                        startIndex = matchResult.range.first,
                        endIndex = matchResult.range.last + 1
                    )
                )
            }
        }
        
        // Add punctuation if enabled
        if (enablePunctuation) {
            val punctRegex = Regex("""[.,;:!?'"()[\]{}]""")
            punctRegex.findAll(text).forEach { matchResult ->
                segments.add(
                    WordSegment(
                        word = matchResult.value,
                        startIndex = matchResult.range.first,
                        endIndex = matchResult.range.last + 1
                    )
                )
            }
        }
        
        return segments.sortedBy { it.startIndex }
    }
    
    /**
     * Chinese segmentation (simplified approach - treats each character as a word)
     */
    private fun segmentChinese(text: String): List<WordSegment> {
        val segments = mutableListOf<WordSegment>()
        
        text.forEachIndexed { index, char ->
            if (char.isWhitespace()) return@forEachIndexed
            
            val isPunctuation = Regex("""[，。！？；：""''（）【】《》]""").matches(char.toString())
            if (isPunctuation && !enablePunctuation) return@forEachIndexed
            
            // Check if it's a Chinese character or punctuation
            if (Regex("""[\u4e00-\u9fff]|[，。！？；：""''（）【】《》]""").matches(char.toString())) {
                segments.add(
                    WordSegment(
                        word = char.toString(),
                        startIndex = index,
                        endIndex = index + 1
                    )
                )
            }
        }
        
        return segments
    }
    
    /**
     * Japanese segmentation (simplified approach)
     */
    private fun segmentJapanese(text: String): List<WordSegment> {
        val segments = mutableListOf<WordSegment>()
        
        text.forEachIndexed { index, char ->
            if (char.isWhitespace()) return@forEachIndexed
            
            val isPunctuation = Regex("""[、。！？；：「」『』（）]""").matches(char.toString())
            if (isPunctuation && !enablePunctuation) return@forEachIndexed
            
            // Check if it's a Japanese character
            if (Regex("""[\u3040-\u309f\u30a0-\u30ff\u4e00-\u9fff]|[、。！？；：「」『』（）]""").matches(char.toString())) {
                segments.add(
                    WordSegment(
                        word = char.toString(),
                        startIndex = index,
                        endIndex = index + 1
                    )
                )
            }
        }
        
        return segments
    }
    
    /**
     * Korean segmentation (space-based)
     */
    private fun segmentKorean(text: String): List<WordSegment> {
        val segments = mutableListOf<WordSegment>()
        val words = text.split(Regex("""\s+"""))
        var currentIndex = 0
        
        words.forEach { word ->
            val trimmed = word.trim()
            if (trimmed.isNotEmpty() && trimmed.length >= minWordLength) {
                // Check if it contains Korean characters
                if (Regex("""[\uac00-\ud7af]""").containsMatchIn(trimmed)) {
                    val wordStartIndex = text.indexOf(trimmed, currentIndex)
                    segments.add(
                        WordSegment(
                            word = trimmed,
                            startIndex = wordStartIndex,
                            endIndex = wordStartIndex + trimmed.length
                        )
                    )
                }
            }
            currentIndex += word.length
        }
        
        return segments
    }
    
    companion object {
        /**
         * Detect language from text.
         *
         * For bilingual subtitles (e.g. English + Chinese in the same cue) we
         * prefer the Latin language so that word segmentation/selection still
         * works on the English line. The Chinese line is rendered separately
         * as plain text by the overlay UI.
         */
        fun detectLanguage(text: String): String {
            val hasCjk = Regex("""[\u4e00-\u9fff]""").containsMatchIn(text)
            val hasLatin = Regex("""[A-Za-z]""").containsMatchIn(text)

            // Bilingual: pick the Latin language so segmentLatin runs on the cue.
            if (hasCjk && hasLatin) {
                return when {
                    Regex("""[àáâãäåæçèéêëìíîïðñòóôõöøùúûüýþÿ]""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "fr"
                    Regex("""[äöüß]""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "de"
                    Regex("""[ñ¿¡]""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "es"
                    else -> "en"
                }
            }

            return when {
                hasCjk -> "zh"
                Regex("""[\u3040-\u309f\u30a0-\u30ff]""").containsMatchIn(text) -> "ja"
                Regex("""[\uac00-\ud7af]""").containsMatchIn(text) -> "ko"
                Regex("""[а-яё]""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "ru"
                Regex("""[àáâãäåæçèéêëìíîïðñòóôõöøùúûüýþÿ]""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "fr"
                Regex("""[äöüß]""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "de"
                Regex("""[ñ¿¡]""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "es"
                else -> "en"
            }
        }
        
        /**
         * Create a segmenter with default options
         */
        fun create(
            language: String = "en",
            enablePunctuation: Boolean = false,
            minWordLength: Int = 1
        ): WordSegmenter {
            return WordSegmenter(language, enablePunctuation, minWordLength)
        }
    }
}

