package com.github.damontecres.stashapp.subtitle

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Text as TvText
import com.github.damontecres.stashapp.subtitle.EnhancedSubtitleViewModel
import com.github.damontecres.stashapp.ui.compat.Button as CompatButton

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
        if (selectedWord != null) {
            // Word selected - pause player to show dictionary dialog
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
                        viewModel = viewModel
                    )
                }
            }
        }
        
        // Dictionary dialog
        selectedWord?.let { word ->
            DictionaryDialog(
                word = word,
                entry = dictionaryEntry,
                isLoading = isLoadingDictionary,
                detectedLanguage = detectedLanguage,
                isFavorite = viewModel.isFavorite(word),
                onDismiss = { viewModel.selectWord(null) },
                onPlayPronunciation = { viewModel.playPronunciation(word) },
                onToggleFavorite = { viewModel.toggleFavorite(word) }
            )
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
    viewModel: EnhancedSubtitleViewModel
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
                        isFavorite = { word -> viewModel.isFavorite(word) },
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
    onDismiss: () -> Unit,
    onPlayPronunciation: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // Container: 更大圆角和 padding，左对齐高留白
        val scrollState = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 960.dp)
                .fillMaxHeight(0.9f)
                .clip(RoundedCornerShape(32.dp))
                .background(Color(0xFF2C3E50))
        ) {
            // 顶部右上角星标（去除关闭按钮）
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 28.dp, end = 38.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 星标按钮
                TvText(
                    text = if (isFavorite) "★" else "☆",
                    fontSize = 32.sp,
                    color = Color(0xFFF2F6FA),
                    modifier = Modifier
                        .focusable()
                        .clickable(onClick = onToggleFavorite)
                        .padding(end = 2.dp)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 42.dp, vertical = 42.dp)
            ) {
                // Title & 词性/音标行全部左对齐
                TvText(
                    text = word,
                    fontSize = 46.sp,
                    color = Color(0xFFF2F6FA),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                // 词性＆音标一行，左对齐
                entry?.let { e ->
                    val posText = e.definitions.firstOrNull()?.partOfSpeech ?: ""
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF3A4A55), RoundedCornerShape(24.dp))
                                .padding(horizontal = 18.dp, vertical = 8.dp)
                        ) {
                            TvText(
                                text = posText.ifEmpty { "词性" },
                                color = Color(0xFFF2F6FA),
                                fontSize = 22.sp,
                                lineHeight = 28.sp
                            )
                        }
                        e.pronunciation?.let { pr ->
                            TvText(
                                text = "  [$pr]",
                                color = Color(0xFF4DA3FF),
                                fontSize = 26.sp,
                                modifier = Modifier
                                    .focusable()
                                    .clickable(onClick = onPlayPronunciation)
                                    .padding(start = 16.dp)
                                ,
                                lineHeight = 32.sp
                            )
                        }
                    }
                }

                // 内容按段落展示，严格左对齐
                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(36.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color(0xFFF2F6FA))
                        }
                    }
                    entry != null -> {
                        // 释义每行分段，自动按换行/分号切割，如例句后另起一段
                        val paras: List<String> = entry.definitions.flatMap {
                            val base = it.meaning.split("\n|").map { it.trim() }.filter { it.isNotBlank() }
                            val examples = it.examples
                            if (examples.isNotEmpty()) base + examples else base
                        }
                        Column(modifier = Modifier.fillMaxWidth()) {
                            paras.forEachIndexed { i, para ->
                                TvText(
                                    text = para,
                                    color = Color(0xFFF2F6FA),
                                    fontSize = 28.sp,
                                    lineHeight = 38.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = if(i==0) 0.dp else 16.dp)
                                )
                            }
                        }
                    }
                    else -> {
                        TvText(
                            text = "未找到释义",
                            color = Color(0xFFFFCDD2),
                            fontSize = 24.sp,
                            lineHeight = 30.sp
                        )
                    }
                }
            }
        }
    }
}

