package com.horsenma.yourtv

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.horsenma.yourtv.databinding.LoadingBinding
import android.text.InputFilter
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent

interface LoadingFragmentCallback {
    fun onLoadingCompleted()
    fun onKeyConfirmed(key: String)
    fun onSkip()
}

class LoadingFragment : Fragment() {
    private var _binding: LoadingBinding? = null
    private val binding get() = _binding!!
    private var callback: LoadingFragmentCallback? = null
    private val TAG = "LoadingFragment"
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val focusRunnable = Runnable {
        _binding?.let {
            if (!it.keyInput.hasFocus() && !it.confirmButton.hasFocus() && !it.skipButton.hasFocus()) {
                it.keyInput.isFocusable = true
                it.keyInput.isFocusableInTouchMode = true
                it.keyInput.requestFocus()
            } else {
                Log.d(TAG, "ensureFocus: focus already set, key_input=${it.keyInput.hasFocus()}, confirm=${it.confirmButton.hasFocus()}, skip=${it.skipButton.hasFocus()}")
            }
        }
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    private fun stopMusicAndSwitchToLive() {
        val activity = activity as? MainActivity
        activity?.let {
            val channelFragment = it.supportFragmentManager.findFragmentByTag("ChannelFragmentTag") as? ChannelFragment
            channelFragment?.playNow() // 假设 playNow 是播放直播的方法
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            stopMusicAndSwitchToLive()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LoadingBinding.inflate(inflater, container, false)

        val application = requireActivity().applicationContext as? YourTVApplication
        if (application != null) {
            binding.bar.layoutParams.width = application.px2Px(binding.bar.layoutParams.width)
            binding.bar.layoutParams.height = application.px2Px(binding.bar.layoutParams.height)
        } else {
            binding.bar.layoutParams.width = (100 * resources.displayMetrics.density).toInt()
            binding.bar.layoutParams.height = (20 * resources.displayMetrics.density).toInt()
        }

        // 获取 warningMessages
        val warningMessages = arguments?.getStringArrayList("warning_messages")?.toList() ?: emptyList()

        // 更新 UI
        binding.loadingText1.text = warningMessages.getOrNull(0) ?: getString(R.string.loading_message1)
        binding.loadingText2.text = warningMessages.getOrNull(1) ?: getString(R.string.loading_message2)
        binding.loadingText3.text = warningMessages.getOrNull(2) ?: getString(R.string.loading_message3)

        binding.bar.visibility = View.VISIBLE

        // 设置输入框按钮监听
        setupInputUI()
        return binding.root
    }

    private fun setupInputUI() {
        // 添加输入过滤器，限制只允许 0-9 和 A-Z
        binding.keyInput.filters = arrayOf(InputFilter { source, _, _, _, _, _ ->
            val regex = Regex("[0-9A-Z]*")
            if (source.toString().matches(regex)) {
                null // 允许输入
            } else {
                "" // 阻止非法字符
            }
        }, InputFilter.LengthFilter(20))

        binding.confirmButton.setOnClickListener {
            try {
                val key = binding.keyInput.text.toString().trim()
                if (key.isNotEmpty() && key.matches("[0-9A-Z]{1,20}".toRegex())) {
                    callback?.onKeyConfirmed(key)
                } else {
                    binding.errorText.text = "您輸入的測試碼格式不對，請重新輸入。"
                    binding.errorText.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Confirm button error: ${e.message}", e)
                binding.errorText.text = "輸入錯誤，請重試。"
                binding.errorText.visibility = View.VISIBLE
            }
        }

        binding.skipButton.setOnClickListener {
            try {
                callback?.onSkip()
            } catch (e: Exception) {
                binding.errorText.text = "操作失敗，請重試。"
                binding.errorText.visibility = View.VISIBLE
            }
        }

        // 焦点监听器
        binding.keyInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.errorText.text = ""
                binding.errorText.visibility = View.GONE
            }
        }

        // 添加文本变化监听器
        binding.keyInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // 仅当用户手动输入非空内容时延迟清空错误提示
                if (s != null && s.isNotEmpty()) {
                    binding.keyInput.postDelayed({
                        binding.errorText.text = ""
                        binding.errorText.visibility = View.GONE
                    }, 500) // 延迟 500ms，确保 errorText 显示
                }
                Log.d(TAG, "Text changed, input=$s, count=$count")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }


    fun setCallback(callback: LoadingFragmentCallback) {
        this.callback = callback
    }

    fun updateMessages(warningMessages: List<String>) {
        _binding?.let {
            it.loadingText1.text = warningMessages.getOrNull(0) ?: getString(R.string.loading_message1)
            it.loadingText2.text = warningMessages.getOrNull(1) ?: getString(R.string.loading_message2)
            it.loadingText3.text = warningMessages.getOrNull(2) ?: getString(R.string.loading_message3)
        } ?: Log.w(TAG, "Binding is null, cannot update messages")
    }

    fun showInputUI(message: String? = null) {
        _binding?.let {
            it.keyInput.visibility = View.VISIBLE
            it.buttonContainer.visibility = View.VISIBLE
            it.bar.visibility = View.GONE
            if (message != null) {
                it.errorText.text = message
                it.errorText.visibility = View.VISIBLE
            } else {
                it.errorText.text = ""
                it.errorText.visibility = View.GONE
            }
            it.loadingText1.text = "請輸入測試碼"
            it.loadingText2.text = "沒有測試碼，您無法測試所有功能。"
            it.loadingText3.text = "正在載入在線直播源，請等待（約10-20秒)。"
            it.keyInput.setOnFocusChangeListener { _, hasFocus ->
                Log.d(TAG, "key_input focus changed, hasFocus=$hasFocus")
            }
            it.confirmButton.setOnFocusChangeListener { _, hasFocus ->
                Log.d(TAG, "confirm_button focus changed, hasFocus=$hasFocus")
            }
            it.skipButton.setOnFocusChangeListener { _, hasFocus ->
                Log.d(TAG, "skip_button focus changed, hasFocus=$hasFocus")
            }
            it.keyInput.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    it.confirmButton.isFocusable = true
                    it.confirmButton.isFocusableInTouchMode = true
                    it.confirmButton.requestFocus()
                    true
                } else {
                    false
                }
            }
            it.confirmButton.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            it.keyInput.isFocusable = true
                            it.keyInput.isFocusableInTouchMode = true
                            it.keyInput.requestFocus()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            it.skipButton.isFocusable = true
                            it.skipButton.isFocusableInTouchMode = true
                            it.skipButton.requestFocus()
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
            it.skipButton.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    it.confirmButton.isFocusable = true
                    it.confirmButton.isFocusableInTouchMode = true
                    it.confirmButton.requestFocus()
                    true
                } else {
                    false
                }
            }
            ensureFocus()
            (requireActivity() as MainActivity).setLoadingInputVisible(true)
        } ?: Log.w(TAG, "Binding is null, cannot show input UI")
    }

    private fun ensureFocus() {
        handler.removeCallbacks(focusRunnable) // 防止重复任务
        handler.postDelayed(focusRunnable, 100)
    }

    fun hideInputUI() {
        _binding?.let {
            it.keyInput.text.clear()
            it.keyInput.visibility = View.GONE
            it.buttonContainer.visibility = View.GONE
            it.errorText.text = ""
            it.errorText.visibility = View.GONE
            it.bar.visibility = View.VISIBLE
            it.loadingText1.text = getString(R.string.loading_message1)
            it.loadingText2.text = getString(R.string.loading_message2)
            it.loadingText3.text = getString(R.string.loading_message3)
            it.keyInput.clearFocus()
            it.confirmButton.clearFocus()
            it.skipButton.clearFocus()
            it.keyInput.setOnKeyListener(null)
            it.confirmButton.setOnKeyListener(null)
            it.skipButton.setOnKeyListener(null)
            it.keyInput.setOnFocusChangeListener(null)
            it.confirmButton.setOnFocusChangeListener(null)
            it.skipButton.setOnFocusChangeListener(null)
            (requireActivity() as MainActivity).setLoadingInputVisible(false)
        } ?: Log.w(TAG, "Binding is null, cannot hide input UI")
        handler.removeCallbacks(focusRunnable)
    }

    private fun removeFragment() {
        try {
            (requireActivity() as MainActivity).setLoadingInputVisible(false)
            requireActivity().supportFragmentManager.beginTransaction()
                .remove(this)
                .commitAllowingStateLoss()
        } catch (e: Exception) {
            Log.e(TAG, "removeFragment error: ${e.message}", e)
        }
    }

    fun triggerConfirm(key: String) {
        try {
            callback?.onKeyConfirmed(key)
            removeFragment()
        } catch (e: Exception) {
            _binding?.errorText?.apply {
                text = "輸入處理錯誤，請重試。"
                visibility = View.VISIBLE
            }
        }
    }

    fun triggerSkip() {
        try {
            callback?.onSkip()
            removeFragment()
        } catch (e: Exception) {
            _binding?.errorText?.apply {
                text = "操作失敗，請重試。"
                visibility = View.VISIBLE
            }
        }
    }

    private fun hideFragment() {
        try {
            requireActivity().supportFragmentManager.beginTransaction()
                .hide(this)
                .commitAllowingStateLoss()
        } catch (e: Exception) {
            Log.e(TAG, "hideFragment error: ${e.message}", e)
        }
    }

    fun isInputUIVisible(): Boolean {
        val isVisible = _binding?.keyInput?.visibility == View.VISIBLE
        return isVisible
    }

    override fun onDestroyView() {
        handler.removeCallbacks(focusRunnable)
        _binding?.let {
            it.keyInput.setOnKeyListener(null)
            it.confirmButton.setOnKeyListener(null)
            it.skipButton.setOnKeyListener(null)
            it.keyInput.setOnFocusChangeListener(null)
            it.confirmButton.setOnFocusChangeListener(null)
            it.skipButton.setOnFocusChangeListener(null)
        }
        _binding = null
        callback = null
        super.onDestroyView()
        stopMusicAndSwitchToLive()
    }

    companion object {
        const val TAG = "LoadingFragment"
    }
}