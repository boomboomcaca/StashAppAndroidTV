package com.github.damontecres.stashapp.subtitle

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apollographql.apollo.ApolloClient
import com.github.damontecres.stashapp.util.StashClient
import com.github.damontecres.stashapp.util.StashServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for enhanced subtitle functionality
 */
class EnhancedSubtitleViewModel(application: Application) : AndroidViewModel(application) {
    private val context = getApplication<Application>()
    
    private var apolloClient: ApolloClient? = null
    private var dictionaryService: DictionaryService? = null
    private var pronunciationService: PronunciationService? = null
    private var favoritesService: FavoritesService? = null
    private var apiKey: String? = null
    private var serverUrl: String? = null

    // In-flight jobs so they can be cancelled/superseded
    private var lookupJob: Job? = null
    private var segmentJob: Job? = null
    
    // Subtitle state
    private val _subtitles = MutableStateFlow<List<SubtitleCue>>(emptyList())
    val subtitles: StateFlow<List<SubtitleCue>> = _subtitles.asStateFlow()
    
    private val _currentCue = MutableStateFlow<SubtitleCue?>(null)
    val currentCue: StateFlow<SubtitleCue?> = _currentCue.asStateFlow()
    
    // Loading and error state
    private val _isLoadingSubtitles = MutableStateFlow(false)
    val isLoadingSubtitles: StateFlow<Boolean> = _isLoadingSubtitles.asStateFlow()
    
    private val _subtitleLoadError = MutableStateFlow<String?>(null)
    val subtitleLoadError: StateFlow<String?> = _subtitleLoadError.asStateFlow()
    
    private val _currentSubtitleUrl = MutableStateFlow<String?>(null)
    val currentSubtitleUrl: StateFlow<String?> = _currentSubtitleUrl.asStateFlow()
    
    private val _wordSegments = MutableStateFlow<List<WordSegment>>(emptyList())
    val wordSegments: StateFlow<List<WordSegment>> = _wordSegments.asStateFlow()
    
    private val _detectedLanguage = MutableStateFlow<String>("en")
    val detectedLanguage: StateFlow<String> = _detectedLanguage.asStateFlow()
    
    // Dictionary state
    private val _selectedWord = MutableStateFlow<String?>(null)
    val selectedWord: StateFlow<String?> = _selectedWord.asStateFlow()
    
    private val _dictionaryEntry = MutableStateFlow<DictionaryEntry?>(null)
    val dictionaryEntry: StateFlow<DictionaryEntry?> = _dictionaryEntry.asStateFlow()
    
    private val _isLoadingDictionary = MutableStateFlow(false)
    val isLoadingDictionary: StateFlow<Boolean> = _isLoadingDictionary.asStateFlow()
    
    // UI state
    private val _isVisible = MutableStateFlow(false)
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()
    
    private val _fontSize = MutableStateFlow(1.0f)
    val fontSize: StateFlow<Float> = _fontSize.asStateFlow()
    
    private val _subtitlePosition = MutableStateFlow(0f)
    val subtitlePosition: StateFlow<Float> = _subtitlePosition.asStateFlow()
    
    private val _autoPauseEnabled = MutableStateFlow(false)
    val autoPauseEnabled: StateFlow<Boolean> = _autoPauseEnabled.asStateFlow()
    
    private val _selectedAiProvider = MutableStateFlow("mistral")
    val selectedAiProvider: StateFlow<String> = _selectedAiProvider.asStateFlow()
    
    private val _targetLanguage = MutableStateFlow("zh")
    val targetLanguage: StateFlow<String> = _targetLanguage.asStateFlow()
    
    // Track if currently auto-paused
    private val _isAutoPaused = MutableStateFlow(false)
    val isAutoPaused: StateFlow<Boolean> = _isAutoPaused.asStateFlow()
    
    // Track if auto-pause was triggered and user resumed playback (same as Web端 userResumedPlaybackRef)
    private var _autoPauseTriggered = false
    private var _userResumedPlayback = false
    
    // Word navigation state
    private val _selectedWordIndex = MutableStateFlow(-1)
    val selectedWordIndex: StateFlow<Int> = _selectedWordIndex.asStateFlow()
    
    private val _isInWordNavigationMode = MutableStateFlow(false)
    val isInWordNavigationMode: StateFlow<Boolean> = _isInWordNavigationMode.asStateFlow()
    
