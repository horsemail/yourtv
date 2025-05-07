package com.horsenma.yourtv

import com.horsenma.yourtv.MainViewModel.Companion.CACHE_FILE_NAME
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.marginBottom
import androidx.core.view.marginEnd
import androidx.core.view.marginTop
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.horsenma.yourtv.ModalFragment.Companion.KEY_URL
import com.horsenma.yourtv.SimpleServer.Companion.PORT
import com.horsenma.yourtv.databinding.SettingBinding
import kotlin.math.max
import kotlin.math.min
import android.view.KeyEvent
import android.content.Intent
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.app.Dialog
import android.content.ActivityNotFoundException
import androidx.core.net.toUri
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt

@Suppress("DEPRECATION")
class SettingFragment : Fragment() {

    private var _binding: SettingBinding? = null
    private val binding get() = _binding!!

    private lateinit var uri: Uri
    private lateinit var updateManager: UpdateManager
    private var server = "http://${PortUtil.lan()}:$PORT"
    private lateinit var viewModel: MainViewModel
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val application = requireActivity().applicationContext as YourTVApplication
        val context = requireContext()
        val mainActivity = (activity as MainActivity)

        _binding = SettingBinding.inflate(inflater, container, false)

        binding.versionName.text = "v${context.appVersionName}"

