package com.horsenma.yourtv

import android.annotation.SuppressLint
import kotlinx.coroutines.withTimeoutOrNull
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.KeyEvent.*
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.view.WindowManager
import android.widget.PopupWindow
import android.widget.RelativeLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.horsenma.yourtv.databinding.SettingsWebBinding
import java.util.Locale
import kotlin.math.abs
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.widget.EditText
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*
import androidx.lifecycle.asFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.collect
import com.horsenma.yourtv.models.TVModel
import androidx.core.view.isVisible


@Suppress("UNUSED_EXPRESSION", "DEPRECATION")
class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private var ok = 0
    internal var playerFragment: PlayerFragment = PlayerFragment()
    private val errorFragment = ErrorFragment()
    private val loadingFragment = LoadingFragment()
    private var infoFragment = InfoFragment()
    private var channelFragment = ChannelFragment()
    private var timeFragment = TimeFragment()
    private var menuFragment = MenuFragment()
    private var settingFragment = SettingFragment()
    private var programFragment = ProgramFragment()

    private val handler = Handler(Looper.myLooper()!!)
    private val delayHideMenu = 10 * 1000L
    private val delayHideSetting = 3 * 60 * 1000L

    private var doubleBackToExitPressedOnce = false

    private var menuPressCount = 0
    private var lastMenuPressTime = 0L
    private val MENU_PRESS_INTERVAL = 300L
    private val MENU_TAP_INTERVAL = 500L
    private val REQUIRED_MENU_PRESSES = 4

    private lateinit var gestureDetector: GestureDetector

    private var server: SimpleServer? = null

    private lateinit var viewModel: MainViewModel

    private var isSafeToPerformFragmentTransactions = false
    internal var usersInfo: List<String> = emptyList()
    private var isLoadingInputVisible = false

    fun setLoadingInputVisible(visible: Boolean) {
        isLoadingInputVisible = visible
    }

    // Callback interface for verification dialog
    interface VerificationCallback {
        fun onKeyConfirmed(key: String)
        fun onSkip()
        fun onCompleted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 简化全屏设置
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContentView(R.layout.activity_main)
        UserInfoManager.initialize(applicationContext)

        // 初始化 ViewModel
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        // 确保焦点
        findViewById<View>(R.id.main_browse_fragment)?.requestFocus()

        // 添加其他 Fragment，初始只显示 PlayerFragment
        if (savedInstanceState == null) {
            try {
                supportFragmentManager.beginTransaction()
                    .add(R.id.main_browse_fragment, playerFragment)
                    .add(R.id.main_browse_fragment, infoFragment)
                    .add(R.id.main_browse_fragment, channelFragment)
                    .add(R.id.main_browse_fragment, menuFragment)
                    .add(R.id.main_browse_fragment, settingFragment)
                    .hide(infoFragment)
                    .hide(channelFragment)
                    .hide(menuFragment)
                    .hide(settingFragment)
                    .commitNowAllowingStateLoss()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add fragments: ${e.message}", e)
            }
        }

        // 设置手势检测
        gestureDetector = GestureDetector(this@MainActivity, GestureListener(this@MainActivity))

        // 优化 playTrigger 观察者
        viewModel.playTrigger.observe(this@MainActivity) { tvModel ->
            tvModel?.let {
                viewModel.setCurrentTvModel(it)
                viewModel.groupModel.setCurrent(it)
                lifecycleScope.launch(Dispatchers.Main) {
                    if (!playerFragment.isAdded) {
                        try {
                            supportFragmentManager.beginTransaction()
                                .add(R.id.main_browse_fragment, playerFragment)
                                .commitNowAllowingStateLoss()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to add PlayerFragment: ${e.message}", e)
                            showFragment(menuFragment)
                            menuActive()
                            return@launch
                        }
                    }
                    showFragment(playerFragment)

                    // 最多等待 2 秒
                    var attempts = 0
                    while (attempts < 20 && (!playerFragment.isAdded || playerFragment.view?.isAttachedToWindow != true)) {
                        Log.d(TAG, "Waiting for PlayerFragment view to be ready... attempt $attempts")
                        delay(100)
                        attempts++
                    }

                    if (playerFragment.isAdded && playerFragment.view != null && playerFragment.view?.isAttachedToWindow == true) {
                        playerFragment.play(it)
                        playerFragment.view?.requestLayout()
                        Log.d(TAG, "PlayerFragment shown, playing: ${it.tv.title}")
                    } else {
                        Log.w(TAG, "PlayerFragment view not ready, showing MenuFragment")
                        showFragment(menuFragment)
                        menuActive()
                    }
                }
            } ?: Log.w(TAG, "playTrigger received null TvModel")
        }

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                withTimeoutOrNull(5000) { viewModel.init(this@MainActivity) } ?: run {
                    Log.w(TAG, "viewModel.init timed out after 5s")
                    showFragment(menuFragment)
                    menuActive()
                    R.string.initialization_error.showToast()
                    return@launch
                }

                var attempts = 0
                var currentTvModel: TVModel? = null
                while (attempts < 10 && currentTvModel == null && SP.getStableSources().isNotEmpty()) {
                    currentTvModel = viewModel.groupModel.getCurrent()
                    delay(100)
                    attempts++
                    Log.d(TAG, "Waiting for stable source, attempt $attempts, currentTvModel: ${currentTvModel?.tv?.title}")
                }

                if (currentTvModel != null && SP.getStableSources().isNotEmpty()) {
                    menuFragment.update()
                    menuFragment.updateList(viewModel.groupModel.positionValue)
                    Log.d(TAG, "Stable source already playing: ${currentTvModel.tv.title}...")
                    return@launch
                }

                viewModel.channelsOk.asFlow().takeWhile { !it }.collect()
                Log.d(TAG, "Channels loaded, channelsOk: ${viewModel.channelsOk.value}")

                // Check groupModel.current.value directly to preserve stable source
                if (viewModel.groupModel.current.value != null) {
                    menuFragment.update()
                    menuFragment.updateList(viewModel.groupModel.positionValue)
                    Log.d(TAG, "Stable source playing after channelsOk: ${viewModel.groupModel.current.value?.tv?.title}...")
                } else {
                    var tvModel = viewModel.groupModel.getCurrent()
                    if (viewModel.listModel.isNotEmpty() && tvModel == null) {
                        tvModel = viewModel.listModel[0]
                        viewModel.groupModel.setCurrent(tvModel)
                        viewModel.groupModel.setPositionPlaying()
                        viewModel.groupModel.getCurrentList()?.let {
                            it.setPosition(0)
                            it.setPositionPlaying()
                            it.getCurrent()?.setReady()
                        }
                        Log.d(TAG, "No stable source, selected default tvModel: ${tvModel.tv.title}...")
                        viewModel.triggerPlay(tvModel)
                        Log.d(TAG, "Triggered play for tvModel: ${tvModel.tv.title}...")
                    } else if (tvModel != null) {
                        menuFragment.update()
                        menuFragment.updateList(viewModel.groupModel.positionValue)
                        Log.d(TAG, "Stable source playing after channelsOk: ${tvModel.tv.title}...")
                    } else {
                        showFragment(menuFragment)
                        menuActive()
                        Log.w(TAG, "No tvModel available, showing MenuFragment")
                        R.string.channel_read_error.showToast()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed: ${e.message}", e)
                showFragment(menuFragment)
                menuActive()
                R.string.initialization_error.showToast()
            }
        }    }

    fun updateMenuSize() {
        menuFragment.updateSize()
    }

    fun updateMenu() {
        val menuFragment = supportFragmentManager.findFragmentByTag("MenuFragment") as? MenuFragment
        menuFragment?.update()
    }

    fun ready() {
        ok++
        if (ok == 2) {
            gestureDetector = GestureDetector(this, GestureListener(this))
            // 确保 Fragment 状态正确
            supportFragmentManager.beginTransaction()
                .hide(menuFragment)
                .hide(settingFragment)
                .commit()
            viewModel.groupModel.change.observe(this) { _ ->
                if (viewModel.groupModel.tvGroup.value != null) {
                    watch()
                    menuFragment.update()
                }
            }

            viewModel.channelsOk.observe(this) {
                if (it) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        menuFragment.update()
                        val currentGroup = viewModel.groupModel.positionValue
                        menuFragment.updateList(currentGroup)
                        viewModel.groupModel.isInLikeMode =
                            SP.defaultLike && viewModel.groupModel.positionValue == 0
                        lifecycleScope.launch(Dispatchers.IO) {
                            viewModel.updateEPG()
                        }
                    }
                }
            }

            Utils.isp.observe(this) {
                val id = when (it) {
                    else -> 0
                }

                if (id == 0) {
                    return@observe
                }

                resources.openRawResource(id).bufferedReader()
                    .use { i ->
                        val channels = i.readText()
                        if (channels.isNotEmpty()) {
                            viewModel.tryStr2Channels(channels, null, "")
                        } else {
                            Log.w(TAG, "$it is empty")
                        }
                    }
            }

            server = SimpleServer(this, viewModel)

            viewModel.updateConfig()
            if (playerFragment.isAdded && !playerFragment.isHidden) {
                val currentTvModel = viewModel.groupModel.getCurrent()
                if (currentTvModel != null) {
                    playerFragment.play(currentTvModel)
                } else {
                    Log.w(TAG, "No current TV model available")
                }
            }
        }
    }

    private fun <T> LiveData<T>.throttle(durationMs: Long): LiveData<T> {
        val result = MutableLiveData<T>()
        var lastEmission = 0L
        observeForever { value ->
            val now = System.currentTimeMillis()
            if (now - lastEmission >= durationMs) {
                result.value = value
                lastEmission = now
            }
        }
        return result
    }

    private fun watch() {
        viewModel.listModel.forEach { tvModel ->
            // 为每个 tvModel 创建独立的防抖实例
            val errInfoThrottled = tvModel.errInfo.throttle(1000)
            errInfoThrottled.observe(this) { _ ->
                if (tvModel.errInfo.value != null && tvModel == viewModel.groupModel.getCurrent()) {
                    hideFragment(loadingFragment)
                    if (tvModel.errInfo.value == "") {
                        hideFragment(errorFragment)
                        showFragment(playerFragment)
                    } else {
                        Log.i(TAG, "${tvModel.tv.title} ${tvModel.errInfo.value.toString()}")
                        hideFragment(playerFragment)
                        errorFragment.setMsg(tvModel.errInfo.value.toString())
                        showFragment(errorFragment)
                    }
                }
            }

            val readyThrottled = tvModel.ready.throttle(1000)
            readyThrottled.observe(this) { _ ->
                if (tvModel.ready.value != null && tvModel == viewModel.groupModel.getCurrent()) {
                    hideFragment(errorFragment)
                    playerFragment.play(tvModel)
                    infoFragment.show(tvModel)
                    if (SP.channelNum) {
                        channelFragment.show(tvModel)
                    }
                }
            }

            tvModel.like.observe(this) { _ ->
                if (tvModel.like.value != null && tvModel.tv.id != -1) {
                    val liked = tvModel.like.value as Boolean
                    if (liked) {
                        viewModel.groupModel.getFavoritesList()?.replaceTVModel(tvModel)
                    } else {
                        viewModel.groupModel.getFavoritesList()?.removeTVModel(tvModel.tv.id)
                    }
                    SP.setLike(tvModel.tv.id, liked)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event != null && menuFragment.isVisible) {
            return super.onTouchEvent(event)
        }
        if (event != null) {
            // 检查是否点击在 btn_source 上，若是则不处理
            val btnSource = playerFragment.view?.findViewById<View>(R.id.btn_source)
            if (btnSource != null && btnSource.isVisible) {
                val buttonRect = android.graphics.Rect()
                btnSource.getGlobalVisibleRect(buttonRect)
                if (buttonRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    return false // 让 PlayerFragment 的 btn_source 处理事件
                }
            }
            gestureDetector.onTouchEvent(event)
            return true
        }
        return super.onTouchEvent(event)
    }

    private inner class GestureListener(context: Context) :
        GestureDetector.SimpleOnGestureListener() {

        private var screenWidth = windowManager.defaultDisplay.width
        private var screenHeight = windowManager.defaultDisplay.height
        private val audioManager = context.getSystemService(AUDIO_SERVICE) as AudioManager

        private var maxVolume = 0

        init {
            maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        }

        override fun onDown(e: MotionEvent): Boolean {
            playerFragment.hideVolumeNow()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            return handleTapCount(1) // 单击记 1 次
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val sourceButton = playerFragment.view?.findViewById<View>(R.id.btn_source)
            if (sourceButton != null && sourceButton.isVisible) {
                val buttonRect = android.graphics.Rect()
                sourceButton.getGlobalVisibleRect(buttonRect)
                if (buttonRect.contains(e.x.toInt(), e.y.toInt())) {
                    sourceUp()
                    return true
                }
            }
            showFragment(menuFragment)
            return handleTapCount(2)
        }

        override fun onLongPress(e: MotionEvent) {
            showProgram()
            return
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val oldX = e1?.rawX ?: 0f
            val oldY = e1?.rawY ?: 0f
            val newX = e2.rawX
            val newY = e2.rawY
            if (oldX > screenWidth / 3 && oldX < screenWidth * 2 / 3 && abs(newX - oldX) < abs(newY - oldY)) {
                if (velocityY > 0) {
                    if ((!menuFragment.isAdded || menuFragment.isHidden) && (!settingFragment.isAdded || settingFragment.isHidden)) {
                        prev()
                    }
                }
                if (velocityY < 0) {
                    if ((!menuFragment.isAdded || menuFragment.isHidden) && (!settingFragment.isAdded || settingFragment.isHidden)) {
                        next()
                    }
                }
            }

            return super.onFling(e1, e2, velocityX, velocityY)
        }

        private var lastScrollTime: Long = 0
        private var decayFactor: Float = 1.0f

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            val oldX = e1?.rawX ?: 0f
            val oldY = e1?.rawY ?: 0f
            val newX = e2.rawX
            val newY = e2.rawY

            if (oldX < screenWidth / 3) {
                val currentTime = System.currentTimeMillis()
                val deltaTime = currentTime - lastScrollTime
                lastScrollTime = currentTime

                decayFactor =
                    0.01f.coerceAtLeast(decayFactor - 0.03f * deltaTime)
                val delta =
                    ((oldY - newY) * decayFactor * 0.2 / screenHeight).toFloat()
                adjustBrightness(delta)
                decayFactor = 1.0f
                return super.onScroll(e1, e2, distanceX, distanceY)
            }

            if (oldX > screenWidth * 2 / 3 && abs(distanceY) > abs(distanceX)) {
                val currentTime = System.currentTimeMillis()
                val deltaTime = currentTime - lastScrollTime
                lastScrollTime = currentTime

                decayFactor =
                    0.01f.coerceAtLeast(decayFactor - 0.03f * deltaTime)
                val delta =
                    ((oldY - newY) * maxVolume * decayFactor * 0.2 / screenHeight).toInt()
                adjustVolume(delta)
                decayFactor = 1.0f
                return super.onScroll(e1, e2, distanceX, distanceY)
            }

            return super.onScroll(e1, e2, distanceX, distanceY)
        }

        private fun adjustVolume(deltaVolume: Int) {
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            var newVolume = currentVolume + deltaVolume

            if (newVolume < 0) {
                newVolume = 0
            } else if (newVolume > maxVolume) {
                newVolume = maxVolume
            }

            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)

            playerFragment.setVolumeMax(maxVolume * 100)
            playerFragment.setVolume(newVolume.toInt() * 100, true)
            playerFragment.showVolume(View.VISIBLE)
        }

        private fun adjustBrightness(deltaBrightness: Float) {
            var brightness = window.attributes.screenBrightness

            brightness += deltaBrightness
            brightness = 0.1f.coerceAtLeast(0.9f.coerceAtMost(brightness))

            val attributes = window.attributes.apply {
                screenBrightness = brightness
            }
            window.attributes = attributes

            playerFragment.setVolumeMax(100)
            playerFragment.setVolume((brightness * 100).toInt())
            playerFragment.showVolume(View.VISIBLE)
        }
    }

    fun onPlayEnd() {
        val tvModel = viewModel.groupModel.getCurrent()!!
        if (SP.repeatInfo) {
            infoFragment.show(tvModel)
            if (SP.channelNum) {
                channelFragment.show(tvModel)
            }
        }
    }

    fun play(position: Int): Boolean {
        return if (position > -1 && position < viewModel.groupModel.getAllList()!!.size()) {
            val prevGroup = viewModel.groupModel.positionValue
            val tvModel = viewModel.groupModel.getPosition(position)

            tvModel?.setReady()
            viewModel.groupModel.setPositionPlaying()
            viewModel.groupModel.getCurrentList()?.setPositionPlaying()

            val currentGroup = viewModel.groupModel.positionValue
            if (currentGroup != prevGroup) {
                menuFragment.updateList(currentGroup)
            }
            true
        } else {
            R.string.channel_not_exist.showToast()
            false
        }
    }

    fun prev() {
        val prevGroup = viewModel.groupModel.positionValue
        val tvModel =
            if (SP.defaultLike && viewModel.groupModel.isInLikeMode && viewModel.groupModel.getFavoritesList() != null
            ) {
                viewModel.groupModel.getPrev(true)
            } else {
                viewModel.groupModel.getPrev()
            }

        tvModel?.setReady()
        viewModel.groupModel.setPositionPlaying()
        viewModel.groupModel.getCurrentList()?.setPositionPlaying()

        val currentGroup = viewModel.groupModel.positionValue
        if (currentGroup != prevGroup) {
            menuFragment.updateList(currentGroup)
        }
    }

    fun next() {
        val prevGroup = viewModel.groupModel.positionValue
        val tvModel =
            if (SP.defaultLike && viewModel.groupModel.isInLikeMode && viewModel.groupModel.getFavoritesList() != null
            ) {
                viewModel.groupModel.getNext(true)
            } else {
                viewModel.groupModel.getNext()
            }

        tvModel?.setReady()
        viewModel.groupModel.setPositionPlaying()
        viewModel.groupModel.getCurrentList()?.setPositionPlaying()

        val currentGroup = viewModel.groupModel.positionValue
        if (currentGroup != prevGroup) {
            menuFragment.updateList(currentGroup)
        }
    }

    // 更新 showFragment 方法，确保画中画模式下视图可见
    private fun showFragment(fragment: Fragment) {
        if (!isSafeToPerformFragmentTransactions) {
            return
        }
        if (!fragment.isAdded) {
            supportFragmentManager.beginTransaction()
                .add(R.id.main_browse_fragment, fragment)
                .commitNowAllowingStateLoss()
        }
        if (!fragment.isHidden) {
            return
        }
        supportFragmentManager.beginTransaction()
            .show(fragment)
            .commitNowAllowingStateLoss()
        // 强制确保视图可见
        fragment.view?.visibility = View.VISIBLE
        fragment.view?.requestFocus()
    }

    private fun hideFragment(fragment: Fragment) {
        if (!isSafeToPerformFragmentTransactions) {
            return
        }

        if (!fragment.isAdded || fragment.isHidden) {
            return
        }

        supportFragmentManager.beginTransaction()
            .hide(fragment)
            .commitAllowingStateLoss()
    }

    fun sourceUp() {
        var tvModel = viewModel.groupModel.getCurrent()
        if (tvModel == null) {
            Log.w(TAG, "sourceUp: tvModel is null, attempting to fix groupModel")
            if (viewModel.listModel.isNotEmpty()) {
                tvModel = viewModel.listModel[SP.channel.coerceIn(0, viewModel.listModel.size - 1)]
                viewModel.groupModel.setCurrent(tvModel)
                Log.d(TAG, "Fixed groupModel with tvModel: ${tvModel.tv.title}, uris: ${tvModel.tv.uris.size}")
            } else {
                Log.e(TAG, "sourceUp: listModel is empty")
                R.string.no_current_tv_model.showToast()
                return
            }
        }
        playerFragment.switchSource(tvModel) // 直接调用 switchSource 更新 tvModel
        Log.d(TAG, "sourceUp tvModel: ${tvModel.tv.title}, uris: ${tvModel.tv.uris.size}")

        val urls = tvModel.tv.uris.filter { it.isNotBlank() }
        if (urls.isEmpty()) {
            Log.w(TAG, "sourceUp: no available sources for ${tvModel.tv.title}")
            R.string.no_available_sources.showToast()
            return
        }
        if (urls.size <= 1) {
            Log.d(TAG, "sourceUp: only one source for ${tvModel.tv.title}")
            R.string.no_multiple_sources.showToast()
            return
        }

        lifecycleScope.launch(Dispatchers.Main) {
            tvModel.nextVideo()
            tvModel.confirmVideoIndex()
            playerFragment.switchSource(tvModel)
            showSourceInfo(tvModel.videoIndexValue + 1)
            Log.d(TAG, "sourceUp: switched to source ${tvModel.videoIndexValue + 1}, uris: ${tvModel.tv.uris.size}")
        }
    }

    private fun showSourceInfo(sourceIndex: Int) {
        val toast = android.widget.Toast.makeText(
            this,
            "线路 $sourceIndex",
            android.widget.Toast.LENGTH_SHORT
        )
        val textView = toast.view?.findViewById<TextView>(android.R.id.message)
        textView?.textSize = 30f
        toast.setGravity(Gravity.CENTER, 0, 0)
        toast.show()

        handler.postDelayed({
            toast.cancel()
        }, 3000)
    }

    fun menuActive() {
        handler.removeCallbacks(hideMenu)
        handler.postDelayed(hideMenu, delayHideMenu)
    }

    private val hideMenu = Runnable {
        if (!isFinishing && !supportFragmentManager.isStateSaved) {
            if (!menuFragment.isHidden) {
                supportFragmentManager.beginTransaction()
                    .hide(menuFragment)
                    .commitAllowingStateLoss()
            }
        }
    }

    fun switchSoftDecode() {
        if (!playerFragment.isAdded || playerFragment.isHidden) {
            return
        }

        playerFragment.updatePlayer()
    }

    fun settingActive() {
        handler.removeCallbacks(hideSetting)
        handler.postDelayed(hideSetting, delayHideSetting)
    }

    private val hideSetting = Runnable {
        hideFragment(settingFragment)
        showTimeFragment()
    }

    fun showTimeFragment() {
        if (SP.time) {
            showFragment(timeFragment)
        } else {
            hideFragment(timeFragment)
        }
    }

    private fun showChannel(channel: Int) {
        if (!menuFragment.isHidden) {
            return
        }

        if (settingFragment.isVisible) {
            return
        }

//        if (SP.channelNum) {
//            channelFragment.show(channel)
//        }
        channelFragment.show(channel)
    }


    private fun channelUp() {
        if (programFragment.isAdded && !programFragment.isHidden) {
            return
        }

        if ((!menuFragment.isAdded || menuFragment.isHidden) && (!settingFragment.isAdded || settingFragment.isHidden)) {
            if (SP.channelReversal) {
                next()
                return
            }
            prev()
        }
    }

    private fun channelDown() {
        if (programFragment.isAdded && !programFragment.isHidden) {
            return
        }

        if ((!menuFragment.isAdded || menuFragment.isHidden) && (!settingFragment.isAdded || settingFragment.isHidden)) {
            if (SP.channelReversal) {
                prev()
                return
            }
            next()
        }
    }

    private fun back() {
        if (menuFragment.isAdded && !menuFragment.isHidden) {
            hideFragment(menuFragment)
            return
        }

        if (programFragment.isAdded && !programFragment.isHidden) {
            hideFragment(programFragment)
            return
        }

        if (settingFragment.isAdded && !settingFragment.isHidden) {
            hideFragment(settingFragment)
            showTimeFragment()
            return
        }

        if (channelFragment.isAdded && channelFragment.isVisible) {
            channelFragment.hideSelf()
            return
        }

        if (doubleBackToExitPressedOnce) {
            super.onBackPressed()
            return
        }

        doubleBackToExitPressedOnce = true
        R.string.press_again_to_exit.showToast()

        Handler(Looper.getMainLooper()).postDelayed({
            doubleBackToExitPressedOnce = false
        }, 2000)
    }

    private fun showSetting() {
        lifecycleScope.launch(Dispatchers.Main) {
            if (programFragment.isAdded && !programFragment.isHidden) {
                hideFragment(programFragment)
            }
            if (menuFragment.isAdded && !menuFragment.isHidden) {
                hideFragment(menuFragment)
            }
            showFragment(settingFragment)
            settingActive()
        }
    }

    private fun showProgram() {
        if (menuFragment.isAdded && !menuFragment.isHidden) {
            return
        }

        if (settingFragment.isAdded && !settingFragment.isHidden) {
            return
        }

        viewModel.groupModel.getCurrent()?.let {
            if (it.epgValue.isEmpty()) {
                R.string.epg_is_empty.showToast()
                return
            }
        }

        showFragment(programFragment)
    }

    private fun hideProgram(): Boolean {
        if (!programFragment.isAdded || programFragment.isHidden) {
            return false
        }

        hideFragment(programFragment)
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun showWebViewPopup(url: String) {
        val binding = SettingsWebBinding.inflate(layoutInflater)

        val webView = binding.web
        webView.settings.javaScriptEnabled = true
        webView.isFocusableInTouchMode = true
        webView.isFocusable = true
        webView.loadUrl(url)

        val popupWindow = PopupWindow(
            binding.root,
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )

        popupWindow.inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        popupWindow.isFocusable = true
        popupWindow.isTouchable = true

        popupWindow.isClippingEnabled = false

        popupWindow.showAtLocation(window.decorView, Gravity.CENTER, 0, 0)

        webView.requestFocus()

        binding.close.setOnClickListener {
            popupWindow.dismiss()
        }
    }

    private fun handleTapCount(tapCount: Int): Boolean {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastTap = currentTime - lastMenuPressTime

        if (timeSinceLastTap <= MENU_TAP_INTERVAL) {
            menuPressCount += tapCount
        } else {
            menuPressCount = tapCount
        }

        lastMenuPressTime = currentTime

        if (menuPressCount >= REQUIRED_MENU_PRESSES) {
            showSetting()
            menuPressCount = 0
        }
        return true
    }

    private fun handleSettingsKeyPress(): Boolean {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastMenuPressTime <= MENU_PRESS_INTERVAL) {
            menuPressCount++
            if (menuPressCount >= REQUIRED_MENU_PRESSES) {
                showSetting()
                menuPressCount = 0
                return true
            }
        } else {
            menuPressCount = 1
        }
        lastMenuPressTime = currentTime
        return true
    }

    fun onKey(keyCode: Int): Boolean {
        when (keyCode) {
            KEYCODE_0,
            KEYCODE_1,
            KEYCODE_2,
            KEYCODE_3,
            KEYCODE_4,
            KEYCODE_5,
            KEYCODE_6,
            KEYCODE_7,
            KEYCODE_8,
            KEYCODE_9 -> {
                showChannel(keyCode - 7)
                return true
            }
            KEYCODE_ESCAPE,
            KEYCODE_BACK -> {
                back()
                return true
            }
            KEYCODE_BOOKMARK,
            KEYCODE_UNKNOWN,
            KEYCODE_HELP,
            KEYCODE_SETTINGS,
            KEYCODE_MENU -> {
                return handleSettingsKeyPress()
            }
            KEYCODE_DPAD_UP, KEYCODE_CHANNEL_UP -> {
                if (isLoadingInputVisible) {
                    val loadingFragment = supportFragmentManager.findFragmentByTag(LoadingFragment.TAG) as? LoadingFragment
                    if (loadingFragment != null && loadingFragment.isVisible && loadingFragment.isInputUIVisible()) {
                        return true // 焦点切换由 XML 的 nextFocusUp 处理
                    }
                }
                if (menuFragment.isAdded && !menuFragment.isHidden) {
                    return false
                }
                if (settingFragment.isAdded && !settingFragment.isHidden) {
                    return false
                }
                channelUp()
                return true
            }

            KEYCODE_DPAD_DOWN, KEYCODE_CHANNEL_DOWN -> {
                if (isLoadingInputVisible) {
                    val loadingFragment = supportFragmentManager.findFragmentByTag(LoadingFragment.TAG) as? LoadingFragment
                    if (loadingFragment != null && loadingFragment.isVisible && loadingFragment.isInputUIVisible()) {
                        return true // 焦点切换由 XML 的 nextFocusDown 处理
                    }
                }
                if (menuFragment.isAdded && !menuFragment.isHidden) {
                    return false
                }
                if (settingFragment.isAdded && !settingFragment.isHidden) {
                    return false
                }
                channelDown()
                return true
            }

            KEYCODE_ENTER, KEYCODE_DPAD_CENTER -> {
                if (isLoadingInputVisible) {
                    val loadingFragment = supportFragmentManager.findFragmentByTag(LoadingFragment.TAG) as? LoadingFragment
                    if (loadingFragment != null && loadingFragment.isVisible && loadingFragment.isInputUIVisible()) {
                        val currentFocus = currentFocus
                        if (currentFocus?.id == R.id.confirm_button) {
                            val key = loadingFragment.view?.findViewById<EditText>(R.id.key_input)?.text?.toString()?.trim() ?: ""
                            if (key.isNotEmpty() && key.matches("[0-9A-Z]{1,20}".toRegex())) {
                                loadingFragment.triggerConfirm(key)
                            } else {
                                loadingFragment.view?.findViewById<TextView>(R.id.errorText)?.apply {
                                    text = "測試碼格式錯，請重新輸入。"
                                    visibility = View.VISIBLE
                                }
                                loadingFragment.view?.findViewById<View>(R.id.key_input)?.requestFocus()
                            }
                            return true
                        } else if (currentFocus?.id == R.id.skip_button) {
                            loadingFragment.triggerSkip()
                            return true
                        }
                        return true
                    }
                }
                if (channelFragment.isAdded && channelFragment.isVisible) {
                    channelFragment.playNow()
                    return true
                }
                showFragment(menuFragment)
                return true
            }

            KEYCODE_DPAD_LEFT -> {
                if (isLoadingInputVisible) {
                    val loadingFragment = supportFragmentManager.findFragmentByTag(LoadingFragment.TAG) as? LoadingFragment
                    if (loadingFragment != null && loadingFragment.isVisible && loadingFragment.isInputUIVisible()) {
                        val currentFocus = currentFocus
                        if (currentFocus?.id == R.id.skip_button) {
                            val confirmButton = loadingFragment.view?.findViewById<View>(R.id.confirm_button)
                            confirmButton?.isFocusable = true
                            confirmButton?.isFocusableInTouchMode = true
                            confirmButton?.requestFocus()
                            return true
                        }
                        return true
                    }
                }
                if (settingFragment.isAdded && !settingFragment.isHidden) {
                    return false
                }
                showProgram()
                return true
            }

            KEYCODE_DPAD_RIGHT -> {
                if (isLoadingInputVisible) {
                    val loadingFragment = supportFragmentManager.findFragmentByTag(LoadingFragment.TAG) as? LoadingFragment
                    if (loadingFragment != null && loadingFragment.isVisible && loadingFragment.isInputUIVisible()) {
                        val currentFocus = currentFocus
                        if (currentFocus?.id == R.id.confirm_button) {
                            val skipButton = loadingFragment.view?.findViewById<View>(R.id.skip_button)
                            skipButton?.isFocusable = true
                            skipButton?.isFocusableInTouchMode = true
                            skipButton?.requestFocus()
                            return true
                        }
                        return true
                    }
                }
                if (menuFragment.isAdded && !menuFragment.isHidden ||
                    settingFragment.isAdded && !settingFragment.isHidden ||
                    programFragment.isAdded && !programFragment.isHidden) {
                    return false
                }
                sourceUp()
                return true
            }
        }
        return false
    }

    // 处理主页按钮点击（圆圈虚拟按钮）
    override fun onUserLeaveHint() {
        // 仅在触摸屏设备上，且 PlayerFragment 可见时，进入画中画模式
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            isTouchScreenDevice() &&
            playerFragment.isAdded && !playerFragment.isHidden) {
            playerFragment.enterPictureInPictureMode()
            Log.d(TAG, "Entering Picture-in-Picture mode via onUserLeaveHint")
        } else {
            Log.d(TAG, "Skipped Picture-in-Picture: isTouchScreen=${isTouchScreenDevice()}, playerFragmentAdded=${playerFragment.isAdded}, playerFragmentHidden=${playerFragment.isHidden}")
            super.onUserLeaveHint()
        }
    }

    // 保留原有 onKeyDown，仅处理返回键
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 仅在触摸屏设备上，且 PlayerFragment 可见时，按返回键进入画中画
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                isTouchScreenDevice() &&
                playerFragment.isAdded && !playerFragment.isHidden) {
                playerFragment.enterPictureInPictureMode()
                return true
            }
            // 原有返回逻辑
            back()
            return true
        }
        return if (onKey(keyCode)) {
            true
        } else {
            super.onKeyDown(keyCode, event)
        }
    }

    // 新增：触摸屏检测方法，与 PlayerFragment 一致
    private fun isTouchScreenDevice(): Boolean {
        val packageManager = packageManager
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        val isTv = uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val hasTouchScreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        return hasTouchScreen && !isTv
    }

    // 处理画中画模式变化
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            hideFragment(menuFragment)
            hideFragment(settingFragment)
            hideFragment(programFragment)
            hideFragment(channelFragment)
            hideFragment(infoFragment)
            hideFragment(timeFragment)
            hideFragment(errorFragment)
            hideFragment(loadingFragment)
            if (playerFragment.isAdded) {
                playerFragment.enterPictureInPictureMode()
            }
            showFragment(playerFragment)
            Log.d(TAG, "Entered Picture-in-Picture mode")
        } else {
            showFragment(playerFragment)
            if (playerFragment.isAdded) {
                playerFragment.exitPictureInPictureMode()
            }
            // 恢复焦点到 main_browse_fragment
            findViewById<View>(R.id.main_browse_fragment)?.requestFocus()
            // 恢复其他 Fragment 状态
            showTimeFragment()
            if (SP.channelNum && viewModel.groupModel.getCurrent() != null) {
                channelFragment.show(viewModel.groupModel.getCurrent()!!)
            }
            Log.d(TAG, "Exited Picture-in-Picture mode, focus requested on main_browse_fragment")
        }
    }

    override fun onResume() {
        super.onResume()

        isSafeToPerformFragmentTransactions = true

        showTimeFragment()
    }

    override fun onPause() {
        super.onPause()
        isSafeToPerformFragmentTransactions = false
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
    }

    override fun attachBaseContext(base: Context) {
        try {
            val locale = Locale.TRADITIONAL_CHINESE
            val config = Configuration()
            config.setLocale(locale)
            super.attachBaseContext(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                    base.createConfigurationContext(config)
                } else {
                    val resources = base.resources
                    resources.updateConfiguration(config, resources.displayMetrics)
                    base
                }
            )
        } catch (_: Exception) {
            super.attachBaseContext(base)
        }
    }

    companion object {
        internal const val TAG = "MainActivity"
    }
}