    private var subtitleCache = mutableMapOf<String, List<SubtitleCue>>()
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "enhanced_subtitles",
        android.content.Context.MODE_PRIVATE
    )
    
    // Favorites state
    private val _favorites = MutableStateFlow<List<FavoriteWord>>(emptyList())
    val favorites: StateFlow<List<FavoriteWord>> = _favorites.asStateFlow()

    init {
        loadPreferences()
        initializeServices()
    }
    
    private fun initializeServices() {
        pronunciationService = PronunciationService.getInstance(context)
        favoritesService = FavoritesService(context)
        // Observe favorites so UI can react to changes
        favoritesService?.let { service ->
            viewModelScope.launch {
                service.getFavorites().collect { list ->
                    _favorites.value = list
                }
            }
        }
    }
    
    fun setServer(server: StashServer?) {
        // Set synchronously so a subtitle load / word lookup triggered right after
        // this call cannot race ahead of an unset apiKey/dictionaryService.
        serverUrl = server?.url
        apiKey = server?.apiKey
        apolloClient = try {
            server?.apolloClient
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get apollo client", e)
            null
        }
        dictionaryService = DictionaryService(apolloClient)
        // Set server for pronunciation service
        pronunciationService?.setServer(server)
    }
    
    private fun loadPreferences() {
        try {
            _isEnabled.value = prefs.getBoolean("enabled", false)
            _fontSize.value = prefs.getFloat("fontSize", 1.0f).coerceIn(0.5f, 3.0f)
            _subtitlePosition.value = prefs.getFloat("position", 0f).coerceIn(-1f, 1f)
            _autoPauseEnabled.value = prefs.getBoolean("autoPause", false)
            val provider = prefs.getString("aiProvider", "mistral") ?: "mistral"
            _selectedAiProvider.value = if (provider in VALID_PROVIDERS) provider else "mistral"
            val lang = prefs.getString("targetLanguage", "zh") ?: "zh"
            _targetLanguage.value = if (lang in VALID_TARGET_LANGUAGES) lang else "zh"
        } catch (e: Exception) {
            // Defend against type-mismatched legacy preference keys (ClassCastException)
            Log.w(TAG, "Failed to load subtitle preferences, using defaults", e)
        }
    }
    
    private fun savePreferences() {
        prefs.edit()
            .putBoolean("enabled", _isEnabled.value)
            .putFloat("fontSize", _fontSize.value)
            .putFloat("position", _subtitlePosition.value)
            .putBoolean("autoPause", _autoPauseEnabled.value)
            .putString("aiProvider", _selectedAiProvider.value)
            .putString("targetLanguage", _targetLanguage.value)
            .apply()
    }
    
    /**
     * Load subtitles from VTT/SRT URL
     */
    fun loadSubtitles(vttUrl: String?) {
        _currentSubtitleUrl.value = vttUrl
        
        if (vttUrl == null) {
            _subtitles.value = emptyList()
            _currentCue.value = null
            _isLoadingSubtitles.value = false
            _subtitleLoadError.value = "未找到字幕文件"
            return
        }
        
        _isLoadingSubtitles.value = true
        _subtitleLoadError.value = null
        
        viewModelScope.launch {
            // Check cache
            subtitleCache[vttUrl]?.let {
                _subtitles.value = it
                _isLoadingSubtitles.value = false
                return@launch
            }
            
            VttParser.loadVttFromUrl(vttUrl, apiKey, serverUrl).fold(
                onSuccess = { cues ->
                    if (subtitleCache.size >= MAX_SUBTITLE_CACHE) {
                        subtitleCache.keys.firstOrNull()?.let { subtitleCache.remove(it) }
                    }
                    subtitleCache[vttUrl] = cues
                    _subtitles.value = cues
                    _isLoadingSubtitles.value = false
                    _subtitleLoadError.value = null
                },
                onFailure = { error ->
                    _subtitles.value = emptyList()
                    _isLoadingSubtitles.value = false
                    _subtitleLoadError.value = "加载字幕失败: ${error.message}"
                }
            )
        }
    }
    
    /**
     * Update current playback time and find corresponding cue
     */
    fun updatePlaybackTime(currentTimeSeconds: Double) {
        val cues = _subtitles.value
        if (cues.isEmpty()) {
            _currentCue.value = null
            return
        }
        
        val cue = VttParser.getCurrentCue(cues, currentTimeSeconds)
        
        if (cue != _currentCue.value) {
            _currentCue.value = cue
            // Reset auto-pause state when cue changes (same as Web端)
            resetAutoPauseState()
            
            // Segment the text when cue changes
            cue?.let { segmentCueText(it) }
        }
    }
    
    private fun segmentCueText(cue: SubtitleCue) {
        // Run detection + segmentation off the main thread; only publish if this cue
        // is still the current one (guards against a newer cue superseding it).
        segmentJob?.cancel()
        segmentJob = viewModelScope.launch {
            val detected = WordSegmenter.detectLanguage(cue.text)
            val segments = withContext(Dispatchers.Default) {
                WordSegmenter.create(detected).segmentText(cue.text)
            }
            if (isActive && _currentCue.value == cue) {
                _detectedLanguage.value = detected
                _wordSegments.value = segments
                _selectedWordIndex.value = -1
            }
        }
    }
    
    /**
     * Set AI provider
     */
    fun setAiProvider(provider: String) {
        if (_selectedAiProvider.value != provider) {
            _selectedAiProvider.value = provider
            savePreferences()
            
            // If a word is currently selected, re-fetch with new provider
            _selectedWord.value?.let { word ->
                selectWord(word)
            }
        }
    }
    
    /**
     * Set target language for dictionary definitions (en/zh toggle)
     */
    fun setTargetLanguage(language: String) {
        if (_targetLanguage.value != language) {
            _targetLanguage.value = language
            savePreferences()
            
            // If a word is currently selected, re-fetch with new language
            _selectedWord.value?.let { word ->
                selectWord(word)
            }
        }
    }
    
    /**
     * Handle word click/selection
     */
    fun selectWord(word: String?) {
        Log.d(TAG, "selectWord: word='$word', currentSelectedWord=${_selectedWord.value}")
        // Cancel any in-flight lookup so a slower previous request can't overwrite this one.
        lookupJob?.cancel()
        _selectedWord.value = word
        if (word == null) {
            _dictionaryEntry.value = null
            _isLoadingDictionary.value = false
            return
        }

        _isLoadingDictionary.value = true
        lookupJob = viewModelScope.launch {
            val context = _currentCue.value?.text ?: ""
            val language = _detectedLanguage.value
            val targetLang = _targetLanguage.value
            val provider = _selectedAiProvider.value

            // Preload pronunciation audio file in background when dictionary dialog opens
            pronunciationService?.let { service ->
                launch {
                    service.preloadPronunciation(word, language)
                        .onFailure { error ->
                            Log.w(TAG, "Failed to preload pronunciation for word: '$word'", error)
                        }
                }
            }

            val entry = dictionaryService?.lookup(word, targetLang, context, provider)
            // Only publish if this is still the selected word and we weren't cancelled.
            if (isActive && _selectedWord.value == word) {
                _dictionaryEntry.value = entry
                _isLoadingDictionary.value = false
                Log.d(TAG, "selectWord: lookup completed, entry=${entry != null}")
            }
        }
    }
    
    /**
     * Play pronunciation for selected word
     */
    fun playPronunciation(word: String?) {
        if (word == null) return
        
        viewModelScope.launch {
            pronunciationService?.playPronunciation(word, _detectedLanguage.value)
                ?.onFailure { error ->
                    Log.e(TAG, "Failed to play pronunciation", error)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "朗读失败: ${error.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }
    
    /**
     * Toggle favorite for selected word
     */
    fun toggleFavorite(word: String?) {
        if (word == null) return
        
        viewModelScope.launch {
            val language = _detectedLanguage.value
            val isFavorite = favoritesService?.isFavorite(word, language) ?: false
            
            if (isFavorite) {
                favoritesService?.removeFavorite(word, language)
            } else {
                favoritesService?.addFavorite(word, language)
            }
        }
    }
    
    fun isFavorite(word: String): Boolean {
        val language = _detectedLanguage.value
        return _favorites.value.any { it.word == word && it.language == language }
    }
    
    /**
     * Toggle visibility
     */
    fun toggleVisibility() {
        _isVisible.value = !_isVisible.value
    }

    fun toggleEnabled() {
        _isEnabled.value = !_isEnabled.value
        savePreferences()
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        savePreferences()
    }
    
    fun setVisible(visible: Boolean) {
        _isVisible.value = visible
    }
    
    /**
     * Adjust font size
     */
    fun adjustFontSize(delta: Float) {
        val newSize = (_fontSize.value + delta).coerceIn(0.5f, 3.0f)
        _fontSize.value = newSize
        savePreferences()
    }
    
    fun setFontSize(size: Float) {
        _fontSize.value = size.coerceIn(0.5f, 3.0f)
        savePreferences()
    }
    
    /**
     * Adjust subtitle position
     */
    fun adjustPosition(delta: Float) {
        _subtitlePosition.value = (_subtitlePosition.value + delta).coerceIn(-1f, 1f)
        savePreferences()
    }

    fun setPosition(position: Float) {
        _subtitlePosition.value = position.coerceIn(-1f, 1f)
        savePreferences()
    }
    
    /**
     * Toggle auto-pause
     */
    fun toggleAutoPause() {
        _autoPauseEnabled.value = !_autoPauseEnabled.value
        if (!_autoPauseEnabled.value) {
            // When disabling auto-pause, reset the paused state
            _isAutoPaused.value = false
        }
        savePreferences()
    }
    
    /**
     * Word navigation mode
     */
    fun enterWordNavigationMode() {
        if (_wordSegments.value.isNotEmpty()) {
            _isInWordNavigationMode.value = true
            _selectedWordIndex.value = 0
        }
    }
    
    fun exitWordNavigationMode() {
        _isInWordNavigationMode.value = false
        _selectedWordIndex.value = -1
    }
    
    fun navigateToNextWord() {
        val segments = _wordSegments.value
        if (segments.isEmpty()) {
            Log.d(TAG, "navigateToNextWord: segments empty")
            return
        }
        
        val currentIndex = _selectedWordIndex.value
        
        // If no word is selected, start from the first word
        if (currentIndex < 0) {
            _isInWordNavigationMode.value = true
            _selectedWordIndex.value = 0
            Log.d(TAG, "navigateToNextWord: entering word nav mode, index=0")
        } else if (currentIndex < segments.size - 1) {
            // Move to next word
            _selectedWordIndex.value = currentIndex + 1
            Log.d(TAG, "navigateToNextWord: index ${currentIndex} -> ${currentIndex + 1}")
        } else {
            // At the end, cycle to the first word
            _selectedWordIndex.value = 0
            Log.d(TAG, "navigateToNextWord: cycling to start, index -> 0")
        }
    }
    
    fun navigateToPreviousWord() {
        val segments = _wordSegments.value
        if (segments.isEmpty()) {
            Log.d(TAG, "navigateToPreviousWord: segments empty")
            return
        }
        
        val currentIndex = _selectedWordIndex.value
        
        // If no word is selected, start from the last word
        if (currentIndex < 0) {
            _isInWordNavigationMode.value = true
            _selectedWordIndex.value = segments.size - 1
            Log.d(TAG, "navigateToPreviousWord: entering word nav mode, index=${segments.size - 1}")
        } else if (currentIndex > 0) {
            // Move to previous word
            _selectedWordIndex.value = currentIndex - 1
            Log.d(TAG, "navigateToPreviousWord: index ${currentIndex} -> ${currentIndex - 1}")
        } else {
            // At the beginning, cycle to the last word
            _selectedWordIndex.value = segments.size - 1
            Log.d(TAG, "navigateToPreviousWord: cycling to end, index -> ${segments.size - 1}")
        }
    }
    
    fun selectCurrentWord() {
        val segments = _wordSegments.value
        val index = _selectedWordIndex.value
        Log.d(TAG, "selectCurrentWord: index=$index, segments.size=${segments.size}, isInWordNavMode=${_isInWordNavigationMode.value}")
        if (index >= 0 && index < segments.size) {
            val word = segments[index].word
            Log.d(TAG, "selectCurrentWord: selecting word='$word' at index=$index")
            selectWord(word)
        } else {
            Log.w(TAG, "selectCurrentWord: Invalid index=$index or segments empty (size=${segments.size})")
        }
    }
    
    /**
     * Get current cue index based on playback time
     */
    fun getCurrentCueIndex(currentTimeSeconds: Double): Int {
        val cues = _subtitles.value
        if (cues.isEmpty()) return -1

        // Cues are sorted by start time; binary search for the last cue that has
        // started at/before the current time (the active cue, or the nearest previous
        // one when between cues). Before the first cue, anchor to index 0.
        var lo = 0
        var hi = cues.size - 1
        var candidate = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startTime <= currentTimeSeconds) {
                candidate = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return if (candidate >= 0) candidate else 0
    }
    
    /**
     * Seek to previous subtitle cue
     */
    fun seekToPreviousCue(currentTimeSeconds: Double): Double? {
        val cues = _subtitles.value
        if (cues.isEmpty()) return null
        
        val currentIndex = getCurrentCueIndex(currentTimeSeconds)
        if (currentIndex > 0) {
            return cues[currentIndex - 1].startTime
        }
        return null
    }
    
    /**
     * Seek to next subtitle cue
     */
    fun seekToNextCue(currentTimeSeconds: Double): Double? {
        val cues = _subtitles.value
        if (cues.isEmpty()) return null
        
        val currentIndex = getCurrentCueIndex(currentTimeSeconds)
        if (currentIndex < cues.size - 1) {
            return cues[currentIndex + 1].startTime
        }
        return null
    }
    
    /**
     * Seek to current subtitle start time (repeat current subtitle)
     */
    fun seekToCurrentCueStart(currentTimeSeconds: Double): Double? {
        val cues = _subtitles.value
        if (cues.isEmpty()) return null
        
        val currentIndex = getCurrentCueIndex(currentTimeSeconds)
        if (currentIndex >= 0 && currentIndex < cues.size) {
            return cues[currentIndex].startTime
        }
        return null
    }
    
    /**
     * Check if auto-pause should trigger (returns true if should pause)
     * Auto-pause triggers if: manual auto-pause enabled OR in word navigation mode
     * (Same logic as Web端)
     */
    fun checkAutoPause(currentTimeSeconds: Double): Boolean {
        // Auto-pause if: manual auto-pause enabled OR in word navigation mode
        val isInWordMode = _isInWordNavigationMode.value
        val shouldRunAutoPauseLogic = _autoPauseEnabled.value || isInWordMode
        
        if (!shouldRunAutoPauseLogic) {
            _isAutoPaused.value = false
            return false
        }
        
        val cue = _currentCue.value ?: run {
            _isAutoPaused.value = false
            return false
        }
        val timeUntilEnd = cue.endTime - currentTimeSeconds
        
        // If user already resumed playback after auto-pause, don't pause again for this cue
        // (Same logic as Web端 userResumedPlaybackRef)
        if (_autoPauseTriggered && _userResumedPlayback) {
            return false
        }

        // If time has passed the pause threshold or subtitle end, reset paused state
        if (timeUntilEnd <= 0 || currentTimeSeconds >= cue.endTime) {
            _isAutoPaused.value = false
            return false
        }
        
        // Pause 0.1 seconds before subtitle ends
        val shouldPause = timeUntilEnd <= 0.2 && timeUntilEnd > 0
        
        // Update paused state
        if (shouldPause) {
            if (!_isAutoPaused.value && !_autoPauseTriggered) {
                _autoPauseTriggered = true
                _isAutoPaused.value = true
            }
        }
        
        return shouldPause && !_userResumedPlayback
    }

    /**
     * Call when user explicitly resumes playback (e.g., presses OK/Play).
     * Same logic as Web端 userResumedPlaybackRef.
     */
    fun notifyUserResumed() {
        if (_autoPauseTriggered && _isAutoPaused.value) {
            _userResumedPlayback = true
            _isAutoPaused.value = false
        }
    }
    
    /**
     * Reset auto-pause state when cue changes (same as Web端)
     */
    private fun resetAutoPauseState() {
        _autoPauseTriggered = false
        _userResumedPlayback = false
        _isAutoPaused.value = false
    }
    
    /**
     * Reset auto-pause state for replay current subtitle.
     * When replaying the same cue, cue doesn't change so resetAutoPauseState() won't be called
     * automatically. This method allows manual reset to enable auto-pause for the replayed subtitle.
     */
    fun resetAutoPauseForReplay() {
        _autoPauseTriggered = false
        _userResumedPlayback = false
        _isAutoPaused.value = false
    }
    
    override fun onCleared() {
        super.onCleared()
        lookupJob?.cancel()
        segmentJob?.cancel()
        // PronunciationService is a process-wide singleton shared across screens, so
        // do NOT shut it down here (that would wipe its cache/temp files for everyone).
        // Just stop any audio this screen started playing.
        pronunciationService?.stop()
    }

    companion object {
        private const val TAG = "EnhancedSubtitleVM"
        private const val MAX_SUBTITLE_CACHE = 20
        private val VALID_PROVIDERS = setOf("mistral", "ollama")
        private val VALID_TARGET_LANGUAGES = setOf("en", "zh")
    }
}