        binding.version.text = "項目地址：https://github.com/horsemailma/yourtvapp"
        binding.version.isFocusable = true
        binding.version.isFocusableInTouchMode = true
        binding.version.setOnClickListener {
            val url = "https://github.com/horsemailma/yourtvapp"
            try {
                val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No browser found to open URL: $url", e)
                R.string.no_browser_found.showToast()
                binding.version.requestFocus()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open URL: $url", e)
                R.string.no_browser_found.showToast()
                binding.version.requestFocus()
            }
        }
        binding.version.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.version.background =
                    ContextCompat.getColor(context, R.color.focus).toDrawable()
                binding.version.setTextColor(ContextCompat.getColor(context, R.color.white))
            } else {
                binding.version.background =
                    ContextCompat.getColor(context, R.color.description_blur).toDrawable()
                binding.version.setTextColor(ContextCompat.getColor(context, R.color.blur))
            }
        }

        val switchChannelReversal = _binding?.switchChannelReversal
        switchChannelReversal?.isChecked = SP.channelReversal
        switchChannelReversal?.setOnCheckedChangeListener { _, isChecked ->
            SP.channelReversal = isChecked
            mainActivity.settingActive()
        }

        val switchChannelNum = _binding?.switchChannelNum
        switchChannelNum?.isChecked = SP.channelNum
        switchChannelNum?.setOnCheckedChangeListener { _, isChecked ->
            SP.channelNum = isChecked
            mainActivity.settingActive()
        }

        val switchTime = _binding?.switchTime
        switchTime?.isChecked = SP.time
        switchTime?.setOnCheckedChangeListener { _, isChecked ->
            SP.time = isChecked
            mainActivity.settingActive()
        }

        val switchBootStartup = _binding?.switchBootStartup
        switchBootStartup?.isChecked = SP.bootStartup
        switchBootStartup?.setOnCheckedChangeListener { _, isChecked ->
            SP.bootStartup = isChecked
            mainActivity.settingActive()
        }

        val switchRepeatInfo = _binding?.switchRepeatInfo
        switchRepeatInfo?.isChecked = SP.repeatInfo
        switchRepeatInfo?.setOnCheckedChangeListener { _, isChecked ->
            SP.repeatInfo = isChecked
            mainActivity.settingActive()
        }

        val switchConfigAutoLoad = _binding?.switchConfigAutoLoad
        switchConfigAutoLoad?.isChecked = SP.configAutoLoad
        switchConfigAutoLoad?.setOnCheckedChangeListener { _, isChecked ->
            SP.configAutoLoad = isChecked
            mainActivity.settingActive()
        }

        val switchDefaultLike = _binding?.switchDefaultLike
        switchDefaultLike?.isChecked = SP.defaultLike
        switchDefaultLike?.setOnCheckedChangeListener { _, isChecked ->
            SP.defaultLike = isChecked
            mainActivity.settingActive()
        }

        val switchShowAllChannels = _binding?.switchShowAllChannels
        switchShowAllChannels?.isChecked = SP.showAllChannels

        val switchCompactMenu = _binding?.switchCompactMenu
        switchCompactMenu?.isChecked = SP.compactMenu
        switchCompactMenu?.setOnCheckedChangeListener { _, isChecked ->
            SP.compactMenu = isChecked
            mainActivity.updateMenuSize()
            mainActivity.settingActive()
        }

        val switchDisplaySeconds = _binding?.switchDisplaySeconds
        switchDisplaySeconds?.isChecked = SP.displaySeconds

        val switchSoftDecode = _binding?.switchSoftDecode
        switchSoftDecode?.isChecked = SP.softDecode
        switchSoftDecode?.setOnCheckedChangeListener { _, isChecked ->
            SP.softDecode = isChecked
            mainActivity.switchSoftDecode()
            mainActivity.settingActive()
        }

        val switchAutoSwitchSource = _binding?.switchAutoSwitchSource
        switchAutoSwitchSource?.isChecked = SP.autoSwitchSource
        switchAutoSwitchSource?.setOnCheckedChangeListener { _, isChecked ->
            SP.autoSwitchSource = isChecked
            mainActivity.settingActive()
        }

        val switchAutoUpdateSources = _binding?.switchAutoUpdateSources
        switchAutoUpdateSources?.isChecked = SP.autoUpdateSources
        switchAutoUpdateSources?.setOnCheckedChangeListener { _, isChecked ->
            SP.autoUpdateSources = isChecked
            mainActivity.settingActive()
        }

        // 设置显示换源按钮开关
        binding.switchShowSourceButton.isChecked = SP.showSourceButton
        // 仅在触摸屏设备上显示开关
        val isTouchScreen = isTouchScreenDevice(context)
        binding.switchShowSourceButton.visibility = if (isTouchScreen) View.VISIBLE else View.GONE
        binding.switchShowSourceButton.setOnCheckedChangeListener { _, isChecked ->
            SP.showSourceButton = isChecked
            mainActivity.settingActive()
            // 通知 PlayerFragment 更新 btn_source 可见性
            if (mainActivity.playerFragment.isAdded) {
                mainActivity.playerFragment.setSourceButtonVisibility(true)
            }
        }

        binding.remoteSettings.setOnClickListener {
            val imageModalFragment = ModalFragment()
            val args = Bundle()
            args.putString(KEY_URL, server)
            imageModalFragment.arguments = args

            imageModalFragment.show(requireFragmentManager(), ModalFragment.TAG)
            mainActivity.settingActive()
        }

        binding.checkVersion.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                    startActivityForResult(intent, REQUEST_UNKNOWN_APP_SOURCES)
                    Toast.makeText(context, R.string.enable_unknown_sources, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open unknown sources settings: ${e.message}")
                    Toast.makeText(context, R.string.install_failed, Toast.LENGTH_SHORT).show()
                }
            } else {
                try {
                    updateManager.checkAndUpdate()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to check update: ${e.message}")
                    Toast.makeText(context, R.string.update_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.checkVersion.setOnLongClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                    startActivityForResult(intent, REQUEST_UNKNOWN_APP_SOURCES)
                    Toast.makeText(context, R.string.enable_unknown_sources, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open unknown sources settings: ${e.message}")
                    Toast.makeText(context, R.string.install_failed, Toast.LENGTH_SHORT).show()
                }
            } else {
                try {
                    updateManager.checkAndUpdate()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to check update: ${e.message}")
                    Toast.makeText(context, R.string.update_failed, Toast.LENGTH_SHORT).show()
                }
            }
            true // 消费长按事件，阻止 MenuFragment
        }

        binding.confirmConfig.setOnClickListener {
            val sourcesFragment = SourcesFragment()
            sourcesFragment.show(requireFragmentManager(), SourcesFragment.TAG)
            mainActivity.settingActive()
        }

        binding.appreciate.setOnClickListener {
            val imageModalFragment = ModalFragment()

            val args = Bundle()
            args.putInt(ModalFragment.KEY_DRAWABLE_ID, R.drawable.appreciate)
            imageModalFragment.arguments = args

            imageModalFragment.show(requireFragmentManager(), ModalFragment.TAG)
            mainActivity.settingActive()
        }

        binding.setting.setOnClickListener {
            hideSelf()
        }

        binding.verifyUser.setOnClickListener {
            showVerificationDialog()
        }

        val txtTextSize =
            application.px2PxFont(binding.versionName.textSize)

        binding.content.layoutParams.width =
            application.px2Px(binding.content.layoutParams.width)
        binding.content.setPadding(
            application.px2Px(binding.content.paddingLeft),
            application.px2Px(binding.content.paddingTop),
            application.px2Px(binding.content.paddingRight),
            application.px2Px(binding.content.paddingBottom)
        )

        binding.name.textSize = application.px2PxFont(binding.name.textSize)
        binding.version.textSize = txtTextSize
        val layoutParamsVersion = binding.version.layoutParams as ViewGroup.MarginLayoutParams
        layoutParamsVersion.topMargin = application.px2Px(binding.version.marginTop)
        layoutParamsVersion.bottomMargin = application.px2Px(binding.version.marginBottom)
        binding.version.layoutParams = layoutParamsVersion

        val btnWidth =
            application.px2Px(binding.confirmConfig.layoutParams.width)

        val btnLayoutParams =
            binding.verifyUser.layoutParams as ViewGroup.MarginLayoutParams
        btnLayoutParams.marginEnd = application.px2Px(binding.verifyUser.marginEnd)

        binding.versionName.textSize = txtTextSize

        for (i in listOf(
            binding.remoteSettings,
            binding.confirmConfig,
            binding.clear,
            binding.checkVersion,
            binding.verifyUser,
            binding.appreciate,
        )) {
            i.layoutParams.width = btnWidth
            i.textSize = txtTextSize
            i.layoutParams = btnLayoutParams
            i.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    i.background = ContextCompat.getColor(
                        context,
                        R.color.focus
                    ).toDrawable()
                    i.setTextColor(
                        ContextCompat.getColor(
                            context,
                            R.color.white
                        )
                    )
                } else {
                    i.background = ContextCompat.getColor(
                        context,
                        R.color.description_blur
                    ).toDrawable()
                    i.setTextColor(
                        ContextCompat.getColor(
                            context,
                            R.color.blur
                        )
                    )
                }
            }
        }

        val textSizeSwitch = application.px2PxFont(binding.switchChannelReversal.textSize)

        val layoutParamsSwitch =
            binding.switchChannelReversal.layoutParams as ViewGroup.MarginLayoutParams
        layoutParamsSwitch.topMargin =
            application.px2Px(binding.switchChannelReversal.marginTop)

        for (i in listOf(
            binding.switchChannelReversal,
            binding.switchChannelNum,
            binding.switchTime,
            binding.switchBootStartup,
            binding.switchRepeatInfo,
            binding.switchConfigAutoLoad,
            binding.switchDefaultLike,
            binding.switchShowAllChannels,
            binding.switchCompactMenu,
            binding.switchDisplaySeconds,
            binding.switchSoftDecode,
            binding.switchAutoSwitchSource,
            binding.switchAutoUpdateSources,
            binding.switchShowSourceButton,
        )) {
            i.textSize = textSizeSwitch
            i.layoutParams = layoutParamsSwitch
            i.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    i.setTextColor(
                        ContextCompat.getColor(
                            context,
                            R.color.focus
                        )
                    )
                } else {
                    i.setTextColor(
                        ContextCompat.getColor(
                            context,
                            R.color.title_blur
                        )
                    )
                }
            }
        }

        val activity = requireActivity()
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode
        } else {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode.toLong()
        }
        updateManager = UpdateManager(requireActivity(), versionCode)

        return binding.root
    }

    // 新增：复用触摸屏检测逻辑
    private fun isTouchScreenDevice(context: Context): Boolean {
        val packageManager = context.packageManager
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        val isTv = uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val hasTouchScreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        return hasTouchScreen && !isTv
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val context = requireActivity()
        val mainActivity = (activity as MainActivity)
        val application = context.applicationContext as YourTVApplication
        val imageHelper = application.imageHelper

        viewModel = ViewModelProvider(context)[MainViewModel::class.java]

        binding.switchDisplaySeconds.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setDisplaySeconds(isChecked)
        }

        binding.clear.setOnClickListener {
            SP.channelNum = SP.DEFAULT_CHANNEL_NUM

            SP.sources = SP.DEFAULT_SOURCES
            viewModel.sources.init()

            SP.channelReversal = SP.DEFAULT_CHANNEL_REVERSAL
            SP.time = SP.DEFAULT_TIME
            SP.bootStartup = SP.DEFAULT_BOOT_STARTUP
            SP.repeatInfo = SP.DEFAULT_REPEAT_INFO
            SP.configAutoLoad = SP.DEFAULT_CONFIG_AUTO_LOAD
            SP.proxy = SP.DEFAULT_PROXY

            imageHelper.clearImage()

            // TODO update player
            SP.softDecode = SP.DEFAULT_SOFT_DECODE

            SP.configUrl = SP.DEFAULT_CONFIG_URL
            context.deleteFile(CACHE_FILE_NAME)
            // viewModel.reset(context)
            viewModel.init(context) // 添加这一行，确保数据重新加载

            SP.channel = SP.DEFAULT_CHANNEL
            confirmChannel()

            SP.deleteLike()

            SP.positionGroup = viewModel.groupModel.defaultPosition()
            viewModel.groupModel.initPosition()

            SP.position = SP.DEFAULT_POSITION

            val tvListModel = viewModel.groupModel.getCurrentList()
            tvListModel?.setPosition(SP.DEFAULT_POSITION)
            tvListModel?.setPositionPlaying(SP.DEFAULT_POSITION)

            viewModel.groupModel.setPositionPlaying()
            viewModel.groupModel.getCurrentList()?.setPositionPlaying()
            viewModel.groupModel.getCurrent()?.setReady()

            SP.showAllChannels = SP.DEFAULT_SHOW_ALL_CHANNELS
            SP.compactMenu = SP.DEFAULT_COMPACT_MENU

            viewModel.setDisplaySeconds(SP.DEFAULT_DISPLAY_SECONDS)

            SP.epg = SP.DEFAULT_EPG
            viewModel.updateEPG()
            SP.showSourceButton = SP.DEFAULT_SHOW_SOURCE_BUTTON // 新增：重置换源按钮设置

            // 通知 MenuFragment 更新数据
            (activity as? MainActivity)?.updateMenu()

            R.string.config_restored.showToast()
            hideSelf()
        }

        binding.switchShowAllChannels.setOnCheckedChangeListener { _, isChecked ->
            SP.showAllChannels = isChecked
            viewModel.groupModel.setChange()
            mainActivity.settingActive()
        }

        // 添加按键事件调试
        binding.root.isFocusable = true
        binding.root.isFocusableInTouchMode = true
        binding.root.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                Log.d(TAG, "Key pressed: $keyCode (Left: 21, Right: 22)")
            }
            false // 让事件继续传递
        }
        binding.remoteSettings.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_UNKNOWN_APP_SOURCES) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && requireContext().packageManager.canRequestPackageInstalls()) {
                try {
                    updateManager.checkAndUpdate()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to check update after permission: ${e.message}")
                    Toast.makeText(requireContext(), R.string.update_failed, Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), R.string.enable_unknown_sources, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmConfig() {
        if (SP.configUrl.isNullOrEmpty()) {
            return
        }

        uri = Utils.formatUrl(SP.configUrl!!).toUri()
        if (uri.scheme == "") {
            uri = uri.buildUpon().scheme("http").build()
        }
        if (uri.isAbsolute) {
            if (uri.scheme == "file") {
                requestReadPermissions()
            } else {
                viewModel.importFromUri(uri)
            }
        } else {
            R.string.invalid_config_address.showToast()
        }
        (activity as MainActivity).settingActive()
    }

    private fun confirmChannel() {
        SP.channel =
            min(max(SP.channel, 0), viewModel.groupModel.getAllList()!!.size())

        (activity as MainActivity).settingActive()
    }

    fun hideSelf() {
        requireActivity().supportFragmentManager.beginTransaction()
            .hide(this)
            .commitAllowingStateLoss()
        (activity as MainActivity).showTimeFragment()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (_binding != null && !hidden) {
            binding.appreciate.requestFocus()
        }
    }

    private fun checkAndAddPermission(context: Context, permission: String, permissionsList: MutableList<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            permissionsList.add(permission)
        }
    }

    private fun requestInstallPermissions() {
        val context = requireContext()
        val permissionsList = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            permissionsList.add(Manifest.permission.REQUEST_INSTALL_PACKAGES)
        }

        checkAndAddPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE, permissionsList)
        checkAndAddPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE, permissionsList)

        if (permissionsList.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                permissionsList.toTypedArray(),
                PERMISSIONS_REQUEST_CODE
            )
        }
    }

    private fun requestReadPermissions() {
        val context = requireContext()
        val permissionsList = mutableListOf<String>()

        checkAndAddPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE, permissionsList)

        if (permissionsList.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                permissionsList.toTypedArray(),
                PERMISSIONS_REQUEST_CODE
            )
        } else {
            viewModel.importFromUri(uri)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            var allPermissionsGranted = true
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false
                    break
                }
            }
            if (!allPermissionsGranted) {
                Toast.makeText(requireContext(), R.string.authorization_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showVerificationDialog() {
        val mainActivity = activity as? MainActivity
        val dialog = Dialog(requireContext()).apply {
            setContentView(R.layout.loading)
            setCancelable(true)
            window?.setBackgroundDrawable("#CCFFFFFF".toColorInt().toDrawable())
            window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val callback = object : MainActivity.VerificationCallback {
            override fun onKeyConfirmed(key: String) {}
            override fun onSkip() {}
            override fun onCompleted() {
                hideSelf()
                mainActivity?.settingActive()
            }
        }

        // Show dialog before handleUserVerification
        try {
            dialog.show()
            Log.d(TAG, "showVerificationDialog: Dialog shown")
        } catch (e: Exception) {
            Log.e(TAG, "showVerificationDialog: Failed to show dialog: ${e.message}", e)
            R.string.verify_user_error.showToast()
            return
        }

        // Use UserVerificationHandler
        mainActivity?.let {
            val handler = UserVerificationHandler(it, UserInfoManager, viewModel)
            handler.handleUserVerification(dialog, callback)
        } ?: run {
            Log.e(TAG, "MainActivity not available for verification")
            R.string.verify_user_error.showToast()
            dialog.dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.postDelayed({
            updateManager.destroy()
            Log.d(TAG, "UpdateManager destroyed after delay")
        }, 5000)
        _binding = null
    }

    companion object {
        const val TAG = "SettingFragment"
        const val PERMISSIONS_REQUEST_CODE = 1
        const val REQUEST_UNKNOWN_APP_SOURCES = 2
    }

    private fun Int.showToast() {
        Toast.makeText(requireContext(), this, Toast.LENGTH_SHORT).show()
    }
}