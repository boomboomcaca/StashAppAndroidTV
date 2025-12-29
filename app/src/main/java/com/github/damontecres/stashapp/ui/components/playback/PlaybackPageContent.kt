package com.github.damontecres.stashapp.ui.components.playback

import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.github.damontecres.stashapp.subtitle.EnhancedSubtitleViewModel
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberNextButtonState
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberPresentationState
import androidx.media3.ui.compose.state.rememberPreviousButtonState
import androidx.tv.material3.MaterialTheme
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Scale
import com.github.damontecres.stashapp.StashApplication
import com.github.damontecres.stashapp.StashExoPlayer
import com.github.damontecres.stashapp.api.fragment.FullSceneData
import com.github.damontecres.stashapp.api.fragment.PerformerData
import com.github.damontecres.stashapp.api.fragment.StashData
import com.github.damontecres.stashapp.data.DataType
import com.github.damontecres.stashapp.data.ThrottledLiveData
import com.github.damontecres.stashapp.data.VideoFilter
import com.github.damontecres.stashapp.data.room.PlaybackEffect
import com.github.damontecres.stashapp.navigation.NavigationManager
import com.github.damontecres.stashapp.playback.PlaybackSceneFragment
import com.github.damontecres.stashapp.playback.PlaylistFragment
import com.github.damontecres.stashapp.playback.TrackActivityPlaybackListener
import com.github.damontecres.stashapp.playback.TrackSupport
import com.github.damontecres.stashapp.playback.TrackSupportReason
import com.github.damontecres.stashapp.playback.TrackType
import com.github.damontecres.stashapp.playback.TranscodeDecision
import com.github.damontecres.stashapp.playback.checkForSupport
import com.github.damontecres.stashapp.playback.maybeMuteAudio
import com.github.damontecres.stashapp.playback.switchToTranscode
import com.github.damontecres.stashapp.proto.PlaybackFinishBehavior
import com.github.damontecres.stashapp.ui.ComposeUiConfig
import com.github.damontecres.stashapp.ui.LocalGlobalContext
import com.github.damontecres.stashapp.ui.compat.isNotTvDevice
import com.github.damontecres.stashapp.ui.compat.isTvDevice
import com.github.damontecres.stashapp.ui.components.ItemOnClicker
import com.github.damontecres.stashapp.ui.components.image.DRAG_THROTTLE_DELAY
import com.github.damontecres.stashapp.ui.components.image.ImageFilterDialog
import com.github.damontecres.stashapp.ui.indexOfFirstOrNull
import com.github.damontecres.stashapp.ui.pages.SearchForDialog
import com.github.damontecres.stashapp.ui.tryRequestFocus
import com.github.damontecres.stashapp.util.ComposePager
import com.github.damontecres.stashapp.util.LoggingCoroutineExceptionHandler
import com.github.damontecres.stashapp.util.MutationEngine
import com.github.damontecres.stashapp.util.QueryEngine
import com.github.damontecres.stashapp.util.StashCoroutineExceptionHandler
import com.github.damontecres.stashapp.util.StashServer
import com.github.damontecres.stashapp.util.findActivity
import com.github.damontecres.stashapp.util.isNotNullOrBlank
import com.github.damontecres.stashapp.util.launchIO
import com.github.damontecres.stashapp.util.showSetRatingToast
import com.github.damontecres.stashapp.util.toLongMilliseconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.properties.Delegates
import kotlin.time.Duration.Companion.milliseconds

const val TAG = "PlaybackPageContent"

class PlaybackViewModel : ViewModel() {
    private lateinit var server: StashServer
    private lateinit var exceptionHandler: CoroutineExceptionHandler
    private var markersEnabled by Delegates.notNull<Boolean>()
    private var saveFilters = true
    private var videoFiltersEnabled = false

    private lateinit var player: Player
    private var trackActivity by Delegates.notNull<Boolean>()
    private var trackActivityListener: TrackActivityPlaybackListener? = null

    val scene = MutableLiveData<FullSceneData>()
    val performers = MutableLiveData<List<PerformerData>>(listOf())

    val mediaItemTag = MutableLiveData<PlaylistFragment.MediaItemTag>()
    val markers = MutableLiveData<List<BasicMarker>>(listOf())
    val oCount = MutableLiveData(0)
    val rating100 = MutableLiveData(0)
    val spriteImageLoaded = MutableLiveData(false)

    private val _videoFilter = MutableLiveData<VideoFilter?>(null)
    val videoFilter = ThrottledLiveData(_videoFilter, 500L)

    fun init(
        server: StashServer,
        player: Player,
        trackActivity: Boolean,
        markersEnabled: Boolean,
        saveFilters: Boolean,
        videoFiltersEnabled: Boolean,
    ) {
        this.server = server
        this.player = player
        this.trackActivity = trackActivity
        this.markersEnabled = markersEnabled
        this.saveFilters = saveFilters
        this.videoFiltersEnabled = videoFiltersEnabled
        this.exceptionHandler = LoggingCoroutineExceptionHandler(server, viewModelScope)
        if (trackActivity) {
            addCloseable("tracking") {
                trackActivityListener?.let {
                    it.release(player.currentPosition)
                    StashExoPlayer.removeListener(it)
                }
            }
        }
    }

    private var sceneJob: Job = Job()

    fun changeScene(tag: PlaylistFragment.MediaItemTag) {
        sceneJob.cancelChildren()
        this.mediaItemTag.value = tag
        this.oCount.value = 0
        this.rating100.value = 0
        this.markers.value = listOf()
        this.spriteImageLoaded.value = false

        if (trackActivity) {
            Log.v(
                TAG,
                "Setting up activity tracking scene ${tag.item.id}, removing=${trackActivityListener?.scene?.id}",
            )
            trackActivityListener?.apply {
                release()
                StashExoPlayer.removeListener(this)
            }
            tag.item.let {
                trackActivityListener =
                    TrackActivityPlaybackListener(
                        server = server,
                        scene = it,
                        getCurrentPosition = {
                            player.currentPosition
                        },
                    )
            }
            trackActivityListener?.let { StashExoPlayer.addListener(it) }
        }

        refreshScene(tag.item.id)

        if (videoFiltersEnabled) {
            updateVideoFilter(VideoFilter())
            if (saveFilters && videoFiltersEnabled) {
                viewModelScope.launch(sceneJob + StashCoroutineExceptionHandler() + Dispatchers.IO) {
                    val vf =
                        StashApplication
                            .getDatabase()
                            .playbackEffectsDao()
                            .getPlaybackEffect(server.url, tag.item.id, DataType.SCENE)
                    if (vf != null) {
                        Log.d(
                            TAG,
                            "Loaded VideoFilter for scene ${tag.item.id}",
                        )
                        withContext(Dispatchers.Main) {
                            videoFilter.stopThrottling(true)
                            updateVideoFilter(vf.videoFilter)
                            videoFilter.startThrottling()
                        }
                    }
                }
            }
        }

        // Fetch preview sprites
        viewModelScope.launch(sceneJob + StashCoroutineExceptionHandler()) {
            val context = StashApplication.getApplication()
            val imageLoader = SingletonImageLoader.get(context)
            if (tag.item.spriteUrl.isNotNullOrBlank()) {
                val request =
                    ImageRequest
                        .Builder(context)
                        .data(tag.item.spriteUrl)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .scale(Scale.FILL)
                        .build()
                val result = imageLoader.enqueue(request).job.await()
                Log.d(TAG, "sprite preload done scene=${tag.item.id}, success=${result.image != null}, url=${tag.item.spriteUrl}")
                spriteImageLoaded.value = result.image != null
            }
        }
    }

