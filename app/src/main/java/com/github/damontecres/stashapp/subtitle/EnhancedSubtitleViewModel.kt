package com.github.damontecres.stashapp.subtitle

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apollographql.apollo.ApolloClient
import com.github.damontecres.stashapp.util.StashClient
import com.github.damontecres.stashapp.util.StashServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    
    private val _fontSize = MutableStateFlow(1.0f)
    val fontSize: StateFlow<Float> = _fontSize.asStateFlow()
    
    private val _subtitlePosition = MutableStateFlow(0f)
    val subtitlePosition: StateFlow<Float> = _subtitlePosition.asStateFlow()
    
    private val _autoPauseEnabled = MutableStateFlow(false)
    val autoPauseEnabled: StateFlow<Boolean> = _autoPauseEnabled.asStateFlow()
    
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
    
    private var segmenter: WordSegmenter? = null
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
        viewModelScope.launch {
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
    }
    
    private fun loadPreferences() {
        _fontSize.value = prefs.getFloat("fontSize", 1.0f)
        _subtitlePosition.value = prefs.getFloat("position", 0f)
        _autoPauseEnabled.value = prefs.getBoolean("autoPause", false)
    }
    
    private fun savePreferences() {
        prefs.edit()
            .putFloat("fontSize", _fontSize.value)
            .putFloat("position", _subtitlePosition.value)
            .putBoolean("autoPause", _autoPauseEnabled.value)
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
            
            VttParser.loadVttFromUrl(vttUrl, apiKey).fold(
                onSuccess = { cues ->
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
        val detected = WordSegmenter.detectLanguage(cue.text)
        if (detected != _detectedLanguage.value) {
            _detectedLanguage.value = detected
            segmenter = WordSegmenter.create(detected)
        }
        
        val currentSegmenter = segmenter ?: WordSegmenter.create(_detectedLanguage.value)
        val segments = currentSegmenter.segmentText(cue.text)
        
        _wordSegments.value = segments
        _selectedWordIndex.value = -1
    }
    
    /**
     * Handle word click/selection
     */
    fun selectWord(word: String?) {
        Log.d(TAG, "selectWord: word='$word', currentSelectedWord=${_selectedWord.value}")
        viewModelScope.launch {
            _selectedWord.value = word
            Log.d(TAG, "selectWord: _selectedWord set to '$word'")
            if (word == null) {
                _dictionaryEntry.value = null
                _isLoadingDictionary.value = false
                Log.d(TAG, "selectWord: word is null, clearing dictionary")
                return@launch
            }
            
            _isLoadingDictionary.value = true
            Log.d(TAG, "selectWord: looking up word='$word'")
            
            val context = _currentCue.value?.text ?: ""
            val language = _detectedLanguage.value
            
            // Preload pronunciation audio file in background when dictionary dialog opens
            pronunciationService?.let { service ->
                launch {
                    service.preloadPronunciation(word, language)
                        .onFailure { error ->
                            Log.w(TAG, "Failed to preload pronunciation for word: '$word'", error)
                        }
                }
            }
            
            val entry = dictionaryService?.lookup(word, language, context)
            _dictionaryEntry.value = entry
            _isLoadingDictionary.value = false
            Log.d(TAG, "selectWord: lookup completed, entry=${entry != null}, isLoading=false")
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

        var lastBefore = -1

        // Find current cue or track the nearest previous cue
        for (i in cues.indices) {
            val cue = cues[i]
            when {
                currentTimeSeconds >= cue.startTime && currentTimeSeconds <= cue.endTime -> {
                    return i
                }
                currentTimeSeconds > cue.endTime -> {
                    lastBefore = i
                }
                currentTimeSeconds < cue.startTime -> {
                    // Between cues: return the most recent previous cue (if any), otherwise the first
                    return if (lastBefore >= 0) lastBefore else 0
                }
            }
        }

        // Past all cues: return the last cue
        return cues.size - 1
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
        pronunciationService?.shutdown()
    }
    
    companion object {
        private const val TAG = "EnhancedSubtitleVM"
    }
}

