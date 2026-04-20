package com.github.damontecres.stashapp.subtitle

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import com.github.damontecres.stashapp.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Text as TvText
import com.github.damontecres.stashapp.subtitle.EnhancedSubtitleViewModel
import com.github.damontecres.stashapp.ui.compat.Button as CompatButton
import com.github.damontecres.stashapp.ui.util.handleDPadKeyEvents
import com.github.damontecres.stashapp.ui.tryRequestFocus

/**
 * Enhanced Subtitle Overlay Component
 * Displays subtitles with word-by-word interaction, dictionary lookup, and pronunciation
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EnhancedSubtitleOverlay(
    viewModel: EnhancedSubtitleViewModel = viewModel(),
    currentTimeSeconds: Double,
    subtitleUrl: String?,
    isVisible: Boolean,
    onPausePlayer: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Collect state
    val currentCue by viewModel.currentCue.collectAsState()
    val wordSegments by viewModel.wordSegments.collectAsState()
    val selectedWord by viewModel.selectedWord.collectAsState()
    val dictionaryEntry by viewModel.dictionaryEntry.collectAsState()
    val isLoadingDictionary by viewModel.isLoadingDictionary.collectAsState()
    val isInWordNavMode by viewModel.isInWordNavigationMode.collectAsState()
    val selectedWordIndex by viewModel.selectedWordIndex.collectAsState()
    val fontSize by viewModel.fontSize.collectAsState()
    val subtitlePosition by viewModel.subtitlePosition.collectAsState()
    val autoPauseEnabled by viewModel.autoPauseEnabled.collectAsState()
    val isAutoPaused by viewModel.isAutoPaused.collectAsState()
    val detectedLanguage by viewModel.detectedLanguage.collectAsState()
    val isLoadingSubtitles by viewModel.isLoadingSubtitles.collectAsState()
    val subtitleLoadError by viewModel.subtitleLoadError.collectAsState()
    val subtitles by viewModel.subtitles.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    
    // Load subtitles when URL changes
    androidx.compose.runtime.LaunchedEffect(subtitleUrl) {
        viewModel.loadSubtitles(subtitleUrl)
    }
    
    // Update playback time
    androidx.compose.runtime.LaunchedEffect(currentTimeSeconds) {
        viewModel.updatePlaybackTime(currentTimeSeconds)
        
        // Check auto-pause
        if (viewModel.checkAutoPause(currentTimeSeconds)) {
            onPausePlayer?.invoke()
        }
    }
    
    // Auto-pause when dictionary dialog opens (when a word is selected)
    androidx.compose.runtime.LaunchedEffect(selectedWord) {
        Log.d("EnhancedSubtitleOverlay", "selectedWord changed: selectedWord='$selectedWord', isLoadingDictionary=$isLoadingDictionary")
        if (selectedWord != null) {
            // Word selected - pause player to show dictionary dialog
            Log.d("EnhancedSubtitleOverlay", "Word selected, pausing player and showing dictionary dialog")
            onPausePlayer?.invoke()
        }
    }
    
    // Check visibility
    if (!isVisible) {
        return
    }
    
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        when {
            subtitleUrl == null -> {
                // No subtitle URL available
                SubtitleStatusMessage(
                    message = "当前视频没有字幕文件",
                    color = Color(0xFFFF9800) // Orange
                )
            }
            isLoadingSubtitles -> {
                // Loading subtitles
                SubtitleStatusMessage(
                    message = "正在加载字幕...",
                    showSpinner = true,
                    color = Color.White
                )
            }
            subtitleLoadError != null -> {
                // Error loading subtitles
                SubtitleStatusMessage(
                    message = subtitleLoadError ?: "加载字幕失败",
                    color = Color(0xFFF44336) // Red
                )
            }
            subtitles.isEmpty() && !isLoadingSubtitles -> {
                // Subtitles loaded but empty
                SubtitleStatusMessage(
                    message = "字幕文件为空",
                    color = Color(0xFFFF9800) // Orange
                )
            }
            currentCue == null && subtitles.isNotEmpty() -> {
                // Subtitles loaded but no current cue (waiting for playback time)
                // Show a subtle indicator or just wait silently
                // The overlay is transparent but active
            }
            else -> {
                // Main subtitle display
                currentCue?.let { cue ->
                    EnhancedSubtitleText(
                        cue = cue,
                        wordSegments = wordSegments,
                        selectedWordIndex = if (isInWordNavMode) selectedWordIndex else -1,
                        fontSize = fontSize,
                        position = subtitlePosition,
                        autoPauseEnabled = autoPauseEnabled,
                        isAutoPaused = isAutoPaused,
                        detectedLanguage = detectedLanguage,
                        onWordClick = { word ->
                            viewModel.selectWord(word)
                            onPausePlayer?.invoke()
                        },
                        onToggleAutoPause = { viewModel.toggleAutoPause() },
                        viewModel = viewModel,
                        isWordFavorite = { w -> favorites.any { it.word == w && it.language == detectedLanguage } }
                    )
                }
            }
        }
        
        // Dictionary dialog
        val selectedAiProvider by viewModel.selectedAiProvider.collectAsState()
        val targetLanguage by viewModel.targetLanguage.collectAsState()
        
        selectedWord?.let { word ->
            DictionaryDialog(
                word = word,
                entry = dictionaryEntry,
                isLoading = isLoadingDictionary,
                detectedLanguage = detectedLanguage,
                isFavorite = favorites.any { it.word == word && it.language == detectedLanguage },
                selectedAiProvider = selectedAiProvider,
                targetLanguage = targetLanguage,
                onDismiss = { viewModel.selectWord(null) },
                onPlayPronunciation = { viewModel.playPronunciation(word) },
                onToggleFavorite = { viewModel.toggleFavorite(word) },
                onSelectAiProvider = { viewModel.setAiProvider(it) },
                onSelectTargetLanguage = { viewModel.setTargetLanguage(it) }
            )
        }
        
        // Log when selectedWord is null but we're in word nav mode (for debugging)
        if (selectedWord == null && isInWordNavMode) {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                Log.d("EnhancedSubtitleOverlay", "selectedWord is null but isInWordNavMode=true, DictionaryDialog not displayed")
            }
        }
    }
}

@Composable
private fun SubtitleStatusMessage(
    message: String,
    showSpinner: Boolean = false,
    color: Color = Color.White,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 32.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = Color.Black.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (showSpinner) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(20.dp)
                            .padding(end = 8.dp),
                        color = color
                    )
                }
                Text(
                    text = message,
                    color = color,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EnhancedSubtitleText(
    cue: SubtitleCue,
    wordSegments: List<WordSegment>,
    selectedWordIndex: Int,
    fontSize: Float,
    position: Float,
    autoPauseEnabled: Boolean,
    isAutoPaused: Boolean,
    detectedLanguage: String,
    onWordClick: (String) -> Unit,
    onToggleAutoPause: () -> Unit,
    viewModel: EnhancedSubtitleViewModel,
    isWordFavorite: (String) -> Boolean
) {
    // 字幕固定在底部，position 为 0 时贴着进度条（约70dp），正值向上移动
    // 进度条在底部约70dp处，字幕框底部贴着进度条
    val offsetY = (-70.dp) + (position * 200).dp  // 默认向下偏移70dp，使字幕框底部贴着进度条
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = offsetY)
            .padding(horizontal = 16.dp),  // 只保留水平padding，垂直方向不留边距以贴合进度条
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Subtitle text with word segments
            // Determine border color based on auto-pause state
            val borderColor = when {
                !autoPauseEnabled -> Color.Gray // Default gray border when auto-pause is disabled
                isAutoPaused -> Color(0xFFF44336) // Red when auto-paused
                else -> Color(0xFF4CAF50) // Green when playing with auto-pause enabled
            }
            val borderWidth = 2.dp // Always show border with 2dp width
            
            Box(
                modifier = Modifier
                    .widthIn(max = 800.dp) // 限制最大宽度，但允许根据内容自适应
                    .background(
                        color = Color.Black.copy(alpha = 0.75f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .border(
                        width = borderWidth,
                        color = borderColor,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                if (wordSegments.isEmpty()) {
                    // Fallback: display plain text
                    Text(
                        text = cue.text,
                        color = Color.Yellow,
                        fontSize = (fontSize * 32).sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        lineHeight = (fontSize * 40).sp
                    )
                } else {
                    // Display segmented text with clickable words
                    SubtitleWords(
                        text = cue.text,
                        segments = wordSegments,
                        selectedIndex = selectedWordIndex,
                        fontSize = fontSize,
                        detectedLanguage = detectedLanguage,
                        isFavorite = isWordFavorite,
                        onWordClick = onWordClick
                    )
                }
            }
        }
    }
}

@Composable
private fun SubtitleWords(
    text: String,
    segments: List<WordSegment>,
    selectedIndex: Int,
    fontSize: Float,
    detectedLanguage: String,
    isFavorite: (String) -> Boolean,
    onWordClick: (String) -> Unit
) {
    Text(
        text = buildAnnotatedStringWithWords(
            text = text,
            segments = segments,
            selectedIndex = selectedIndex,
            fontSize = fontSize,
            isFavorite = isFavorite
        ),
        fontSize = (fontSize * 32).sp,
        color = Color.Yellow,
        fontWeight = FontWeight.Normal,
        lineHeight = (fontSize * 40).sp,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun buildAnnotatedStringWithWords(
    text: String,
    segments: List<WordSegment>,
    selectedIndex: Int,
    fontSize: Float,
    isFavorite: (String) -> Boolean
): AnnotatedString {
    return buildAnnotatedString {
        var lastIndex = 0
    
        segments.forEachIndexed { index, segment ->
            // Add text before segment
            if (segment.startIndex > lastIndex) {
                append(text.substring(lastIndex, segment.startIndex))
            }
            
            // Add styled word
            val isSelected = index == selectedIndex
            val isFav = isFavorite(segment.word)
            val wordColor = when {
                isSelected -> Color.Cyan
                isFav -> Color(0xFFFF6B35) // Orange for favorites
                else -> Color.Yellow
            }
            
            withStyle(
                style = SpanStyle(
                    color = wordColor,
                    fontWeight = if (isSelected || isFav) FontWeight.Bold else FontWeight.Normal
                )
            ) {
                append(segment.word)
            }
            
            lastIndex = segment.endIndex
        }
        
        // Add remaining text
        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }
}

@Composable
private fun DictionaryDialog(
    word: String,
    entry: DictionaryEntry?,
    isLoading: Boolean,
    detectedLanguage: String,
    isFavorite: Boolean,
    selectedAiProvider: String,
    targetLanguage: String,
    onDismiss: () -> Unit,
    onPlayPronunciation: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSelectAiProvider: (String) -> Unit,
    onSelectTargetLanguage: (String) -> Unit
) {
    val favoriteFocusRequester = remember { FocusRequester() }
    val pronunciationFocusRequester = remember { FocusRequester() }
    val mistralFocusRequester = remember { FocusRequester() }
    val ollamaFocusRequester = remember { FocusRequester() }
    val languageFocusRequester = remember { FocusRequester() }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // Request focus on pronunciation icon first (always available), allows immediate play with OK
        LaunchedEffect(isLoading) {
            if (!isLoading) {
                // Small delay to ensure UI is fully rendered before requesting focus
                kotlinx.coroutines.delay(50)
                pronunciationFocusRequester.requestFocus()
            }
        }
        
        // Container: dark rounded panel matching screenshot style
        val scrollState = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 900.dp)
                .wrapContentHeight()
                .heightIn(max = 900.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF2C3E50))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .heightIn(max = 900.dp)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Title row: Sound icon (left) + Word (center) + Favorite (right)
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Sound icon positioned on the left
                    val pronunciationInteractionSource = remember { MutableInteractionSource() }
                    val isPronunciationFocused by pronunciationInteractionSource.collectIsFocusedAsState()
                    val pronunciationBgColor = if (isPronunciationFocused) {
                        Color(0xFF4A90E2).copy(alpha = 0.8f) // Blue background when focused
                    } else {
                        Color.Transparent
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .focusRequester(pronunciationFocusRequester)
                            .background(
                                color = pronunciationBgColor,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                            .handleDPadKeyEvents(
                                onLeft = { favoriteFocusRequester.tryRequestFocus() },
                                onRight = { favoriteFocusRequester.tryRequestFocus() },
                                onUp = onDismiss,
                                onEnter = onPlayPronunciation
                            )
                            .focusable(interactionSource = pronunciationInteractionSource)
                            .clickable(
                                interactionSource = pronunciationInteractionSource,
                                indication = null,
                                onClick = onPlayPronunciation
                            )
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_volume_up),
                            contentDescription = "Play pronunciation",
                            tint = Color(0xFF4DA3FF),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    
                    // Centered word + pronunciation
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TvText(
                                text = word,
                                fontSize = 28.sp,
                                color = Color(0xFFF2F6FA),
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        
                            entry?.aiSource?.let { aiSource ->
                                val isMistral = aiSource.equals("mistral", ignoreCase = true)
                                val tagBgColor = if (isMistral) Color(0xFFFF7000).copy(alpha = 0.2f) else Color(0xFFFBC02D).copy(alpha = 0.2f)
                                val tagBorderColor = if (isMistral) Color(0xFFFF7000).copy(alpha = 0.5f) else Color(0xFFFBC02D).copy(alpha = 0.5f)
                                val tagTextColor = if (isMistral) Color(0xFFFFAB66) else Color(0xFFFDE293)
                            
                                Box(
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .background(tagBgColor, RoundedCornerShape(4.dp))
                                        .border(1.dp, tagBorderColor, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    TvText(
                                        text = aiSource.uppercase(),
                                        fontSize = 12.sp,
                                        color = tagTextColor,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    
                    // Favorite star positioned on the right
                    val favoriteInteractionSource = remember { MutableInteractionSource() }
                    val isFavoriteFocused by favoriteInteractionSource.collectIsFocusedAsState()
                    val favoriteBgColor = if (isFavoriteFocused) {
                        Color(0xFF4A90E2).copy(alpha = 0.8f) // Blue background when focused
                    } else {
                        Color.Transparent
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .focusRequester(favoriteFocusRequester)
                            .background(
                                color = favoriteBgColor,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                            .handleDPadKeyEvents(
                                onLeft = { pronunciationFocusRequester.tryRequestFocus() },
                                onRight = { pronunciationFocusRequester.tryRequestFocus() },
                                onDown = { mistralFocusRequester.tryRequestFocus() },
                                onUp = onDismiss,
                                onEnter = onToggleFavorite
                            )
                            .focusable(interactionSource = favoriteInteractionSource)
                            .clickable(
                                interactionSource = favoriteInteractionSource,
                                indication = null,
                                onClick = onToggleFavorite
                            )
                    ) {
                        TvText(
                            text = if (isFavorite) "★" else "☆",
                            fontSize = 24.sp,
                            color = Color(0xFFF2F6FA)
                        )
                    }
                }

                // Provider selection row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val providers = listOf("mistral", "ollama")
                    providers.forEach { provider ->
                        val isSelected = selectedAiProvider == provider
                        val interactionSource = remember { MutableInteractionSource() }
                        val isFocused by interactionSource.collectIsFocusedAsState()
                        
                        val bgColor = when {
                            isFocused -> Color(0xFF4A90E2).copy(alpha = 0.8f)
                            isSelected -> Color(0xFF34495E)
                            else -> Color.Transparent
                        }
                        
                        val textColor = if (isSelected || isFocused) Color.White else Color.Gray
                        
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .focusRequester(if (provider == "mistral") mistralFocusRequester else ollamaFocusRequester)
                                .background(bgColor, RoundedCornerShape(20.dp))
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) Color(0xFF4DA3FF) else Color.Transparent,
                                    shape = RoundedCornerShape(20.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .handleDPadKeyEvents(
                                    onLeft = { if (provider == "ollama") mistralFocusRequester.tryRequestFocus() },
                                    onRight = { if (provider == "mistral") ollamaFocusRequester.tryRequestFocus() else languageFocusRequester.tryRequestFocus() },
                                    onUp = { favoriteFocusRequester.tryRequestFocus() },
                                    onEnter = { onSelectAiProvider(provider) }
                                )
                                .focusable(interactionSource = interactionSource)
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = null,
                                    onClick = { onSelectAiProvider(provider) }
                                )
                        ) {
                            TvText(
                                text = provider.uppercase(),
                                fontSize = 14.sp,
                                color = textColor,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                    
                    // Language toggle button (EN/中)
                    val langInteractionSource = remember { MutableInteractionSource() }
                    val isLangFocused by langInteractionSource.collectIsFocusedAsState()
                    
                    val langBgColor = when {
                        isLangFocused -> Color(0xFF4A90E2).copy(alpha = 0.8f)
                        else -> Color.Transparent
                    }
                    
                    val langTextColor = if (isLangFocused) Color.White else Color.Gray
                    
                    Box(
                        modifier = Modifier
                            .padding(start = 16.dp)
                            .focusRequester(languageFocusRequester)
                            .background(langBgColor, RoundedCornerShape(20.dp))
                            .border(
                                width = 1.dp,
                                color = Color(0xFF4DA3FF),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .handleDPadKeyEvents(
                                onLeft = { ollamaFocusRequester.tryRequestFocus() },
                                onUp = { favoriteFocusRequester.tryRequestFocus() },
                                onEnter = {
                                    val nextLang = if (targetLanguage == "en") "zh" else "en"
                                    onSelectTargetLanguage(nextLang)
                                }
                            )
                            .focusable(interactionSource = langInteractionSource)
                            .clickable(
                                interactionSource = langInteractionSource,
                                indication = null,
                                onClick = {
                                    val nextLang = if (targetLanguage == "en") "zh" else "en"
                                    onSelectTargetLanguage(nextLang)
                                }
                            )
                    ) {
                        TvText(
                            text = if (targetLanguage == "en") "EN" else "中",
                            fontSize = 14.sp,
                            color = langTextColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Content
                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color(0xFFF2F6FA))
                        }
                    }
                    entry != null -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            entry.definitions.forEachIndexed { index, definition ->
                                // POS tag + Pronunciation on same line (matching Web layout)
                                val posText = definition.partOfSpeech
                                if (!posText.isNullOrBlank() || (index == 0 && !entry.pronunciation.isNullOrBlank())) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = if (index == 0) 6.dp else 12.dp, bottom = 8.dp)
                                    ) {
                                        if (!posText.isNullOrBlank()) {
                                            Box(
                                                modifier = Modifier
                                                    .background(Color(0xFF3A4A55), RoundedCornerShape(50))
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                TvText(text = posText, color = Color(0xFFF2F6FA), fontSize = 18.sp)
                                            }
                                        }
                                        if (index == 0) {
                                            entry.pronunciation?.let { phonetic ->
                                                if (phonetic.isNotBlank()) {
                                                    TvText(
                                                        text = "  [$phonetic]",
                                                        fontSize = 18.sp,
                                                        color = Color(0xFFF2F6FA).copy(alpha = 0.6f),
                                                        fontWeight = FontWeight.Normal,
                                                        modifier = Modifier.padding(start = 8.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Morphology below POS line (like Web)
                                if (index == 0 && !entry.morphology.isNullOrBlank()) {
                                    entry.morphology.split("\n").forEach { line ->
                                        if (line.isNotBlank()) {
                                            TvText(
                                                text = line.trim(),
                                                color = Color(0xFFF2F6FA).copy(alpha = 0.9f),
                                                fontSize = 28.sp,
                                                lineHeight = 34.sp
                                            )
                                        }
                                    }
                                }

                                // Meaning split by newlines (like Web)
                                definition.meaning.split("\n").forEach { line ->
                                    if (line.isNotBlank()) {
                                        TvText(
                                            text = line.trim(),
                                            color = Color(0xFFF2F6FA),
                                            fontSize = 28.sp,
                                            lineHeight = 34.sp
                                        )
                                    }
                                }

                                // Examples (like Web)
                                if (definition.examples.isNotEmpty()) {
                                    definition.examples.forEach { example ->
                                        if (example.isNotBlank()) {
                                            TvText(
                                                text = example.trim(),
                                                color = Color(0xFFF2F6FA).copy(alpha = 0.8f),
                                                fontSize = 28.sp,
                                                lineHeight = 34.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        TvText(
                            text = "未找到释义",
                            color = Color(0xFFFFCDD2),
                            fontSize = 24.sp
                        )
                    }
                }
            }
        }
    }
}