    private fun refreshScene(sceneId: String) {
        // Fetch o count & markers
        viewModelScope.launch(sceneJob + exceptionHandler) {
            oCount.value = 0
            rating100.value = 0
            markers.value = listOf()
            performers.value = listOf()

            val queryEngine = QueryEngine(server)
            val scene = queryEngine.getScene(sceneId)
            if (scene != null) {
                oCount.value = scene.o_counter ?: 0
                rating100.value = scene.rating100 ?: 0
                if (markersEnabled) {
                    markers.value =
                        scene.scene_markers
                            .sortedBy { it.seconds }
                            .map(::BasicMarker)
                }
                if (scene.performers.isNotEmpty()) {
                    performers.value =
                        queryEngine.findPerformers(performerIds = scene.performers.map { it.id })
                }
            }
            this@PlaybackViewModel.scene.value = scene
        }
    }

    fun addMarker(
        position: Long,
        tagId: String,
    ) {
        mediaItemTag.value?.let {
            viewModelScope.launch(exceptionHandler) {
                val mutationEngine = MutationEngine(server)
                val newMarker = mutationEngine.createMarker(it.item.id, position, tagId)
                if (newMarker != null) {
                    // Refresh markers
                    refreshScene(it.item.id)
                    Toast
                        .makeText(
                            StashApplication.getApplication(),
                            "Created marker at ${position.milliseconds}",
                            Toast.LENGTH_SHORT,
                        ).show()
                }
            }
        }
    }

    fun incrementOCount(sceneId: String) {
        viewModelScope.launch(exceptionHandler) {
            val mutationEngine = MutationEngine(server)
            oCount.value = mutationEngine.incrementOCounter(sceneId).count
        }
    }

    fun updateVideoFilter(newFilter: VideoFilter?) {
        _videoFilter.value = newFilter
    }

