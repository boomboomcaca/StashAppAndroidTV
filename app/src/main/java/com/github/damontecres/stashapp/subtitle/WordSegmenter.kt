package com.github.damontecres.stashapp.subtitle

import java.text.BreakIterator
import java.util.Locale

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
            "zh", "zh-CN" -> segmentWithBreakIterator(text, Locale.CHINA)
            "zh-TW" -> segmentWithBreakIterator(text, Locale.TAIWAN)
            "ja" -> segmentWithBreakIterator(text, Locale.JAPANESE)
            "ko" -> segmentWithBreakIterator(text, Locale.KOREAN)
            else -> segmentLatin(text)
        }
    }

    /**
     * Latin-based languages (English, Spanish, French, etc.)
     *
     * Uses a Unicode-aware letter class so accented characters (é, ñ, ü, ß …) that
     * [detectLanguage] explicitly routes here for fr/de/es are kept intact, and
     * allows internal apostrophes (straight ' and typographic ’) and hyphens so
     * contractions ("don't", "don’t") and compound words ("well-known") stay as a
     * single selectable token.
     */
    private fun segmentLatin(text: String): List<WordSegment> {
        val segments = mutableListOf<WordSegment>()

        LATIN_WORD_REGEX.findAll(text).forEach { matchResult ->
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
            LATIN_PUNCT_REGEX.findAll(text).forEach { matchResult ->
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
     * CJK / locale-aware segmentation using ICU's dictionary-backed [BreakIterator]
     * (Chinese, Japanese, Korean). This groups multi-character words instead of
     * emitting one segment per glyph, so dictionary lookups and word navigation
     * operate on real words. The reported indices come straight from the iterator,
     * so they always line up with the source text (no manual offset bookkeeping).
     */
    private fun segmentWithBreakIterator(text: String, locale: Locale): List<WordSegment> {
        if (text.isEmpty()) return emptyList()

        val segments = mutableListOf<WordSegment>()
        val iterator = BreakIterator.getWordInstance(locale)
        iterator.setText(text)

        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val word = text.substring(start, end)
            val hasContent = word.any { it.isLetterOrDigit() }
            if (word.isNotBlank() && (hasContent || enablePunctuation) && word.length >= minWordLength) {
                segments.add(
                    WordSegment(
                        word = word,
                        startIndex = start,
                        endIndex = end
                    )
                )
            }
            start = end
            end = iterator.next()
        }

        return segments
    }

    companion object {
        /** Unicode letters, allowing internal apostrophes (straight/typographic) and hyphens. */
        private val LATIN_WORD_REGEX = Regex("""\p{L}+(?:['’\-]\p{L}+)*""")
        private val LATIN_PUNCT_REGEX = Regex("""[.,;:!?'"()\[\]{}]""")
        private val CJK_REGEX = Regex("""[一-鿿]""")
        private val LATIN_LETTER_REGEX = Regex("""[A-Za-z]""")
        private val KANA_REGEX = Regex("""[぀-ゟ゠-ヿ]""")
        private val HANGUL_REGEX = Regex("""[가-힯]""")
        private val CYRILLIC_REGEX = Regex("""[а-яё]""", RegexOption.IGNORE_CASE)
        private val FRENCH_REGEX = Regex("""[àáâãäåæçèéêëìíîïðñòóôõöøùúûüýþÿ]""", RegexOption.IGNORE_CASE)
        private val GERMAN_REGEX = Regex("""[äöüß]""", RegexOption.IGNORE_CASE)
        private val SPANISH_REGEX = Regex("""[ñ¿¡]""", RegexOption.IGNORE_CASE)

        /**
         * Detect language from text.
         *
         * For bilingual subtitles (e.g. English + Chinese in the same cue) we
         * prefer the Latin language so that word segmentation/selection still
         * works on the English line. The Chinese line is rendered separately
         * as plain text by the overlay UI.
         */
        fun detectLanguage(text: String): String {
            val hasCjk = CJK_REGEX.containsMatchIn(text)
            val hasLatin = LATIN_LETTER_REGEX.containsMatchIn(text)

            // Bilingual: pick the Latin language so segmentLatin runs on the cue.
            if (hasCjk && hasLatin) {
                return when {
                    FRENCH_REGEX.containsMatchIn(text) -> "fr"
                    GERMAN_REGEX.containsMatchIn(text) -> "de"
                    SPANISH_REGEX.containsMatchIn(text) -> "es"
                    else -> "en"
                }
            }

            return when {
                hasCjk -> "zh"
                KANA_REGEX.containsMatchIn(text) -> "ja"
                HANGUL_REGEX.containsMatchIn(text) -> "ko"
                CYRILLIC_REGEX.containsMatchIn(text) -> "ru"
                FRENCH_REGEX.containsMatchIn(text) -> "fr"
                GERMAN_REGEX.containsMatchIn(text) -> "de"
                SPANISH_REGEX.containsMatchIn(text) -> "es"
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
