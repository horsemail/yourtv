package com.horsenma.yourtv

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.horsenma.yourtv.data.SourceType
import com.horsenma.yourtv.databinding.PlayerBinding
import com.horsenma.yourtv.models.TVModel
import androidx.media3.ui.PlayerView
import com.horsenma.yourtv.data.StableSource
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.horsenma.yourtv.data.TV
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.core.view.isVisible


class PlayerFragment : Fragment() {
    private lateinit var viewModel: MainViewModel
    fun setViewModel(viewModel: MainViewModel) {
        this.viewModel = viewModel
    }
    private val stablePlaybackDuration = 30_000L
    private var isStable = false
    private var _binding: PlayerBinding? = null
    private val binding get() = _binding!!

    internal var player: ExoPlayer? = null
    private var tvModel: TVModel? = null

    private val aspectRatio = 16f / 9f
    internal var isInPictureInPictureMode = false

    private val handler = Handler(Looper.myLooper()!!)
    private val delayHideVolume = 2 * 1000L

    // 新增：缓冲检测变量
    private val bufferingThreshold = 5
    private val bufferingDurationThreshold = 8_000L
    private val switchCooldown = 15_000L
    private val stablePlaybackThreshold = 10_000L
    private var bufferingStartTime = 0L
    private var bufferingCount = 0
    private var lastSwitchTime = 0L
    private var playbackStartTime = 0L
    private val bufferingTimestamps = mutableListOf<Long>()
    private var lastBufferingTime = 0L
    private var isSourceButtonVisible = false

    // 新增：播放停止检测变量
    private var lastStopTime = 0L
    private val stopDurationThreshold = 5_000L
    private val retryCooldown = 30_000L
    private val checkPlaybackInterval = 15_000L
    private val stableSourceCheckRunnable = Runnable {
        if (player?.isPlaying == true && tvModel != null &&
            System.currentTimeMillis() - playbackStartTime >= stablePlaybackDuration &&
            bufferingCount == 0 && tvModel!!.retryTimes == 0) {
            isStable = true
            saveStableSource(tvModel!!)
        }
    }
    private var lastPauseTime = 0L