    fun saveVideoFilter() {
        mediaItemTag.value?.item?.let {
            viewModelScope.launchIO(StashCoroutineExceptionHandler(autoToast = true)) {
                val vf = _videoFilter.value
                if (vf != null) {
                    StashApplication
                        .getDatabase()
                        .playbackEffectsDao()
                        .insert(PlaybackEffect(server.url, it.id, DataType.SCENE, vf))
                    Log.d(TAG, "Saved VideoFilter for scene ${it.id}")
                    withContext(Dispatchers.Main) {
                        Toast
                            .makeText(
                                StashApplication.getApplication(),
                                "Saved",
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                }
            }
        }
    }

    fun updateRating(
        sceneId: String,
        rating100: Int,
    ) {
        viewModelScope.launch(exceptionHandler) {
            val newRating =
                MutationEngine(server).setRating(sceneId, rating100)?.rating100 ?: 0
            this@PlaybackViewModel.rating100.value = newRating
            showSetRatingToast(StashApplication.getApplication(), newRating)
        }
    }
}

val playbackScaleOptions =
    mapOf(
        ContentScale.Fit to "Fit",
        ContentScale.None to "None",
        ContentScale.Crop to "Crop",
//        ContentScale.Inside to "Inside",
        ContentScale.FillBounds to "Fill",
        ContentScale.FillWidth to "Fill Width",
        ContentScale.FillHeight to "Fill Height",
    )

@OptIn(UnstableApi::class)
@Composable
fun PlaybackPageContent(
    server: StashServer,
    player: ExoPlayer,
    playlist: List<MediaItem>,
    startIndex: Int,
    uiConfig: ComposeUiConfig,
    markersEnabled: Boolean,
    playlistPager: ComposePager<StashData>?,
    onClickPlaylistItem: ((Int) -> Unit)?,
    itemOnClick: ItemOnClicker<Any>,
    modifier: Modifier = Modifier,
    controlsEnabled: Boolean = true,
    viewModel: PlaybackViewModel = viewModel(),
    startPosition: Long = C.TIME_UNSET,
) {
    var savedStartPosition by rememberSaveable(startPosition) { mutableLongStateOf(startPosition) }
    var currentPlaylistIndex by rememberSaveable(startIndex) { mutableIntStateOf(startIndex) }
    if (playlist.isEmpty() || playlist.size < currentPlaylistIndex) {
        return
    }

    val context = LocalContext.current
    val navigationManager = LocalGlobalContext.current.navigationManager
    val currentScene by viewModel.mediaItemTag.observeAsState(
        playlist[currentPlaylistIndex].localConfiguration!!.tag as PlaylistFragment.MediaItemTag,
    )
    val markers by viewModel.markers.observeAsState(listOf())
    val oCount by viewModel.oCount.observeAsState(0)
    val rating100 by viewModel.rating100.observeAsState(0)
    val spriteImageLoaded by viewModel.spriteImageLoaded.observeAsState(false)
    var currentTracks by remember { mutableStateOf<List<TrackSupport>>(listOf()) }
    var captions by remember { mutableStateOf<List<TrackSupport>>(listOf()) }
    var subtitles by remember { mutableStateOf<List<Cue>?>(null) }
    var subtitleIndex by remember { mutableStateOf<Int?>(null) }
    var mediaIndexSubtitlesActivated by remember { mutableStateOf<Int>(-1) }
    var audioIndex by remember { mutableStateOf<Int?>(null) }
    var audioOptions by remember { mutableStateOf<List<String>>(listOf()) }
    var showFilterDialog by rememberSaveable { mutableStateOf(false) }
    val videoFilter by viewModel.videoFilter.observeAsState()
    
    // Enhanced Subtitle state
    var enhancedSubtitlesEnabled by rememberSaveable { mutableStateOf(false) }
    val enhancedSubtitleViewModel: com.github.damontecres.stashapp.subtitle.EnhancedSubtitleViewModel = viewModel()
    val autoPauseEnabled by enhancedSubtitleViewModel.autoPauseEnabled.collectAsState()
    
    // Initialize server for enhanced subtitle ViewModel
    LaunchedEffect(server) {
        enhancedSubtitleViewModel.setServer(server)
    }

    var showSceneDetails by rememberSaveable { mutableStateOf(false) }
    val scene by viewModel.scene.observeAsState()
    val performers by viewModel.performers.observeAsState(listOf())

    AmbientPlayerListener(player)

    LifecycleStartEffect(Unit) {
        onStopOrDispose {
            savedStartPosition = player.currentPosition
            currentPlaylistIndex = player.currentMediaItemIndex
            StashExoPlayer.releasePlayer()
        }
    }

    var showControls by remember { mutableStateOf(true) }
    var showPlaylist by remember { mutableStateOf(false) }
    var contentScale by remember { mutableStateOf(ContentScale.Fit) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    LaunchedEffect(playbackSpeed) { player.setPlaybackSpeed(playbackSpeed) }

    val presentationState = rememberPresentationState(player)
    val scaledModifier =
        Modifier.resizeWithContentScale(contentScale, presentationState.videoSizeDp)

    var contentCurrentPosition by remember { mutableLongStateOf(0L) }

    var createMarkerPosition by remember { mutableLongStateOf(-1L) }
    var playingBeforeDialog by remember { mutableStateOf(false) }

    var showDebugInfo by remember {
        mutableStateOf(
            uiConfig.preferences.playbackPreferences.showDebugInfo,
        )
    }
    val showSkipProgress = uiConfig.preferences.interfacePreferences.showProgressWhenSkipping
    val skipWithLeftRight = uiConfig.preferences.playbackPreferences.dpadSkipping
    // Enabled if the preference is enabled and playing a playlist of markers
    val nextWithUpDown =
        remember {
            playlistPager != null &&
                playlistPager.filter.dataType == DataType.MARKER &&
                playlistPager.size > 1 &&
                uiConfig.preferences.interfacePreferences.useUpDownPreviousNext
        }
    val useVideoFilters = uiConfig.preferences.playbackPreferences.videoFiltersEnabled

    val controllerViewState =
        remember {
            ControllerViewState(
                uiConfig.preferences.playbackPreferences.controllerTimeoutMs,
                controlsEnabled,
            )
        }.also {
            LaunchedEffect(it) {
                it.observe()
            }
        }

    val retryMediaItemIds = remember { mutableSetOf<String>() }

    val isMarkerPlaylist = playlistPager?.filter?.dataType == DataType.MARKER

    val isTvDevice = isTvDevice

    var videoDecoder by remember { mutableStateOf<String?>(null) }
    var audioDecoder by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.init(
            server,
            player,
            uiConfig.preferences.playbackPreferences.savePlayHistory &&
                server.serverPreferences.trackActivity &&
                !isMarkerPlaylist,
            markersEnabled,
            uiConfig.persistVideoFilters,
            useVideoFilters,
        )
        viewModel.changeScene(playlist[currentPlaylistIndex].localConfiguration!!.tag as PlaylistFragment.MediaItemTag)
        if (!isTvDevice) {
            viewModel.videoFilter.startThrottling(DRAG_THROTTLE_DELAY)
        }
        maybeMuteAudio(uiConfig.preferences, false, player)
        player.setMediaItems(playlist, startIndex, savedStartPosition)
        if (playlistPager == null) {
            player.setupFinishedBehavior(
                uiConfig.preferences.playbackPreferences.playbackFinishBehavior,
                navigationManager,
            ) {
                controllerViewState.showControls()
            }
        }
        StashExoPlayer.addListener(
            StashAnalyticsListener { audio, video ->
                audioDecoder = audio
                videoDecoder = video
            },
        )
        StashExoPlayer.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        enhancedSubtitleViewModel.notifyUserResumed()
                    }
                }
                override fun onCues(cueGroup: CueGroup) {
//                    val cues =
//                        cueGroup.cues
//                            .mapNotNull { it.text }
//                            .joinToString("\n")
//                    Log.v(TAG, "onCues: \n$cues")
                    subtitles = cueGroup.cues.ifEmpty { null }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    val trackInfo = checkForSupport(tracks)
                    currentTracks = trackInfo
                    val audioTracks =
                        trackInfo
                            .filter { it.type == TrackType.AUDIO && it.supported == TrackSupportReason.HANDLED }
                    audioIndex = audioTracks.indexOfFirstOrNull { it.selected }
                    audioOptions =
                        audioTracks.map { it.labels.joinToString(", ").ifBlank { "Default" } }
                    // Native captions are always disabled - only enhanced subtitles are available
                    captions = emptyList()
                    
                    // Disable native captions by default (captionsByDefault setting is ignored)
                    // Native captions are permanently disabled to use only enhanced subtitles
                }

                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    if (mediaItem != null) {
                        viewModel.changeScene(mediaItem.localConfiguration!!.tag as PlaylistFragment.MediaItemTag)
                    }
                    subtitles = null
                    subtitleIndex = null
                    currentPlaylistIndex = player.currentMediaItemIndex
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(
                        TAG,
                        "PlaybackException on scene ${currentScene.item.id}, errorCode=${error.errorCode}",
                        error,
                    )
                    val showError =
                        when (error.errorCode) {
                            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                            PlaybackException.ERROR_CODE_DECODING_FAILED,
                            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
                            -> {
                                val current = player.currentMediaItem
                                val currentPosition = player.currentMediaItemIndex
                                if (current != null) {
                                    val tag =
                                        (current.localConfiguration!!.tag as PlaylistFragment.MediaItemTag)
                                    val id = tag.item.id
                                    val isTranscodingOrDirect =
                                        tag.streamDecision.transcodeDecision == TranscodeDecision.Transcode ||
                                            tag.streamDecision.transcodeDecision is TranscodeDecision.ForcedTranscode ||
                                            tag.streamDecision.transcodeDecision is TranscodeDecision.ForcedDirectPlay
                                    if (id !in retryMediaItemIds && !isTranscodingOrDirect) {
                                        retryMediaItemIds.add(id)
                                        val newMediaItem =
                                            switchToTranscode(
                                                context,
                                                current,
                                                uiConfig.preferences.playbackPreferences,
                                            )
                                        val newTag =
                                            newMediaItem.localConfiguration!!.tag as PlaylistFragment.MediaItemTag
                                        Log.d(
                                            TAG,
                                            "Using new transcoding media item: ${newTag.streamDecision}",
                                        )
                                        viewModel.changeScene(newTag)
                                        player.replaceMediaItem(currentPosition, newMediaItem)
                                        player.prepare()
                                        if (savedStartPosition != C.TIME_UNSET) {
                                            player.seekTo(savedStartPosition)
                                        }
                                        player.play()
                                        false
                                    } else {
                                        true
                                    }
                                } else {
                                    Log.w(
                                        TAG,
                                        "No current media item, cannot fallback to transcoding",
                                    )
                                    true
                                }
                            }

                            else -> true
                        }
                    if (showError) {
                        Toast
                            .makeText(
                                context,
                                "Play error: ${error.localizedMessage}",
                                Toast.LENGTH_LONG,
                            ).show()
                    }
                }
            },
        )

        player.prepare()
        if (useVideoFilters) {
            Log.d(TAG, "Enabling video effects")
            player.setVideoEffects(listOf())
        }
    }

    val windowInsetsController =
        remember {
            context
                .findActivity()
                ?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        }

    if (isNotTvDevice && windowInsetsController != null && controllerViewState.controlsEnabled) {
        if (controllerViewState.controlsVisible) {
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        } else {
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    var skipIndicatorDuration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(controllerViewState.controlsVisible) {
        // If controller shows/hides, immediately cancel the skip indicator
        skipIndicatorDuration = 0L
    }
    var skipPosition by remember { mutableLongStateOf(0L) }
    val updateSkipIndicator = { delta: Long ->
        if (skipIndicatorDuration > 0 && delta < 0 || skipIndicatorDuration < 0 && delta > 0) {
            skipIndicatorDuration = 0
        }
        skipIndicatorDuration += delta
        skipPosition = player.currentPosition
    }
    val scope = rememberCoroutineScope()
    val playPauseState = rememberPlayPauseButtonState(player)
    val previousState = rememberPreviousButtonState(player)
    val nextState = rememberNextButtonState(player)
    val seekBarState = rememberSeekBarState(player, scope)

    // Track left/right key press state when control bar is hidden
    var leftRightKeyPressed by remember { mutableStateOf(false) }
    var lastPreviewTileIndex by remember { mutableIntStateOf(-1) }
    var lastPreviewUpdateMs by remember { mutableLongStateOf(0L) }

    // Calculate current seek progress for preview
    var currentSeekProgress by remember { mutableFloatStateOf(-1f) }
    LaunchedEffect(player) {
        snapshotFlow { player.duration to player.currentPosition }
            .collect { (duration, position) ->
                if (duration > 0) {
                    currentSeekProgress = (position.toFloat() / duration).coerceIn(0f, 1f)
                }
            }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.tryRequestFocus()
    }
    val playbackKeyHandler =
        remember(enhancedSubtitlesEnabled, enhancedSubtitleViewModel) {
            PlaybackKeyHandler(
                isTvDevice = isTvDevice,
                player = player,
                controlsEnabled = controlsEnabled,
                skipWithLeftRight = skipWithLeftRight,
                nextWithUpDown = nextWithUpDown,
                controllerViewState = controllerViewState,
                updateSkipIndicator = updateSkipIndicator,
                enhancedSubtitleViewModel = enhancedSubtitleViewModel,
                enhancedSubtitlesEnabled = enhancedSubtitlesEnabled,
                onLeftRightKeyStateChanged = { pressed ->
                    leftRightKeyPressed = pressed
                },
                onPreviewProgressChange = { progress ->
                    val tileCount = 81 // 9x9 sprite grid
                    val clampedProgress = progress.coerceIn(0f, 1f)
                    val tileIndex = (clampedProgress * tileCount).toInt().coerceIn(0, tileCount - 1)
                    val now = SystemClock.uptimeMillis()
                    val timeOk = now - lastPreviewUpdateMs >= 300L
                    val tileOk = tileIndex != lastPreviewTileIndex
                    // Require both: reduce churn while still moving tile forward
                    if (timeOk && tileOk) {
                        lastPreviewTileIndex = tileIndex
                        lastPreviewUpdateMs = now
                        Log.d(TAG, "preview progress update (left/right): progress=$clampedProgress duration=${player.duration} pos=${player.currentPosition} tile=$tileIndex")
                        currentSeekProgress = clampedProgress
                    }
                },
            )
        }
    Box(
        modifier
            .background(Color.Black)
            .onKeyEvent(playbackKeyHandler::onKeyEvent)
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        PlayerSurface(
            player = player,
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            modifier =
                scaledModifier.clickable(
                    enabled = !isTvDevice,
                    indication = null,
                    interactionSource = null,
                ) {
                    if (controllerViewState.controlsVisible) {
                        controllerViewState.hideControls()
                    } else {
                        controllerViewState.showControls()
                    }
                },
        )
        if (presentationState.coverSurface) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black),
            )
        }
        if (!controllerViewState.controlsVisible && skipIndicatorDuration != 0L) {
            SkipIndicator(
                durationMs = skipIndicatorDuration,
                onFinish = {
                    skipIndicatorDuration = 0L
                },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 70.dp),
            )
            if (showSkipProgress) {
                currentScene.item.duration?.let {
                    val percent =
                        skipPosition.toFloat() / (it.toLongMilliseconds).toFloat()
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.BottomStart)
                                .background(MaterialTheme.colorScheme.border)
                                .clip(RectangleShape)
                                .height(3.dp)
                                .fillMaxWidth(percent),
                    ) {}
                }
            }
        }
        
        // Show preview when control bar is hidden and left/right keys are pressed
        currentScene?.let { scene ->
            val shouldShowPreview = !controllerViewState.controlsVisible && 
                                    leftRightKeyPressed && 
                                    !isMarkerPlaylist &&
                                    currentSeekProgress >= 0 &&
                                    player.duration > 0
            if (shouldShowPreview) {
                val previewImageUrl = scene.item.spriteUrl
                val imageLoader = coil3.SingletonImageLoader.get(LocalContext.current)
                val spriteImageLoadedState = spriteImageLoaded
                val yOffsetDp = 180.dp + (if (spriteImageLoadedState) 160.dp else 24.dp)
                val heightPx = with(LocalDensity.current) { yOffsetDp.toPx().toInt() }

                SeekPreviewImage(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offsetByPercent(
                            xPercentage = currentSeekProgress.coerceIn(0f, 1f),
                            yOffset = heightPx,
                        ),
                    imageLoaded = spriteImageLoadedState,
                    previewImageUrl = previewImageUrl,
                    imageLoader = imageLoader,
                    duration = player.duration,
                    seekProgress = currentSeekProgress,
                    videoWidth = scene.item.videoWidth,
                    videoHeight = scene.item.videoHeight,
                    placeHolder = ColorPainter(Color(0xFF1E1E1E)),
                )
            }
        }

        // Native subtitles are always disabled - only enhanced subtitles are available
        // if (!controllerViewState.controlsVisible && subtitleIndex != null && skipIndicatorDuration == 0L && !enhancedSubtitlesEnabled) {
        if (false) {
            AndroidView(
                factory = {
                    SubtitleView(context).apply {
                        setUserDefaultStyle()
                        setUserDefaultTextSize()
                    }
                },
                update = {
                    it.setCues(subtitles)
                },
                onReset = {
                    it.setCues(null)
                },
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Transparent),
            )
        }

        currentScene?.let {
            AnimatedVisibility(
                controllerViewState.controlsVisible,
                Modifier,
                slideInVertically { it },
                slideOutVertically { it },
            ) {
                PlaybackOverlay(
                    modifier =
                        Modifier
                            .padding(WindowInsets.systemBars.asPaddingValues())
                            .fillMaxSize()
                            .background(Color.Transparent),
                    uiConfig = uiConfig,
                    scene = currentScene.item,
                    tracks = currentTracks,
                    captions = captions,
                    markers = markers,
                    streamDecision = currentScene.streamDecision,
                    oCounter = oCount,
                    playerControls = PlayerControlsImpl(player),
                    onPlaybackActionClick = {
                        when (it) {
                            PlaybackAction.CreateMarker -> {
                                if (markersEnabled) {
                                    playingBeforeDialog = player.isPlaying
                                    player.pause()
                                    controllerViewState.hideControls()
                                    createMarkerPosition = player.currentPosition
                                }
                            }

                            PlaybackAction.OCount -> {
                                viewModel.incrementOCount(currentScene.item.id)
                            }

                            PlaybackAction.ShowDebug -> {
                                showDebugInfo = !showDebugInfo
                            }

                            PlaybackAction.ShowVideoFilterDialog -> showFilterDialog = true

                            PlaybackAction.ShowPlaylist -> {
                                if (playlistPager != null && playlistPager.size > 1) {
                                    showPlaylist = true
                                    controllerViewState.hideControls()
                                }
                            }

                            is PlaybackAction.ToggleCaptions -> {
                                // Native captions are disabled - do nothing
                                // Only enhanced subtitles are available
                            }

                            is PlaybackAction.PlaybackSpeed -> playbackSpeed = it.value
                            is PlaybackAction.Scale -> contentScale = it.scale
                            is PlaybackAction.ToggleAudio -> {
                                if (toggleAudio(player, audioIndex, it.index)) {
                                    audioIndex = it.index
                                } else {
                                    audioIndex = null
                                }
                            }

                            PlaybackAction.ShowSceneDetails -> {
                                showSceneDetails = true
                            }
                            
                            PlaybackAction.ToggleEnhancedSubtitles -> {
                                enhancedSubtitlesEnabled = !enhancedSubtitlesEnabled
                                controllerViewState.hideControls()
                            }
                            
                            PlaybackAction.ToggleAutoPause -> {
                                enhancedSubtitleViewModel.toggleAutoPause()
                            }
                        }
                    },
                    onSeekBarChange = seekBarState::onValueChange,
                    controllerViewState = controllerViewState,
                    showPlay = playPauseState.showPlay,
                    previousEnabled = previousState.isEnabled,
                    nextEnabled = nextState.isEnabled,
                    seekEnabled = seekBarState.isEnabled,
                    seekPreviewEnabled = !isMarkerPlaylist,
                    showDebugInfo = showDebugInfo,
                    spriteImageLoaded = spriteImageLoaded,
                    moreButtonOptions =
                        MoreButtonOptions(
                            buildMap {
                                if (markersEnabled) {
                                    put("Create Marker", PlaybackAction.CreateMarker)
                                }
                                if (playlistPager != null && playlistPager.size > 1) {
                                    put("Show Playlist", PlaybackAction.ShowPlaylist)
                                }
                                if (useVideoFilters) {
                                    put("Set video filters", PlaybackAction.ShowVideoFilterDialog)
                                }
                                put("Details", PlaybackAction.ShowSceneDetails)
                            },
                        ),
                    subtitleIndex = subtitleIndex,
                    audioIndex = audioIndex,
                    audioOptions = audioOptions,
                    playbackSpeed = playbackSpeed,
                    scale = contentScale,
                    playlistInfo =
                        playlistPager?.let {
                            PlaylistInfo(
                                currentPlaylistIndex,
                                it.size,
                                player.mediaItemCount,
                            )
                        },
                    videoDecoder = videoDecoder,
                    audioDecoder = audioDecoder,
                    enhancedSubtitlesEnabled = enhancedSubtitlesEnabled,
                    autoPauseEnabled = autoPauseEnabled,
                )
            }
        }
        val dismiss = {
            createMarkerPosition = -1
            if (playingBeforeDialog) {
                player.play()
            }
        }
        SearchForDialog(
            show = markersEnabled && createMarkerPosition >= 0,
            uiConfig = uiConfig,
            dataType = DataType.TAG,
            onItemClick = { item ->
                viewModel.addMarker(createMarkerPosition, item.id)
                dismiss.invoke()
            },
            onDismissRequest = dismiss,
            dialogTitle = "Create marker at ${createMarkerPosition.milliseconds}?",
            dismissOnClick = false,
        )
        if (playlistPager != null && onClickPlaylistItem != null) {
            PlaylistListDialog(
                show = showPlaylist,
                onDismiss = { showPlaylist = false },
                player = player,
                pager = playlistPager,
                onClickPlaylistItem = onClickPlaylistItem,
                modifier = Modifier,
            )
        }
        videoFilter?.let {
            val effectList = it.createEffectList()
            Log.d(TAG, "Applying ${effectList.size} effects")
            player.setVideoEffects(effectList)

            AnimatedVisibility(showFilterDialog) {
                ImageFilterDialog(
                    filter = it,
                    showVideoOptions = true,
                    showSaveGalleryButton = false,
                    uiConfig = uiConfig,
                    onChange = viewModel::updateVideoFilter,
                    onClickSave = viewModel::saveVideoFilter,
                    onClickSaveGallery = {},
                    onDismissRequest = {
                        showFilterDialog = false
                    },
                )
            }
        }
        AnimatedVisibility(showSceneDetails && scene != null) {
            LaunchedEffect(Unit) {
                playingBeforeDialog = player.isPlaying
                player.pause()
                controllerViewState.hideControls()
            }
            scene?.let { scene ->
                Dialog(
                    onDismissRequest = {
                        showSceneDetails = false
                        if (playingBeforeDialog) {
                            player.play()
                        }
                    },
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    SceneDetailsOverlay(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background.copy(alpha = .75f)),
                        server = server,
                        scene = scene,
                        performers = performers,
                        uiConfig = uiConfig,
                        itemOnClick = itemOnClick,
                        rating100 = rating100,
                        onRatingChange = { viewModel.updateRating(scene.id, it) },
                    )
                }
            }
        }
        
        // Enhanced Subtitle Overlay - placed at the end to ensure it's on top
        if (enhancedSubtitlesEnabled) {
            // Track player position for subtitle synchronization
            var playerPosition by remember { mutableLongStateOf(0L) }
            LaunchedEffect(enhancedSubtitlesEnabled) {
                if (enhancedSubtitlesEnabled) {
                    while (true) {
                        playerPosition = player.currentPosition
                        kotlinx.coroutines.delay(100) // Update every 100ms for smooth subtitle sync
                    }
                }
            }
            
            val currentSceneItem = currentScene?.item
            val subtitleUrl = if (currentSceneItem?.captionUrl != null) {
                // If captionUrl exists, ensure it has query parameters
                val baseUrl = android.net.Uri.parse(currentSceneItem.captionUrl)
                if (currentSceneItem.captions.isNotEmpty()) {
                    val firstCaption = currentSceneItem.captions.first()
                    baseUrl.buildUpon()
                        .clearQuery()
                        .appendQueryParameter("lang", firstCaption.language_code)
                        .appendQueryParameter("type", firstCaption.caption_type)
                        .build()
                        .toString()
                } else {
                    currentSceneItem.captionUrl
                }
            } else {
                // If captionUrl is null but captions exist, try to build the URL from stream URL
                if (currentSceneItem?.captions?.isNotEmpty() == true && currentSceneItem.streamUrl != null) {
                    // Build caption URL: {baseURL}/scene/{sceneID}/caption?lang={lang}&type={type}
                    try {
                        val streamUrl = android.net.Uri.parse(currentSceneItem.streamUrl)
                        // Replace "/stream" with "/caption" in the path
                        val path = streamUrl.path?.replace("/stream", "/caption") ?: "/scene/${currentSceneItem.id}/caption"
                        val baseUrl = streamUrl.buildUpon()
                            .path(path)
                            .clearQuery()
                            .build()
                        // Use the first available caption
                        val firstCaption = currentSceneItem.captions.first()
                        baseUrl.buildUpon()
                            .appendQueryParameter("lang", firstCaption.language_code)
                            .appendQueryParameter("type", firstCaption.caption_type)
                            .build()
                            .toString()
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
            }
            // Use tracked player position for subtitle synchronization
            val currentTimeSeconds = (playerPosition / 1000.0).coerceAtLeast(0.0)
            
            // Word navigation mode state (auto-pause happens in checkAutoPause, not here)
            val isInWordNavigationMode by enhancedSubtitleViewModel.isInWordNavigationMode.collectAsState()
            
            com.github.damontecres.stashapp.subtitle.EnhancedSubtitleOverlay(
                viewModel = enhancedSubtitleViewModel,
                currentTimeSeconds = currentTimeSeconds,
                subtitleUrl = subtitleUrl,
                isVisible = enhancedSubtitlesEnabled,
                onPausePlayer = { player.pause() },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

fun Player.setupFinishedBehavior(
    finishedBehavior: PlaybackFinishBehavior,
    navigationManager: NavigationManager,
    showController: () -> Unit,
) {
    when (finishedBehavior) {
        PlaybackFinishBehavior.REPEAT -> {
            repeatMode = Player.REPEAT_MODE_ONE
        }

        PlaybackFinishBehavior.GO_BACK ->
            StashExoPlayer.addListener(
                object :
                    Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            navigationManager.goBack()
                        }
                    }
                },
            )

        PlaybackFinishBehavior.DO_NOTHING -> {
            StashExoPlayer.addListener(
                object :
                    Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            showController()
                        }
                    }
                },
            )
        }

        else ->
            Log.w(
                PlaybackSceneFragment.TAG,
                "Unknown playbackFinishedBehavior: $finishedBehavior",
            )
    }
}

