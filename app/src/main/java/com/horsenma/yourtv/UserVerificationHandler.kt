package com.horsenma.yourtv

import android.annotation.SuppressLint
import android.app.Dialog
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.ContentLoadingProgressBar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Suppress("NAME_SHADOWING")
class UserVerificationHandler(
    private val activity: MainActivity,
    private val userInfoManager: UserInfoManager,
    private val viewModel: MainViewModel
) {
    private val TAG = "UserVerificationHandler"

    @SuppressLint("SetTextI18n")
    fun handleUserVerification(dialog: Dialog, callback: MainActivity.VerificationCallback) {
        // Check Activity lifecycle
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            Log.e(TAG, "handleUserVerification: Activity not in STARTED state, aborting")
            callback.onCompleted()
            return
        }

        activity.lifecycleScope.launch(Dispatchers.Main) {
            try {
                Log.d(TAG, "handleUserVerification: Started")

                // Download user_info.txt with error handling
                var warningMessages: List<String> = emptyList()
                var remoteUsers: List<RemoteUserInfo> = emptyList()
                try {
                    val (warnings, users) = userInfoManager.downloadRemoteUserInfo()
                    warningMessages = warnings
                    remoteUsers = users
                    activity.usersInfo = users.map { it.userId }
                    Log.d(TAG, "Downloaded users_info.txt, warnings=$warningMessages, users=$users")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to download users_info.txt: ${e.message}", e)
                }

                // Load local user_info.txt
                val userInfo = try {
                    withContext(Dispatchers.IO) { userInfoManager.loadUserInfo() }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load userInfo: ${e.message}", e)
                    null
                }
                Log.d(TAG, "Loaded userInfo=$userInfo")

                // Verify dialog state
                if (!dialog.isShowing || dialog.window == null) {
                    Log.e(TAG, "Dialog not showing or window is null, isShowing=${dialog.isShowing}")
                    callback.onCompleted()
                    return@launch
                }

                // Configure dialog UI
                val progressBar = dialog.findViewById<ContentLoadingProgressBar>(R.id.bar)
                val instructionText = dialog.findViewById<TextView>(R.id.loading_text1)
                val warningText = dialog.findViewById<TextView>(R.id.loading_text2)
                val warningText2 = dialog.findViewById<TextView>(R.id.loading_text3)
                val errorText = dialog.findViewById<TextView>(R.id.errorText)
                val keyInput = dialog.findViewById<EditText>(R.id.key_input)
                val buttonContainer = dialog.findViewById<LinearLayout>(R.id.button_container)
                val confirmButton = dialog.findViewById<Button>(R.id.confirm_button)
                val skipButton = dialog.findViewById<Button>(R.id.skip_button)
                val validKeyText = dialog.findViewById<TextView>(R.id.valid_key_text)

                // Log UI binding
                Log.d(TAG, "instructionText=${instructionText != null}, confirmButton=${confirmButton != null}, keyInput=${keyInput != null}, errorText=${errorText != null}")

                // Hide progress bar
                progressBar?.visibility = View.GONE

                // Update warning messages
                instructionText?.let {
                    it.text = warningMessages.getOrNull(0) ?: "請輸入測試碼"
                    it.visibility = View.VISIBLE
                    Log.d(TAG, "instructionText set to: ${it.text}")
                }
                warningText?.let {
                    it.text = warningMessages.getOrNull(1) ?: ""
                    it.visibility = if (warningMessages.size > 1) View.VISIBLE else View.GONE
                    Log.d(TAG, "warningText set to: ${it.text}")
                }
                warningText2?.let {
                    it.text = warningMessages.getOrNull(2) ?: ""
                    it.visibility = if (warningMessages.size > 2) View.VISIBLE else View.GONE
                    Log.d(TAG, "warningText2 set to: ${it.text}")
                }

                // Handle EditText key events for TV remote
                keyInput?.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
                                confirmButton?.requestFocus()
                                Log.d(TAG, "Focus moved to confirmButton")
                                true
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                }

                // Clear error text when input gains focus
                keyInput?.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus && errorText?.visibility == View.VISIBLE) {
                        errorText.visibility = View.GONE
                        Log.d(TAG, "errorText hidden on keyInput focus")
                    }
                }

                // Set initial focus to key input
                keyInput?.let {
                    it.post { it.requestFocus() }
                    Log.d(TAG, "keyInput focused")
                }

                // Check if user has valid key
                var downloadFailed = remoteUsers.isEmpty()
                val hasValidKey = try {
                    userInfo != null && userInfoManager.validateKey(userInfo.userId, remoteUsers) != null
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to validate key: ${e.message}", e)
                    false
                }
                Log.d(TAG, "hasValidKey=$hasValidKey, userInfo=$userInfo")

                // Always show input UI
                keyInput?.visibility = View.VISIBLE
                buttonContainer?.visibility = View.VISIBLE
                instructionText?.let {
                    it.text = if (hasValidKey) {
                        "新的輸入將替換現有測試碼。"
                    } else {
                        if (downloadFailed) "網絡不佳，請輸入測試碼或跳過。" else "請輸入測試碼"
                    }
                    it.visibility = View.VISIBLE
                    Log.d(TAG, "instructionText updated to: ${it.text}")
                }
                validKeyText?.let {
                    if (hasValidKey && userInfo != null) {
                        val maskedKey = "*".repeat(15) + userInfo.userId.takeLast(5)
                        it.text = "現有測試碼：$maskedKey"
                        it.visibility = View.VISIBLE
                        Log.d(TAG, "Showing valid key=$maskedKey")
                    } else {
                        it.visibility = View.GONE
                    }
                }

                // Handle verification logic
                val verificationJob = activity.lifecycleScope.launch(Dispatchers.Main) {
                    // Handle key confirmation
                    suspend fun handleKeyConfirmation(key: String) {
                        Log.d(TAG, "handleKeyConfirmation: Started for key=$key")
                        var mutableWarningMessages = warningMessages.toMutableList()
                        var mutableRemoteUsers = remoteUsers.toMutableList()
                        if (downloadFailed) {
                            Log.d(TAG, "Retrying download due to downloadFailed")
                            try {
                                val (newWarnings, newUsers) = userInfoManager.downloadRemoteUserInfo()
                                mutableWarningMessages = newWarnings.toMutableList()
                                mutableRemoteUsers = newUsers.toMutableList()
                                activity.usersInfo = mutableRemoteUsers.map { it.userId }
                                downloadFailed = mutableRemoteUsers.isEmpty()
                            } catch (e: Exception) {
                                Log.e(TAG, "Retry download failed: ${e.message}", e)
                            }
                        }

                        val validatedUser = try {
                            withContext(Dispatchers.IO) { userInfoManager.validateKey(key, mutableRemoteUsers) }
                        } catch (e: Exception) {
                            Log.e(TAG, "Validate key failed: ${e.message}", e)
                            null
                        }
                        if (validatedUser != null) {
                            val deviceId = userInfoManager.getDeviceId()
                            val (isValid, errorMsg) = try {
                                withContext(Dispatchers.IO) { userInfoManager.checkBinding(key, deviceId) }
                            } catch (e: Exception) {
                                Log.e(TAG, "Check binding failed: ${e.message}", e)
                                false to "測試碼綁定失敗"
                            }
                            if (!isValid) {
                                withContext(Dispatchers.Main) {
                                    errorText?.text = errorMsg ?: "測試碼綁定失敗"
                                    errorText?.visibility = View.VISIBLE
                                }
                                return
                            }
                            val (bindingSuccess, bindingMsg) = try {
                                withContext(Dispatchers.IO) { userInfoManager.updateBinding(key, deviceId) }
                            } catch (e: Exception) {
                                Log.e(TAG, "Update binding failed: ${e.message}", e)
                                false to "測試碼綁定失敗"
                            }
                            if (!bindingSuccess) {
                                withContext(Dispatchers.Main) {
                                    errorText?.text = bindingMsg ?: "測試碼綁定失敗"
                                    errorText?.visibility = View.VISIBLE
                                }
                                return
                            }

                            val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                            val userInfo = try {
                                withContext(Dispatchers.IO) { userInfoManager.loadUserInfo() }
                            } catch (e: Exception) {
                                Log.e(TAG, "Load userInfo failed: ${e.message}", e)
                                null
                            }
                            val cacheCodeFile = File(activity.filesDir, MainViewModel.CACHE_CODE_FILE)
                            if (userInfo != null && userInfo.userUpdateStatus && userInfo.updateDate == today && cacheCodeFile.exists()) {
                                Log.d(TAG, "Using existing cacheCode.txt for key=$key")
                                withContext(Dispatchers.Main) {
                                    dialog.dismiss()
                                    callback.onCompleted()
                                }
                                return
                            }

                            val updatedDevices = if (validatedUser.devices.contains(deviceId)) {
                                validatedUser.devices
                            } else {
                                validatedUser.devices.toMutableList().apply { add(deviceId) }
                            }
                            val updatedUserInfo = UserInfo(
                                userId = key,
                                userLimitDate = validatedUser.userLimitDate,
                                userType = validatedUser.userType,
                                vipUserUrl = validatedUser.vipUserUrl,
                                maxDevices = validatedUser.maxDevices,
                                devices = updatedDevices,
                                userUpdateStatus = true,
                                updateDate = today
                            )
                            try {
                                withContext(Dispatchers.IO) { userInfoManager.saveUserInfo(updatedUserInfo) }
                            } catch (e: Exception) {
                                Log.e(TAG, "Save userInfo failed: ${e.message}", e)
                            }

                            // 显示下载中的 UI
                            withContext(Dispatchers.Main) {
                                if (!dialog.isShowing) {
                                    Log.w(TAG, "Dialog not showing, attempting to show")
                                    try {
                                        dialog.show()
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to show dialog: ${e.message}", e)
                                        callback.onCompleted()
                                        return@withContext
                                    }
                                }
                                instructionText?.text = "正在更新直播源，請等待"
                                errorText?.visibility = View.GONE
                                validKeyText?.visibility = View.GONE
                                keyInput?.visibility = View.GONE
                                buttonContainer?.visibility = View.GONE
                                if (progressBar == null) {
                                    Log.e(TAG, "progressBar is null")
                                } else {
                                    progressBar.visibility = View.VISIBLE
                                    progressBar.isIndeterminate = true
                                }
                                Log.d(TAG, "Showing download UI")
                            }

                            // 执行下载
                            try {
                                withContext(Dispatchers.IO) {
                                    viewModel.importFromUrl(validatedUser.vipUserUrl, skipHistory = true)
                                }
                                withContext(Dispatchers.Main) {
                                    Log.d(TAG, "Download completed, dismissing dialog")
                                    dialog.dismiss()
                                    callback.onCompleted()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Download failed: ${e.message}", e)
                                withContext(Dispatchers.Main) {
                                    if (!dialog.isShowing) {
                                        Log.w(TAG, "Dialog not showing for error, attempting to show")
                                        try {
                                            dialog.show()
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed to show dialog for error: ${e.message}", e)
                                        }
                                    }
                                    errorText?.text = "直播源下載失敗，請重試。"
                                    errorText?.visibility = View.VISIBLE
                                    instructionText?.text = mutableWarningMessages.getOrNull(0) ?: "請輸入測試碼"
                                    keyInput?.visibility = View.VISIBLE
                                    buttonContainer?.visibility = View.VISIBLE
                                    if (progressBar != null) {
                                        progressBar.visibility = View.GONE
                                    }
                                    Log.d(TAG, "Showing error UI")
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                errorText?.text = if (downloadFailed) "網絡不佳，請重試或跳過。" else "測試碼無效，請重新輸入。"
                                errorText?.visibility = View.VISIBLE
                            }
                        }
                    }

                    suspend fun handleSkip() {
                        Log.d(TAG, "handleSkip: Started, hasValidKey=$hasValidKey")
                        if (!hasValidKey) {
                            val defaultUserInfo = userInfoManager.createDefaultUserInfo().copy(userUpdateStatus = true)
                            try {
                                withContext(Dispatchers.IO) { userInfoManager.saveUserInfo(defaultUserInfo) }
                                Log.d(TAG, "Saved default userInfo")
                            } catch (e: Exception) {
                                Log.e(TAG, "Save default userInfo failed: ${e.message}", e)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            instructionText?.text = warningMessages.getOrNull(0) ?: "歡迎使用 您的電視！"
                            errorText?.visibility = View.GONE
                            dialog.dismiss()
                            Log.d(TAG, "Dialog dismissed")
                        }
                        callback.onCompleted()
                    }

                    val verificationCallback = object : MainActivity.VerificationCallback {
                        override fun onKeyConfirmed(key: String) {
                            Log.d(TAG, "VerificationCallback: onKeyConfirmed key=$key")
                            activity.lifecycleScope.launch { handleKeyConfirmation(key) }
                        }

                        override fun onSkip() {
                            Log.d(TAG, "VerificationCallback: onSkip")
                            activity.lifecycleScope.launch { handleSkip() }
                        }

                        override fun onCompleted() {
                            Log.d(TAG, "VerificationCallback: onCompleted")
                            callback.onCompleted()
                        }
                    }

                    confirmButton?.setOnClickListener {
                        Log.d(TAG, "Confirm button clicked")
                        val key = keyInput?.text?.toString()?.trim() ?: ""
                        Log.d(TAG, "Input key=$key")
                        if (key.isEmpty()) {
                            if (hasValidKey && userInfo != null) {
                                Log.d(TAG, "Using existing key=${userInfo.userId}")
                                verificationCallback.onKeyConfirmed(userInfo.userId)
                            } else {
                                errorText?.let { error ->
                                    error.post {
                                        error.text = "請輸入測試碼"
                                        error.visibility = View.VISIBLE
                                        Log.d(TAG, "Error: Empty input")
                                    }
                                } ?: Log.e(TAG, "errorText is null")
                            }
                            return@setOnClickListener
                        }
                        if (!key.matches(Regex("^[0-9A-Z]{20}$"))) {
                            errorText?.let { error ->
                                error.post {
                                    error.text = "測試碼格式不對，請重輸。"
                                    error.visibility = View.VISIBLE
                                    Log.d(TAG, "Error: Invalid format")
                                }
                            } ?: Log.e(TAG, "errorText is null")
                            return@setOnClickListener
                        }
                        Log.d(TAG, "Valid format, confirming key=$key")
                        verificationCallback.onKeyConfirmed(key)
                    }

                    skipButton?.setOnClickListener {
                        Log.d(TAG, "Skip button clicked")
                        verificationCallback.onSkip()
                    }
                }

                dialog.setOnDismissListener {
                    verificationJob.cancel()
                    Log.d(TAG, "Dialog dismissed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception: ${e.message}", e)
                callback.onCompleted()
            }
        }
    }
}