    // 进入画中画模式
    @OptIn(UnstableApi::class)
    fun enterPictureInPictureMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isTouchScreenDevice()) {
            // 获取视频的实际宽高比
            val videoSize = player?.videoSize
            val aspectRatio = if (videoSize != null && videoSize.width > 0 && videoSize.height > 0) {
                val ratio = videoSize.width.toFloat() / videoSize.height
                // 限制宽高比在 2.39:1 到 1:2.39 范围内
                when {
                    ratio > 2.39f -> Rational(239, 100)
                    ratio < 1 / 2.39f -> Rational(100, 239)
                    else -> Rational(videoSize.width, videoSize.height)
                }
            } else {
                Rational(16, 9) // 默认 16:9
            }

            val params = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .build()
            requireActivity().enterPictureInPictureMode(params)
            // 确保 PlayerView 使用等比率缩放
            if (_binding != null) {
                binding.playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                binding.playerView.useController = false // 保持禁用内置控件
                binding.playerView.requestLayout()
                setSourceButtonVisibility(false) // 隐藏切换源按钮
                binding.icon.visibility = View.GONE // 隐藏音量图标
                binding.volume.visibility = View.GONE // 隐藏音量条
                binding.playerView.clearFocus() // 防止抢夺焦点
            }
            isInPictureInPictureMode = true
            Log.d(TAG, "Entering Picture-in-Picture mode with aspectRatio=$aspectRatio")
        } else {
            Log.d(TAG, "Picture-in-Picture mode skipped: Not a touchscreen device or API level < O")
        }
    }

    // 新增：退出画中画模式
    @OptIn(UnstableApi::class)
    fun exitPictureInPictureMode() {
        if (_binding != null) {
            binding.playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            binding.playerView.useController = false // 保持禁用内置控件，与 player.xml 一致
            binding.playerView.requestLayout()
            binding.playerView.clearFocus() // 防止 PlayerView 抢夺焦点
            setSourceButtonVisibility(isTouchScreenDevice() && SP.showSourceButton) // 恢复 btn_source
            isInPictureInPictureMode = false
            Log.d(TAG, "Exiting Picture-in-Picture mode, btn_source visible=${binding.btnSource.isVisible}")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = PlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
            updatePlayer()
            binding.playerView.isFocusable = true
            binding.playerView.isFocusableInTouchMode = true
            binding.playerView.requestFocus()
            Log.d(TAG, "PlayerView focus requested: isFocusable=${binding.playerView.isFocusable}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PlayerFragment view: ${e.message}", e)
        }
        updatePlayer()
        (activity as MainActivity).ready()

        val btnSource = view.findViewById<Button>(R.id.btn_source)
        // 初始化 btn_source 可见性
        setSourceButtonVisibility(isTouchScreenDevice() && SP.showSourceButton)
        Log.d(TAG, "btn_source initialized: visibility=${btnSource.isVisible}, isTouchScreen=${isTouchScreenDevice()}, showSourceButton=${SP.showSourceButton}")

        // 设置 btn_source 的双击手势监听
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (btnSource.isEnabled && btnSource.isVisible) {
                    (activity as? MainActivity)?.sourceUp()
                    Log.d(TAG, "btn_source double tapped, triggering sourceUp")
                    return true
                }
                return false
            }
        })

        // 确保 btn_source 优先接收触摸事件
        btnSource.setOnTouchListener { _, event ->
            if (btnSource.isEnabled && btnSource.isVisible) {
                gestureDetector.onTouchEvent(event)
                true // 消耗事件，防止 PlayerView 拦截
            } else {
                false // 不可见或禁用时透传事件
            }
        }

        // 防止 PlayerView 拦截 btn_source 的事件
        binding.playerView.setOnTouchListener { _, event ->
            val buttonRect = android.graphics.Rect()
            btnSource.getGlobalVisibleRect(buttonRect)
            if (btnSource.isVisible && buttonRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                btnSource.dispatchTouchEvent(event)
                true // btn_source 区域内的事件由按钮处理
            } else {
                false // 其他区域的事件透传给 MainActivity
            }
        }
    }

    // 控制 btn_source 可见性
    @OptIn(UnstableApi::class)
    fun setSourceButtonVisibility(visible: Boolean) {
        val btnSource = binding.root.findViewById<Button>(R.id.btn_source) ?: return
        val shouldShow = if (!visible) {
            false // 画中画模式下始终隐藏
        } else {
            isTouchScreenDevice() && SP.showSourceButton
        }
        btnSource.visibility = if (shouldShow) View.VISIBLE else View.GONE
        btnSource.isFocusable = shouldShow
        btnSource.isEnabled = shouldShow
        btnSource.isFocusableInTouchMode = shouldShow // 确保触摸交互
        isSourceButtonVisible = shouldShow
        Log.d(TAG, "setSourceButtonVisibility: visible=$visible, shouldShow=$shouldShow, isTouchScreen=${isTouchScreenDevice()}, showSourceButton=${SP.showSourceButton}, btnSource.focusable=${btnSource.isFocusable}")
    }

    // 新增：播放状态回调接口
    interface PlaybackCallback {
        fun onPlaybackStarted()
    }

    private var playbackCallback: PlaybackCallback? = null

    // 新增：设置回调
    fun setPlaybackCallback(callback: PlaybackCallback) {
        this.playbackCallback = callback
    }

    @OptIn(UnstableApi::class)
    fun updatePlayer() {
        if (context == null) {
            Log.e(TAG, "context == null")
            return
        }

        val ctx = requireContext()
        val playerView = binding.playerView
        val renderersFactory = DefaultRenderersFactory(ctx)
        val playerMediaCodecSelector = PlayerMediaCodecSelector()
        renderersFactory.setMediaCodecSelector(playerMediaCodecSelector)
        renderersFactory.setExtensionRendererMode(
            if (SP.softDecode) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
        )

        if (player != null) {
            player?.release()
        }

        player = ExoPlayer.Builder(ctx)
            .setRenderersFactory(renderersFactory)
            .build()
        player?.repeatMode = REPEAT_MODE_ALL
        player?.playWhenReady = true
        player?.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                // 保留原始逻辑
                if (!isInPictureInPictureMode) {
                    val ratio = playerView.measuredWidth.div(playerView.measuredHeight)
                    val layoutParams = playerView.layoutParams
                    if (ratio < aspectRatio) {
                        layoutParams?.height = (playerView.measuredWidth.div(aspectRatio)).toInt()
                        playerView.layoutParams = layoutParams
                    } else if (ratio > aspectRatio) {
                        layoutParams?.width = (playerView.measuredHeight.times(aspectRatio)).toInt()
                        playerView.layoutParams = layoutParams
                    }
                }
                Log.d(TAG, "Video size changed: ${videoSize.width}x${videoSize.height}")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (tvModel == null) {
                    Log.e(TAG, "tvModel == null")
                    return
                }

                val tv = tvModel!!
                if (isPlaying) {
                    tv.confirmSourceType()
                    tv.confirmVideoIndex()
                    tv.setErrInfo("")
                    tv.retryTimes = 0
                    bufferingCount = 0
                    bufferingStartTime = 0L
                    bufferingTimestamps.clear()
                    lastBufferingTime = 0L
                    playbackStartTime = System.currentTimeMillis()
                    playbackCallback?.onPlaybackStarted()
                    lastStopTime = 0L // 重置停止时间
                    Log.d(TAG, "${tv.tv.title} is playing")

                    // Check stability after 30 seconds
                    handler.removeCallbacks(stableSourceCheckRunnable) // 防止重复调度
                    handler.postDelayed(stableSourceCheckRunnable, stablePlaybackDuration)
                } else {
                    isStable = false
                    playbackStartTime = 0L // 重置计时
                    lastStopTime = System.currentTimeMillis() // 记录停止时间
                    handler.removeCallbacks(stableSourceCheckRunnable)
                    Log.i(TAG, "${tv.tv.title} 播放停止")
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (!SP.autoSwitchSource) return
                if (tvModel == null || player == null) {
                    return
                }

                val currentTime = System.currentTimeMillis()

                // 检查是否处于播放稳定期（启动或切换源后10秒内不监控缓冲）
                if (currentTime - playbackStartTime < stablePlaybackThreshold) {
                    if (state == Player.STATE_READY) {
                        // 播放稳定后更新开始时间
                        playbackStartTime = currentTime
                    }
                    return
                }

                // 检测缓冲状态
                if (state == Player.STATE_BUFFERING) {
                    // 过滤快速重复缓冲（小于500ms的忽略）
                    if (currentTime - lastBufferingTime < 500L) {
                        return
                    }

                    if (bufferingStartTime == 0L) {
                        bufferingStartTime = currentTime
                    }
                    lastBufferingTime = currentTime
                    bufferingTimestamps.add(currentTime)
                    // 统计最近10秒内的缓冲次数
                    bufferingCount = bufferingTimestamps.count { it >= currentTime - 10_000L }
                    val bufferingDuration = currentTime - bufferingStartTime

                    // 清理过旧的时间戳
                    bufferingTimestamps.removeAll { it < currentTime - 10_000L }

                    // 检查是否需要切换源
                    if ((bufferingCount >= bufferingThreshold && currentTime - lastSwitchTime >= switchCooldown) ||
                        (bufferingDuration >= bufferingDurationThreshold && currentTime - lastSwitchTime >= switchCooldown)) {
                        if (tvModel!!.retryTimes < tvModel!!.retryMaxTimes && player!!.currentPosition > 0) {
                            Log.i(TAG, "Non-smooth playback detected: bufferingCount=$bufferingCount, duration=$bufferingDuration")
                            (activity as MainActivity).sourceUp()
                            lastSwitchTime = currentTime
                            playbackStartTime = currentTime // 重置播放开始时间
                            bufferingCount = 0
                            bufferingStartTime = 0L
                            bufferingTimestamps.clear()
                            lastBufferingTime = 0L
                        }
                    }
                } else if (state == Player.STATE_READY) {
                    // 播放流畅时重置缓冲变量（如果持续流畅超过2秒）
                    if (currentTime - lastBufferingTime >= 2_000L) {
                        bufferingStartTime = 0L
                        bufferingCount = 0
                        bufferingTimestamps.clear()
                        lastBufferingTime = 0L
                    }
                } else if (state == Player.STATE_ENDED) {
                    // 播放结束时重置所有变量
                    bufferingStartTime = 0L
                    bufferingCount = 0
                    bufferingTimestamps.clear()
                    lastBufferingTime = 0L
                    playbackStartTime = 0L
                    lastStopTime = currentTime // 记录停止时间
                    Log.w(TAG, "${tvModel!!.tv.title} playback ended, marking for retry, lastStopTime=$lastStopTime, cooldownRemaining=${if (currentTime - lastSwitchTime < retryCooldown) retryCooldown - (currentTime - lastSwitchTime) else 0}")
                    // 优化：立即触发重试
                    if (!isInPictureInPictureMode) {
                        Log.w(TAG, "${tvModel!!.tv.title} ended, retrying immediately")
                        switchSource(tvModel!!)
                        lastSwitchTime = currentTime
                        lastStopTime = 0L
                    }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    (activity as MainActivity).onPlayEnd()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Player error: ${error.errorCode}, message=${error.message}")
                lastStopTime = System.currentTimeMillis()
                Log.w(TAG, "Marking for retry: lastStopTime=$lastStopTime, cooldownRemaining=${if (System.currentTimeMillis() - lastSwitchTime < retryCooldown) retryCooldown - (System.currentTimeMillis() - lastSwitchTime) else 0}")
                // 优化：即使 autoSwitchSource 禁用，也触发重试
                if (System.currentTimeMillis() - lastSwitchTime >= retryCooldown && !isInPictureInPictureMode) {
                    Log.w(TAG, "${tvModel?.tv?.title} error, retrying immediately")
                    tvModel?.let { switchSource(it) }
                    lastSwitchTime = System.currentTimeMillis()
                    lastStopTime = 0L
                }
                // 首次使用检测：无稳定源且 cacheFile 不存在
                // val isFirstUse = SP.getStableSources().isEmpty() && !File(requireContext().filesDir, "cacheFile").exists()
                val isFirstUse = SP.getStableSources().isEmpty()
                if (isFirstUse && tvModel != null) {
                    if (tvModel!!.retryTimes < 3) { // 限制为 3 次
                        tvModel!!.nextSourceType() // 尝试下一个源类型
                        tvModel!!.setReady(true)
                        tvModel!!.retryTimes++
                        Log.i(TAG, "First use: Error detected, switching source for ${tvModel!!.tv.title}")
                        (activity as MainActivity).sourceUp()
                        lastSwitchTime = System.currentTimeMillis()
                        // 快速超时：3 秒后若未播放，触发下一次切换
                        handler.postDelayed({
                            if (player?.isPlaying != true) {
                                (activity as MainActivity).sourceUp()
                                Log.i(TAG, "First use: 3s timeout, retry switching for ${tvModel!!.tv.title}")
                            }
                        }, 3_000L)
                        return
                    } else if (!tvModel!!.isLastVideo()) {
                        tvModel!!.nextVideo() // 尝试下一个视频源
                        tvModel!!.setReady(true)
                        tvModel!!.retryTimes = 0
                        Log.i(TAG, "First use: All source types failed, switching video for ${tvModel!!.tv.title}")
                        (activity as MainActivity).sourceUp()
                        lastSwitchTime = System.currentTimeMillis()
                        handler.postDelayed({
                            if (player?.isPlaying != true) {
                                (activity as MainActivity).sourceUp()
                                Log.i(TAG, "First use: 3s timeout, retry switching for ${tvModel!!.tv.title}")
                            }
                        }, 3_000L)
                        return
                    } else {
                        // 所有源无效，尝试下一个频道
                        val nextChannel = viewModel.groupModel.getNext()
                        if (nextChannel != null) {
                            nextChannel.setReady()
                            viewModel.groupModel.setCurrent(nextChannel)
                            switchSource(nextChannel)
                            Log.d(TAG, "First use: Fell back to next channel: ${nextChannel.tv.title}")
                            lastSwitchTime = System.currentTimeMillis()
                            handler.postDelayed({
                                if (player?.isPlaying != true) {
                                    (activity as MainActivity).sourceUp()
                                    Log.i(TAG, "First use: 3s timeout, retry switching for ${nextChannel.tv.title}")
                                }
                            }, 3_000L)
                            return
                        } else {
                            tvModel!!.setErrInfo(R.string.play_error.getString())
                            Log.w(TAG, "First use: No next channel available")
                            return
                        }
                    }
                }

                if (!SP.autoSwitchSource) {
                    Log.w(TAG, "Auto-switch disabled, ignoring error: ${error.message}")
                    return
                }
                if (tvModel == null) {
                    Log.e(TAG, "tvModel == null")
                    return
                }

                if (error.errorCode !in listOf(
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                    )) {
                    Log.w(TAG, "Non-supported error: ${error.errorCode}, ignoring")
                    return
                }

                val tv = tvModel!!
                if (tv.retryTimes < tv.retryMaxTimes) {
                    var last = true
                    if (tv.getSourceTypeDefault() == SourceType.UNKNOWN) {
                        last = tv.nextSourceType()
                    }
                    tv.setReady(true)
                    if (last) {
                        tv.retryTimes++
                    }
                    Log.i(
                        TAG,
                        "Retry ${tv.videoIndex.value} ${tv.getSourceTypeCurrent()} ${tv.retryTimes}/${tv.retryMaxTimes}"
                    )
                    if (System.currentTimeMillis() - lastSwitchTime >= switchCooldown) {
                        handler.postDelayed({
                            if (player?.isPlaying != true) {
                                (activity as MainActivity).sourceUp()
                            } else {
                                Log.d(TAG, "Playback recovered, no need to switch")
                            }
                        }, 2_000L)
                        lastSwitchTime = System.currentTimeMillis()
                    }
                } else {
                    if (!tv.isLastVideo()) {
                        tv.nextVideo()
                        tv.setReady(true)
                        tv.retryTimes = 0
                        (activity as MainActivity).sourceUp()
                    } else {
                        // Fallback to stable source
                        lifecycleScope.launch(Dispatchers.Main) {
                            val stableSource = selectRandomStableSource()
                            if (stableSource != null) {
                                val newTvModel = TVModel(
                                    TV(
                                        id = stableSource.id,
                                        name = stableSource.name,
                                        title = stableSource.title,
                                        description = stableSource.description,
                                        logo = stableSource.logo,
                                        image = stableSource.image,
                                        uris = stableSource.uris,
                                        videoIndex = stableSource.videoIndex,
                                        headers = stableSource.headers,
                                        group = stableSource.group,
                                        sourceType = SourceType.valueOf(stableSource.sourceType),
                                        number = stableSource.number,
                                        child = stableSource.child
                                    )
                                ).apply {
                                    setLike(SP.getLike(stableSource.id))
                                    setGroupIndex(2)
                                    listIndex = 0
                                }
                                viewModel.groupModel.setCurrent(newTvModel)
                                switchSource(newTvModel)
                                Log.d(TAG, "Fell back to stable source: ${newTvModel.tv.title}, url: ${newTvModel.getVideoUrl()}")
                                return@launch
                            }
                            // No stable sources, try next channel
                            val nextChannel = viewModel.groupModel.getNext()
                            if (nextChannel != null) {
                                nextChannel.setReady()
                                viewModel.groupModel.setCurrent(nextChannel)
                                switchSource(nextChannel)
                                Log.d(TAG, "Fell back to next channel: ${nextChannel.tv.title}")
                            } else {
                                tv.setErrInfo(R.string.play_error.getString())
                                Log.w(TAG, "No stable sources or next channel available")
                            }
                        }
                    }
                }
            }
        })

        playerView.player = player
        tvModel?.let {
            play(it)
        }

        // 修复：移动定时器启动到末尾
        handler.removeCallbacks(checkPlaybackRunnable)
        handler.postDelayed(checkPlaybackRunnable, checkPlaybackInterval)
    }

    // 优化：增强 checkPlaybackRunnable 日志
    private val checkPlaybackRunnable = object : Runnable {
        @OptIn(UnstableApi::class)
        override fun run() {
            val currentTime = System.currentTimeMillis()
            if (player != null && tvModel != null && !isInPictureInPictureMode && isResumed) {
                // 新增：检查暂停时间，避免 onPause 误触发
                if (lastPauseTime > lastStopTime && currentTime - lastPauseTime < stopDurationThreshold) {
                    Log.d(TAG, "Playback check skipped: recent pause at $lastPauseTime")
                    handler.postDelayed(this, checkPlaybackInterval)
                    return
                }
                if (!player!!.isPlaying && lastStopTime > 0 &&
                    currentTime - lastStopTime >= stopDurationThreshold &&
                    currentTime - lastSwitchTime >= retryCooldown) {
                    Log.w(TAG, "${tvModel!!.tv.title} stopped for ${stopDurationThreshold / 1000}s, retrying")
                    switchSource(tvModel!!)
                    lastSwitchTime = currentTime
                    lastStopTime = 0L
                } else {
                    Log.d(TAG, "Playback check: isPlaying=${player!!.isPlaying}, lastStopTime=$lastStopTime, " +
                            "stopDuration=${if (lastStopTime > 0) currentTime - lastStopTime else 0}, " +
                            "cooldownRemaining=${if (currentTime - lastSwitchTime < retryCooldown) retryCooldown - (System.currentTimeMillis() - lastSwitchTime) else 0}, " +
                            "isResumed=$isResumed, isInPip=$isInPictureInPictureMode")
                }
            } else {
                Log.d(TAG, "Playback check skipped: player=$player, tvModel=$tvModel, isResumed=$isResumed, isInPip=$isInPictureInPictureMode")
            }
            handler.postDelayed(this, checkPlaybackInterval)
        }
    }

    private fun selectRandomStableSource(): StableSource? {
        val stableSources = SP.getStableSources()
        return if (stableSources.isNotEmpty()) {
            stableSources.sortedByDescending { it.timestamp }.firstOrNull()
        } else {
            null
        }
    }

    private fun saveStableSource(tvModel: TVModel) {
        lifecycleScope.launch(Dispatchers.IO) {
            val currentSources = SP.getStableSources()
            val tv = tvModel.tv
            val newSource = StableSource(
                id = tv.id,
                name = tv.name,
                title = tv.title,
                description = tv.description,
                logo = tv.logo,
                image = tv.image,
                uris = tv.uris,
                videoIndex = tvModel.videoIndexValue,
                headers = tv.headers,
                group = tv.group,
                sourceType = tvModel.getSourceTypeCurrent().name,
                number = tv.number,
                child = tv.child,
                timestamp = System.currentTimeMillis()
            )

            // 移除重复项并按时间戳降序排序，限制10个
            val updatedSources = (currentSources.filter {
                it.id != newSource.id || it.videoIndex != newSource.videoIndex || it.sourceType != newSource.sourceType
            } + newSource).sortedByDescending { it.timestamp }.take(10)

            SP.setStableSources(updatedSources)
            Log.d(TAG, "Saved stable source: ${newSource.title}, url: ${newSource.uris.getOrNull(newSource.videoIndex)}")
        }
    }

    @OptIn(UnstableApi::class)
    fun ensurePlaying() {
        player?.run {
            if (!isPlaying && tvModel != null) {
                prepare()
                playWhenReady = true
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun switchSource(tvModel: TVModel) {
        Toast.makeText(requireContext(), "正在切换直播源", Toast.LENGTH_SHORT).show()
        this.tvModel = tvModel
        Log.d(TAG, "Switching source: ${tvModel.tv.title}, url: ${tvModel.getVideoUrl()}")
        player?.run {
            tvModel.getVideoUrl() ?: run {
                Log.w(TAG, "No valid URL for ${tvModel.tv.title}")
                tvModel.setErrInfo(R.string.play_error.getString())
                return
            }
            val mediaItem = tvModel.getMediaItem()
            if (mediaItem == null) {
                Log.w(TAG, "No valid mediaItem for ${tvModel.tv.title}")
                tvModel.setErrInfo(R.string.play_error.getString())
                return
            }
            stop()
            clearMediaItems()
            val mediaSource = tvModel.getMediaSource()
            try {
                if (mediaSource != null) {
                    setMediaSource(mediaSource)
                } else {
                    setMediaItem(mediaItem)
                }
                prepare()
                playWhenReady = true
                Log.d(TAG, "Switched to source: ${tvModel.tv.title}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch source for ${tvModel.tv.title}: ${e.message}", e)
                tvModel.setErrInfo(R.string.play_error.getString())
            }
        } ?: Log.w(TAG, "Player is null, cannot switch source for ${tvModel.tv.title}")
    }

    @OptIn(UnstableApi::class)
    fun play(tvModel: TVModel) {
        this.tvModel = tvModel
        Log.d(TAG, "Playing tvModel: ${tvModel.tv.title}, uris: ${tvModel.tv.uris.size}")

        // 强制确保 PlayerView 可见并置于顶层
        try {
            binding.playerView.visibility = View.VISIBLE
            binding.playerView.bringToFront()
            binding.playerView.requestFocus()
            binding.playerView.requestLayout()
            binding.playerView.player = null
            binding.playerView.player = player
            binding.playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS) // 显示缓冲状态
            Log.d(TAG, "PlayerView visibility: ${binding.playerView.visibility}, isAttached: ${binding.playerView.isAttachedToWindow}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PlayerView: ${e.message}", e)
            return
        }

        player?.run {
            val videoUrl = tvModel.getVideoUrl()
            if (videoUrl == null) {
                Log.w(TAG, "getVideoUrl failed for ${tvModel.tv.title}")
                tvModel.setErrInfo(R.string.play_error.getString())
                return
            }

            val mediaItem = tvModel.getMediaItem()
            if (mediaItem == null) {
                Log.w(TAG, "No valid mediaItem for ${tvModel.tv.title}")
                tvModel.setErrInfo(R.string.play_error.getString())
                return
            }
            val mediaSource = tvModel.getMediaSource()
            try {
                if (mediaSource != null) {
                    setMediaSource(mediaSource)
                } else {
                    setMediaItem(mediaItem)
                }
                prepare()
                playWhenReady = true
                Log.d(TAG, "Playback started for ${tvModel.tv.title}")
            } catch (e: Exception) {
                Log.e(TAG, "Playback failed for ${tvModel.tv.title}: ${e.message}", e)
                tvModel.setErrInfo(R.string.play_error.getString())
            }
        } ?: Log.w(TAG, "Player is null, cannot play ${tvModel.tv.title}")
    }

    @OptIn(UnstableApi::class)
    fun updateSource() {
        tvModel?.let { model ->
            player?.run {
                stop()
                clearMediaItems()
                play(model)
            }
        }
    }

    private fun isTouchScreenDevice(): Boolean {
        val context = context ?: return false
        val packageManager = context.packageManager
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        val isTv = uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val hasTouchScreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        return hasTouchScreen && !isTv
    }

    @OptIn(UnstableApi::class)
    class PlayerMediaCodecSelector : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean
        ): MutableList<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
            val infos = MediaCodecUtil.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
            if (SP.softDecode) {
                val softwareCodecs = infos.filter { !it.hardwareAccelerated }
                if (softwareCodecs.isNotEmpty()) {
                    return softwareCodecs.toMutableList()
                }
            } else if (mimeType.startsWith("audio/")) {
                val softwareCodecs = infos.filter { !it.hardwareAccelerated }
                if (softwareCodecs.isNotEmpty()) {
                    return softwareCodecs.toMutableList()
                }
            }
            if (mimeType == MimeTypes.VIDEO_H265 && !requiresSecureDecoder && !requiresTunnelingDecoder) {
                if (infos.isNotEmpty()) {
                    val infosNew = infos.find { it.name == "c2.android.hevc.decoder" }
                        ?.let { mutableListOf(it) }
                    if (infosNew != null) {
                        return infosNew
                    }
                }
            }
            return infos
        }
    }

    fun showVolume(visibility: Int) {
        binding.icon.visibility = visibility
        binding.volume.visibility = visibility
        hideVolume()
    }

    fun setVolumeMax(volume: Int) {
        binding.volume.max = volume
    }

    fun setVolume(progress: Int, volume: Boolean = false) {
        val context = requireContext()
        binding.volume.progress = progress
        binding.icon.setImageDrawable(
            ContextCompat.getDrawable(
                context,
                if (volume) {
                    if (progress > 0) R.drawable.volume_up_24px else R.drawable.volume_off_24px
                } else {
                    R.drawable.light_mode_24px
                }
            )
        )
    }

    fun hideVolume() {
        handler.removeCallbacks(hideVolumeRunnable)
        handler.postDelayed(hideVolumeRunnable, delayHideVolume)
    }

    fun hideVolumeNow() {
        handler.removeCallbacks(hideVolumeRunnable)
        handler.postDelayed(hideVolumeRunnable, 0)
    }

    private val hideVolumeRunnable = Runnable {
        binding.icon.visibility = View.GONE
        binding.volume.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        if (player?.isPlaying == false) {
            player?.prepare()
            player?.play()
        }
        // 确保定时器运行
        handler.removeCallbacks(checkPlaybackRunnable)
        handler.postDelayed(checkPlaybackRunnable, checkPlaybackInterval)
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && requireActivity().isInPictureInPictureMode) {
            return
        }
        if (player?.isPlaying == true) {
            player?.stop()
            lastPauseTime = System.currentTimeMillis() // 新增：记录暂停时间
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        handler.removeCallbacks(checkPlaybackRunnable)
        handler.removeCallbacks(stableSourceCheckRunnable)
    }

    companion object {
        private const val TAG = "PlayerFragment"
    }
}