class PlaybackKeyHandler(
    private val isTvDevice: Boolean,
    private val player: Player,
    private val controlsEnabled: Boolean,
    private val skipWithLeftRight: Boolean,
    private val nextWithUpDown: Boolean,
    private val controllerViewState: ControllerViewState,
    private val updateSkipIndicator: (Long) -> Unit,
    private val enhancedSubtitleViewModel: EnhancedSubtitleViewModel? = null,
    private val enhancedSubtitlesEnabled: Boolean = false,
    private val onLeftRightKeyStateChanged: ((Boolean) -> Unit)? = null,
    private val onPreviewProgressChange: ((Float) -> Unit)? = null,
) {
    // Double-click detection timing (400ms, same as stash-server frontend)
    private val doubleClickDelayMs = 400L
    
    // Track last key press time and pending handlers for double-click detection
    private var lastUpArrowPress = 0L
    private var lastDownArrowPress = 0L
    private var upArrowHandler: Handler? = null
    private var downArrowHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    /**
     * Handle up arrow key with double-click detection:
     * - Single click: Toggle play/pause and exit word selection mode
     * - Double click (within 400ms): Seek to next subtitle and play, exiting word selection mode
     */
    private fun handleUpArrowKey() {
        val now = System.currentTimeMillis()
        val timeSinceLastPress = now - lastUpArrowPress
        
        // Cancel any pending single-click handler
        upArrowHandler?.removeCallbacksAndMessages(null)
        upArrowHandler = null
        
        if (timeSinceLastPress < doubleClickDelayMs && timeSinceLastPress > 0) {
            // Double click detected - seek to next subtitle
            lastUpArrowPress = 0L
            val currentTimeSeconds = (player.currentPosition / 1000.0).coerceAtLeast(0.0)
            val nextCueTime = enhancedSubtitleViewModel?.seekToNextCue(currentTimeSeconds)
            if (nextCueTime != null) {
                player.seekTo((nextCueTime * 1000).toLong())
                if (!player.isPlaying) {
                    player.play()
                }
                // Exit word selection mode when seeking to next subtitle
                enhancedSubtitleViewModel?.exitWordNavigationMode()
            }
        } else {
            // Single click - will trigger after delay if no second press
            lastUpArrowPress = now
            
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({
                val currentTime = System.currentTimeMillis()
                val timeSincePress = currentTime - lastUpArrowPress
                
                // Only execute if this was a single press (not a double press)
                if (timeSincePress >= doubleClickDelayMs && lastUpArrowPress != 0L) {
                    // Single press - toggle play/pause and exit word selection mode
                    val wasPlaying = player.isPlaying
                    if (wasPlaying) {
                        player.pause()
                    } else {
                        player.play()
                        // Mark manual resume to suppress immediate auto-pause retrigger
                        enhancedSubtitleViewModel?.notifyUserResumed()
                    }
                    enhancedSubtitleViewModel?.exitWordNavigationMode()
                    lastUpArrowPress = 0L
                }
            }, doubleClickDelayMs)
            upArrowHandler = handler
        }
    }
    
    /**
     * Handle left/right arrow keys:
     * - Control bar visible: Seek backward/forward (10s default, 5s with Shift, 60s with Ctrl/Alt)
     * - Enhanced subtitles enabled and control bar hidden:
     *   - If not in word navigation mode: Enter word navigation mode
     *   - If in word navigation mode: Navigate to previous/next word
     * @return true if event was handled (consumed), false if should propagate
     */
    private fun handleLeftRightArrowKey(event: KeyEvent): Boolean {
        val isLeft = event.key == Key.DirectionLeft
        
        if (controllerViewState.controlsVisible) {
            // Control bar visible: do not adjust progress here. Let focused UI (e.g., seek bar) handle left/right.
            // Notify that key is not pressed (control bar handles it)
            onLeftRightKeyStateChanged?.invoke(false)
            return false
        } else {
            // Control bar hidden: Enhanced subtitle word navigation
            if (enhancedSubtitlesEnabled && enhancedSubtitleViewModel != null) {
                if (event.type == KeyEventType.KeyUp) {
                    // Navigate words: if no word selected, left goes to last, right goes to first
                    // The navigation methods handle entering word navigation mode automatically
                    if (isLeft) {
                        enhancedSubtitleViewModel.navigateToPreviousWord()
                    } else {
                        enhancedSubtitleViewModel.navigateToNextWord()
                    }
                    // Don't show control bar when navigating words
                    onLeftRightKeyStateChanged?.invoke(false)
                }
                return true // Event handled
            } else {
                // Default behavior: seek backward/forward on KeyDown
                if (event.type == KeyEventType.KeyDown) {
                    if (skipWithLeftRight) {
                        val duration = player.duration.takeIf { it > 0 } ?: 0L
                        // Notify that key is pressed for preview
                        onLeftRightKeyStateChanged?.invoke(true)
                        if (duration > 0) {
                            val delta =
                                if (isLeft) {
                                    -player.seekBackIncrement
                                } else {
                                    player.seekForwardIncrement
                                }
                            val targetPosition =
                                (player.currentPosition + delta).coerceIn(0, duration)
                            val progress = targetPosition.toFloat() / duration.toFloat()
                            Log.d(
                                TAG,
                                "handleLeftRight preview: isLeft=$isLeft, duration=$duration, delta=$delta, target=$targetPosition, progress=$progress",
                            )
                            onPreviewProgressChange?.invoke(progress)
                        }
                        if (isLeft) {
                            updateSkipIndicator(-player.seekBackIncrement)
                            player.seekBack()
                        } else {
                            player.seekForward()
                            updateSkipIndicator(player.seekForwardIncrement)
                        }
                        return true // Event handled
                    }
                } else if (event.type == KeyEventType.KeyUp) {
                    // Notify that key is released
                    onLeftRightKeyStateChanged?.invoke(false)
                }
                return false // Let it propagate if not handled
            }
        }
    }
    
    /**
     * Handle up/down arrow keys:
     * - Enhanced subtitles enabled: Handle up/down arrow with double-click detection
     * - Default behavior: Media navigation or show controls
     * Note: This function is only called when control bar is hidden
     */
    private fun handleUpDownArrowKey(event: KeyEvent) {
                // Handle up/down keys on KeyUp for media navigation
        if (event.type == KeyEventType.KeyUp) {
            // Enhanced subtitle navigation takes priority when enabled
            if (enhancedSubtitlesEnabled && enhancedSubtitleViewModel != null) {
                when (event.key) {
                    Key.DirectionUp -> {
                        // If auto-paused, resume immediately without waiting for
                        // double-click timeout to avoid feeling unresponsive.
                        if (enhancedSubtitleViewModel.isAutoPaused.value) {
                            enhancedSubtitleViewModel.notifyUserResumed()
                            player.play()
                            enhancedSubtitleViewModel.exitWordNavigationMode()
                            // Record current time for double-click detection
                            // (don't set to 0L, which breaks double-click after auto-pause)
                            val now = System.currentTimeMillis()
                            lastUpArrowPress = now
                            upArrowHandler?.removeCallbacksAndMessages(null)
                            upArrowHandler = null
                        } else {
                            handleUpArrowKey()
                        }
                        return
                    }
                    Key.DirectionDown -> {
                        handleDownArrowKey()
                        return
                    }
                    else -> { /* Fall through to default behavior */ }
                }
            }
            
            // Default behavior when enhanced subtitles not enabled or not up/down
            if (nextWithUpDown && event.key == Key.DirectionUp) {
                        player.seekToPreviousMediaItem()
                return
            } else if (nextWithUpDown && event.key == Key.DirectionDown) {
                        player.seekToNextMediaItem()
                return
            } else if (event.key == Key.DirectionUp || event.key == Key.DirectionDown) {
                // Only show controls for up/down keys
                        controllerViewState.showControls()
                return
            }
        }
    }
    
    /**
     * Handle down arrow key with double-click detection:
     * - Single click: Repeat current subtitle (seek to current subtitle start) and play, exiting word selection mode
     * - Double click (within 400ms): Seek to previous subtitle and play, exiting word selection mode
     */
    private fun handleDownArrowKey() {
        val now = System.currentTimeMillis()
        val timeSinceLastPress = now - lastDownArrowPress
        
        // Cancel any pending single-click handler
        downArrowHandler?.removeCallbacksAndMessages(null)
        downArrowHandler = null
        
        if (timeSinceLastPress < doubleClickDelayMs && timeSinceLastPress > 0) {
            // Double click detected - seek to previous subtitle
            lastDownArrowPress = 0L
            val currentTimeSeconds = (player.currentPosition / 1000.0).coerceAtLeast(0.0)
            val prevCueTime = enhancedSubtitleViewModel?.seekToPreviousCue(currentTimeSeconds)
            if (prevCueTime != null) {
                player.seekTo((prevCueTime * 1000).toLong())
                if (!player.isPlaying) {
                    player.play()
                }
                // Exit word selection mode when seeking to previous subtitle
                enhancedSubtitleViewModel?.exitWordNavigationMode()
            }
        } else {
            // Single click - will trigger after delay if no second press
            lastDownArrowPress = now
            
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({
                val currentTime = System.currentTimeMillis()
                val timeSincePress = currentTime - lastDownArrowPress
                
                // Only execute if this was a single press (not a double press)
                if (timeSincePress >= doubleClickDelayMs && lastDownArrowPress != 0L) {
                    // Single press - repeat current subtitle and play
                    val currentTimeSeconds = (player.currentPosition / 1000.0).coerceAtLeast(0.0)
                    val currentCueStartTime = enhancedSubtitleViewModel?.seekToCurrentCueStart(currentTimeSeconds)
                    if (currentCueStartTime != null) {
                        player.seekTo((currentCueStartTime * 1000).toLong())
                        if (!player.isPlaying) {
                            player.play()
                        }
                        // Exit word selection mode when replaying current subtitle
                        enhancedSubtitleViewModel?.exitWordNavigationMode()
                    }
                    lastDownArrowPress = 0L
                }
            }, doubleClickDelayMs)
            downArrowHandler = handler
        }
    }
    
    fun onKeyEvent(it: KeyEvent): Boolean {
        var result = true
        if (!controlsEnabled) {
            result = false
        } else if (it.key == Key.DirectionCenter || it.key == Key.Enter || it.key == Key.NumPadEnter) {
            // OK/Enter key: handle enhanced subtitle word selection or play/pause
            if (it.type == KeyEventType.KeyUp) {
                Log.d("PlaybackPageContent", "OK/Enter key pressed: enhancedSubtitlesEnabled=$enhancedSubtitlesEnabled, enhancedSubtitleViewModel=${enhancedSubtitleViewModel != null}")
                
                // Enhanced subtitle word selection takes priority
                // If there's a selected word, lookup it first (even when auto-paused)
                if (enhancedSubtitlesEnabled && enhancedSubtitleViewModel != null) {
                    val selectedWordIndex = enhancedSubtitleViewModel.selectedWordIndex.value
                    val isInWordNavMode = enhancedSubtitleViewModel.isInWordNavigationMode.value
                    Log.d("PlaybackPageContent", "OK/Enter: selectedWordIndex=$selectedWordIndex, isInWordNavigationMode=$isInWordNavMode")
                    
                    // If there's a selected word (has valid index), lookup it
                    if (selectedWordIndex >= 0 || isInWordNavMode) {
                        Log.d("PlaybackPageContent", "OK/Enter: calling selectCurrentWord()")
                        enhancedSubtitleViewModel.selectCurrentWord()
                        return true
                    }
                }
                
                // If enhanced subtitles auto-paused the player and no word is selected,
                // a single OK/Enter should resume playback
                if (enhancedSubtitlesEnabled && enhancedSubtitleViewModel != null &&
                    enhancedSubtitleViewModel.isAutoPaused.value
                ) {
                    Log.d("PlaybackPageContent", "OK/Enter: auto-paused, no word selected, resuming playback")
                    enhancedSubtitleViewModel.notifyUserResumed()
                    player.play()
                    return true
                }
                // Normal behavior: play/pause toggle
                // This works whether enhanced subtitles are enabled or not
                Log.d("PlaybackPageContent", "OK/Enter: normal play/pause behavior")
                if (player.isPlaying) {
                    player.pause()
                    // Don't show controls when enhanced subtitles are enabled
                    if (!enhancedSubtitlesEnabled) {
                        controllerViewState.showControls()
                    }
                } else {
                    player.play()
                }
                return true
            }
            result = false
        } else if (isDpad(it)) {
            when (it.key) {
                Key.DirectionLeft, Key.DirectionRight -> {
                    // Left/Right keys: handle seek when control bar visible, or word navigation when hidden
                    val handled = handleLeftRightArrowKey(it)
                    return handled
                }
                Key.DirectionUp, Key.DirectionDown -> {
                    // Up/Down keys: let control bar handle when visible, or handle subtitle navigation when hidden
                    if (controllerViewState.controlsVisible) {
                        // Control bar visible: let it handle navigation
                        return false
                    } else {
                        // Control bar hidden: handle subtitle navigation or default behavior
                        handleUpDownArrowKey(it)
                        return true
                    }
                }
                else -> { /* Other DPad keys */ }
            }
            
            // Fallback for other DPad keys
            if (!controllerViewState.controlsVisible) {
                // Default behavior for other DPad keys when control bar hidden
                result = true
            } else {
                // When controller is visible, let the control bar handle navigation for other keys
                result = false
            }
        } else if (it.key == Key.Menu) {
            // Menu key: simply show the controls when hidden
            if (it.type == KeyEventType.KeyUp) {
                controllerViewState.showControls()
            }
            return true
        } else if (isMedia(it)) {
            // Media keys should only trigger on KeyUp to avoid multiple triggers
            if (it.type == KeyEventType.KeyUp) {
                when (it.key) {
                    Key.MediaPlay -> {
                        Util.handlePlayButtonAction(player)
                    }

                    Key.MediaPause -> {
                        Util.handlePauseButtonAction(player)
                        controllerViewState.showControls()
                    }

                    Key.MediaPlayPause -> {
                        Util.handlePlayPauseButtonAction(player)
                        if (!player.isPlaying) {
                            controllerViewState.showControls()
                        }
                    }

                    Key.MediaFastForward, Key.MediaSkipForward -> {
                        player.seekForward()
                        updateSkipIndicator(player.seekForwardIncrement)
                    }

                    Key.MediaRewind, Key.MediaSkipBackward -> {
                        player.seekBack()
                        updateSkipIndicator(-player.seekBackIncrement)
                    }

                    Key.MediaNext -> if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)) player.seekToNext()
                    Key.MediaPrevious -> if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)) player.seekToPrevious()
                    else -> result = false
                }
            } else {
                result = false
            }
        } else if (it.key == Key.Back) {
            if (it.type == KeyEventType.KeyUp) {
                // Enhanced subtitle word navigation mode takes priority
                if (enhancedSubtitlesEnabled && enhancedSubtitleViewModel != null && 
                    enhancedSubtitleViewModel.isInWordNavigationMode.value) {
                    // Exit word navigation mode
                    val wasAutoPaused = enhancedSubtitleViewModel.isAutoPaused.value
                    enhancedSubtitleViewModel.exitWordNavigationMode()
                    
                    // If was auto-paused, resume playback
                    if (wasAutoPaused && !player.isPlaying) {
                        Log.d("PlaybackPageContent", "Back key: exiting word nav mode, resuming playback from auto-pause")
                        enhancedSubtitleViewModel.notifyUserResumed()
                        player.play()
                    }
                    return true
                } else if (controllerViewState.controlsVisible) {
                    // Hide control bar
                controllerViewState.hideControls()
                if (!isTvDevice) {
                    // Allow to propagate up
                        result = false
                    }
                } else {
                    // Default behavior (allow propagation)
                    result = false
                }
            } else {
                result = false
            }
        } else {
            controllerViewState.pulseControls()
            result = false
        }
        return result
    }
}

@OptIn(UnstableApi::class)
fun toggleSubtitles(
    player: Player,
    currentActiveSubtitleIndex: Int?,
    index: Int,
): Boolean {
    val subtitleTracks =
        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
    if (index !in subtitleTracks.indices || currentActiveSubtitleIndex != null && currentActiveSubtitleIndex == index) {
        Log.v(
            TAG,
            "Deactivating subtitles",
        )
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        return false
    } else {
        Log.v(
            TAG,
            "Activating subtitle ${subtitleTracks[index].mediaTrackGroup.id}",
        )
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(
                    TrackSelectionOverride(
                        subtitleTracks[index].mediaTrackGroup,
                        0,
                    ),
                ).build()
        return true
    }
}

@OptIn(UnstableApi::class)
fun toggleAudio(
    player: Player,
    audioIndex: Int?,
    index: Int,
): Boolean {
    val audioTracks =
        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
    if (index !in audioTracks.indices || audioIndex != null && audioIndex == index) {
        Log.v(
            TAG,
            "Deactivating audio",
        )
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        return false
    } else {
        Log.v(
            TAG,
            "Activating audio ${audioTracks[index].mediaTrackGroup.id}",
        )
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setOverrideForType(
                    TrackSelectionOverride(
                        audioTracks[index].mediaTrackGroup,
                        0,
                    ),
                ).build()
        return true
    }
}
