package com.horsenma.yourtv

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 更新数据类
data class UserInfo(
    val userId: String = "",
    val userLimitDate: String = "",
    val userType: String = "",
    val vipUserUrl: String = "",
    val maxDevices: Int = 5,
    val devices: List<String> = emptyList(),
    val userUpdateStatus: Boolean = false,
    val updateDate: String = ""
)

data class RemoteUserInfo(
    val userId: String,
    val userLimitDate: String,
    val userType: String,
    val vipUserUrl: String,
    val maxDevices: Int,
    val devices: List<String>,
    @SerializedName("indemnify") val indemnify: List<String>? = null
)

@SuppressLint("StaticFieldLeak")
object UserInfoManager {
    private lateinit var context: Context
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var remoteUsersCache: List<RemoteUserInfo>? = null
    private var warningMessagesCache: List<String>? = null
    private var rawJsonCache: JsonObject? = null

    private const val TAG = "UserInfoManager"
    private const val USER_INFO_FILE = "user_info.txt"

    private const val BACKUP_COUNT = 10
    private const val BACKUP_FILE_PREFIX = "users_info_"
    private const val BACKUP_FILE_SUFFIX = ".txt"

    fun initialize(context: Context) {
        this.context = context.applicationContext
        Log.d(TAG, "UserInfoManager initialized")
    }

    fun getDeviceId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    suspend fun checkBinding(key: String, deviceId: String): Pair<Boolean, String?> {
        val user = getUserInfoById(key) ?: return Pair(false, "無效的測試碼")
        val currentDeviceCount = if (user.devices.contains(deviceId)) user.devices.size else user.devices.size + 1
        if (currentDeviceCount > user.maxDevices) {
            return Pair(false, "測試碼已綁定過多設備（最多${user.maxDevices}个）")
        }
        return Pair(true, null)
    }

    suspend fun updateBinding(key: String, deviceId: String): Pair<Boolean, String?> {
        try {
            // 检查绑定有效性
            val (isValid, errorMessage) = checkBinding(key, deviceId)
            if (!isValid) {
                return Pair(false, errorMessage)
            }

            // 获取远程用户信息
            val user = getUserInfoById(key) ?: return Pair(false, "无效的测试码")

            // 如果设备 ID 已存在，无需更新
            if (user.devices.contains(deviceId)) {
                return Pair(true, null)
            }

            // 验证 deviceId 有效性
            if (deviceId.isBlank()) {
                return Pair(false, "無效的設備 ID")
            }

            // 检查设备数量限制（冗余检查，checkBinding 已验证）
            val updatedDevices = user.devices.toMutableList().apply { add(deviceId) }
            if (updatedDevices.size > user.maxDevices) {
                return Pair(false, "測試碼已綁定過多設備（最多${user.maxDevices}个）")
            }

            // 获取原始 JSON
            val rawJson = rawJsonCache ?: return Pair(false, "未找到原始 JSON 數據")
            val usersArray = rawJson.getAsJsonArray("users") ?: return Pair(false, "無效的 users 數組")

            // 更新目标用户的 devices 字段
            var userFound = false
            for (userElement in usersArray) {
                if (userElement.isJsonObject) {
                    val userObj = userElement.asJsonObject
                    if (userObj.get("userId")?.asString == key) {
                        val newDevicesArray = JsonArray().apply {
                            updatedDevices.forEach { add(it) }
                        }
                        userObj.add("devices", newDevicesArray)
                        userFound = true
                        break
                    }
                }
            }
            if (!userFound) {
                return Pair(false, "沒找到匹配的用戶")
            }

            // 添加 backupDate 字段
            val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            rawJson.addProperty("backupDate", today)

            // 序列化更新后的 JSON
            val json = gson.toJson(rawJson)
            Log.d(TAG, "updateBinding: Generated JSON: $json")

            // 验证 JSON 完整性
            try {
                gson.fromJson(json, JsonObject::class.java)
            } catch (e: JsonSyntaxException) {
                Log.e(TAG, "Generated JSON is invalid: ${e.message}")
                return Pair(false, "生成的數據格式錯誤")
            }

            val encodedContent = SourceEncoder.encodeJsonSource(json)

            // 创建备份
            val backupResult = createBackup(encodedContent)
            if (backupResult.isFailure) {
                Log.w(TAG, "Failed to create backup: ${backupResult.exceptionOrNull()?.message}")
                // 继续上传，但记录警告
            }

            // 尝试上传到 GitHub（最多重试 3 次）
            var uploadResult: Result<Unit> = Result.failure(IOException("Initial failure"))
            repeat(3) { attempt ->
                uploadResult = DownGithubPrivate.uploadFile(
                    context = context,
                    repo = "horsenmail/yourtv",
                    filePath = "users_info.txt",
                    branch = "main",
                    updatedContent = encodedContent,
                    commitMessage = "Update user binding for $key (attempt ${attempt + 1})"
                )
                if (uploadResult.isSuccess) {
                    return@repeat // 成功后退出重试
                }
                Log.w(TAG, "Upload attempt $attempt failed, retrying...")
                delay(1000) // 等待 1 秒后重试
            }

            // 检查上传结果
            if (uploadResult.isSuccess) {
                // 更新缓存
                remoteUsersCache = remoteUsersCache?.map {
                    if (it.userId == key) it.copy(devices = updatedDevices) else it
                }
                rawJsonCache = rawJson

                // 更新本地 user_info.txt，覆盖现有测试码
                saveUserInfo(
                    UserInfo(
                        userId = key,
                        userLimitDate = user.userLimitDate,
                        userType = user.userType,
                        vipUserUrl = user.vipUserUrl,
                        maxDevices = user.maxDevices,
                        devices = updatedDevices,
                        userUpdateStatus = true,
                        updateDate = today
                    )
                )
                return Pair(true, null)
            } else {
                return Pair(false, "網絡不佳，無法驗證測試碼")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update binding: ${e.message}", e)
            return Pair(false, "網絡不佳，無法驗證測試碼")
        }
    }

    suspend fun downloadRemoteUserInfo(): Pair<List<String>, List<RemoteUserInfo>> {
        if (remoteUsersCache != null && warningMessagesCache != null && remoteUsersCache!!.isNotEmpty() && rawJsonCache != null) {
            Log.d(TAG, "Hit memory cache for users_info.txt, users: ${remoteUsersCache!!.size}, warnings: ${warningMessagesCache!!.size}")
            return warningMessagesCache!! to remoteUsersCache!!
        }

        Log.d(TAG, "Downloading users_info.txt from remote")
        return coroutineScope {
            val warningMessages = mutableListOf<String>()
            val remoteUsers = mutableListOf<RemoteUserInfo>()
            var rawJson: JsonObject? = null

            try {
                val usersUrl = "https://raw.githubusercontent.com/horsenmail/yourtv/main/users_info.txt"
                val result = DownGithubPrivate.download(context, usersUrl)
                result.getOrNull()?.let { hexContent ->
                    try {
                        val decoded = SourceDecoder.decodeHexSource(hexContent)
                        if (decoded == null) {
                            Log.e(TAG, "Failed to decode hex content: $hexContent")
                            return@let
                        }
                        Log.d(TAG, "Decoded users_info.txt: $decoded")

                        // 尝试解析 JSON
                        rawJson = try {
                            gson.fromJson(decoded, JsonObject::class.java)
                        } catch (e: JsonSyntaxException) {
                            Log.e(TAG, "Malformed JSON detected: ${e.message}")
                            // 尝试修复 JSON
                            val repairedJson = repairJson(decoded)
                            if (repairedJson != null) {
                                gson.fromJson(repairedJson, JsonObject::class.java)
                            } else {
                                Log.e(TAG, "Failed to repair JSON, attempting to restore from backup")
                                // 尝试从备份恢复
                                val restoreResult = restoreFromBackup()
                                if (restoreResult.isSuccess) {
                                    // 重新下载
                                    return@coroutineScope downloadRemoteUserInfo()
                                } else {
                                    Log.e(TAG, "Failed to restore from backup: ${restoreResult.exceptionOrNull()?.message}")
                                    null
                                }
                            }
                        }

                        // 解析 warnings 和 users
                        rawJson?.let { json ->
                            val warningsArray = json.getAsJsonArray("warnings")
                            warningsArray?.forEach { warning ->
                                warning.asString?.let { warningMessages.add(it) }
                            }

                            val usersArray = json.getAsJsonArray("users")
                            usersArray?.forEach { userElement ->
                                if (userElement.isJsonObject) {
                                    val userObj = userElement.asJsonObject
                                    try {
                                        val devices = userObj.getAsJsonArray("devices")?.mapNotNull {
                                            it.asString?.takeIf { it.isNotBlank() }
                                        } ?: emptyList()
                                        val user = RemoteUserInfo(
                                            userId = userObj.get("userId")?.asString ?: "",
                                            userLimitDate = userObj.get("userLimitDate")?.asString ?: "",
                                            userType = userObj.get("userType")?.asString ?: "",
                                            vipUserUrl = userObj.get("vipUserUrl")?.asString ?: "",
                                            maxDevices = userObj.get("maxDevices")?.asInt ?: 5,
                                            devices = devices,
                                            indemnify = userObj.getAsJsonArray("indemnify")?.mapNotNull { it.asString }
                                        )
                                        remoteUsers.add(user)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to parse user object: $userObj, error: ${e.message}")
                                    }
                                }
                            }
                            Log.d(TAG, "Parsed warnings: $warningMessages, users: $remoteUsers")
                        } ?: Log.e(TAG, "Failed to parse JSON: rawJson is null")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process users_info.txt: ${e.message}", e)
                    }
                } ?: Log.e(TAG, "Failed to download users_info.txt: Empty content")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download files: ${e.message}", e)
            }

            warningMessagesCache = warningMessages
            remoteUsersCache = remoteUsers
            rawJsonCache = rawJson
            Log.d(TAG, "Cached users_info.txt in memory, users: ${remoteUsers.size}, warnings: ${warningMessages.size}")
            warningMessages to remoteUsers
        }
    }

    // 创建备份文件
    private suspend fun createBackup(encodedContent: String): Result<Unit> {
        try {
            // 下载所有备份文件，记录存在的文件和 backupDate
            val backupFiles = mutableListOf<Pair<String, JsonObject>>()
            val existingIndices = mutableSetOf<Int>()
            for (i in 1..BACKUP_COUNT) {
                val backupFileName = "$BACKUP_FILE_PREFIX$i$BACKUP_FILE_SUFFIX"
                val backupUrl = "https://raw.githubusercontent.com/horsenmail/yourtv/main/$backupFileName"
                val result = DownGithubPrivate.download(context, backupUrl)
                result.getOrNull()?.let { hexContent ->
                    val decoded = SourceDecoder.decodeHexSource(hexContent)
                    if (decoded != null) {
                        try {
                            val json = gson.fromJson(decoded, JsonObject::class.java)
                            if (json.has("backupDate")) {
                                backupFiles.add(backupFileName to json)
                                existingIndices.add(i)
                            } else {
                                Log.w(TAG, "Backup $backupFileName missing backupDate field")
                            }
                        } catch (e: JsonSyntaxException) {
                            Log.w(TAG, "Backup $backupFileName is invalid JSON")
                        }
                    }
                }
            }

            // 选择备份文件
            val targetBackupFile = if (existingIndices.size < BACKUP_COUNT) {
                // 备份文件数量不足，选最小未使用的后缀
                val minUnusedIndex = (1..BACKUP_COUNT).first { it !in existingIndices }
                "$BACKUP_FILE_PREFIX$minUnusedIndex$BACKUP_FILE_SUFFIX"
            } else {
                // 备份文件已满，选 backupDate 最旧的
                backupFiles.minByOrNull { it.second.get("backupDate")?.asString ?: "99991231" }?.first
                    ?: "$BACKUP_FILE_PREFIX${1}$BACKUP_FILE_SUFFIX"
            }

            // 上传备份
            val backupResult = DownGithubPrivate.uploadFile(
                context = context,
                repo = "horsenmail/yourtv",
                filePath = targetBackupFile,
                branch = "main",
                updatedContent = encodedContent,
                commitMessage = "Backup to $targetBackupFile"
            )
            if (backupResult.isSuccess) {
                Log.d(TAG, "Successfully created backup: $targetBackupFile")
            }
            return backupResult
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create backup: ${e.message}", e)
            return Result.failure(e)
        }
    }

    // 从最新备份恢复
    private suspend fun restoreFromBackup(): Result<Unit> {
        try {
            // 下载所有备份文件
            val backupFiles = mutableListOf<Pair<String, JsonObject>>()
            for (i in 1..BACKUP_COUNT) {
                val backupFileName = "$BACKUP_FILE_PREFIX$i$BACKUP_FILE_SUFFIX"
                val backupUrl = "https://raw.githubusercontent.com/horsenmail/yourtv/main/$backupFileName"
                val result = DownGithubPrivate.download(context, backupUrl)
                result.getOrNull()?.let { hexContent ->
                    val decoded = SourceDecoder.decodeHexSource(hexContent)
                    if (decoded != null) {
                        try {
                            val json = gson.fromJson(decoded, JsonObject::class.java)
                            if (json.has("backupDate")) {
                                backupFiles.add(backupFileName to json)
                            } else {
                                Log.w(TAG, "Backup $backupFileName missing backupDate field")
                            }
                        } catch (e: JsonSyntaxException) {
                            Log.w(TAG, "Backup $backupFileName is invalid JSON")
                        }
                    }
                }
            }

            if (backupFiles.isEmpty()) {
                return Result.failure(IOException("No valid backup files found"))
            }

            // 选择 backupDate 最新的备份
            val latestBackup = backupFiles.maxByOrNull { it.second.get("backupDate")?.asString ?: "0" }
            if (latestBackup == null) {
                return Result.failure(IOException("No backup with valid backupDate"))
            }

            val (backupFileName, json) = latestBackup
            val encodedContent = SourceEncoder.encodeJsonSource(gson.toJson(json))

            // 恢复到 users_info.txt
            val restoreResult = DownGithubPrivate.uploadFile(
                context = context,
                repo = "horsenmail/yourtv",
                filePath = "users_info.txt",
                branch = "main",
                updatedContent = encodedContent,
                commitMessage = "Restore users_info.txt from $backupFileName"
            )
            if (restoreResult.isSuccess) {
                Log.d(TAG, "Successfully restored from backup: $backupFileName")
            }
            return restoreResult
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore from backup: ${e.message}", e)
            return Result.failure(e)
        }
    }

    // 尝试修复无效 JSON
    private fun repairJson(json: String): String? {
        try {
            // 简单修复：替换未闭合的字符串
            var repaired = json.replace(Regex("""\[""\]"""), "[]") // 替换 [""] 为 []
            repaired = repaired.replace(Regex("""\["\]"""), "[]") // 替换 ["] 为 []

            // 验证修复后的 JSON 是否有效
            gson.fromJson(repaired, JsonObject::class.java)
            Log.d(TAG, "Successfully repaired JSON")
            return repaired
        } catch (e: Exception) {
            Log.e(TAG, "Failed to repair JSON: ${e.message}")
            return null
        }
    }

    fun getRemoteUserInfo(): List<RemoteUserInfo> {
        return remoteUsersCache ?: emptyList()
    }

    fun getWarningMessages(): List<String> {
        return warningMessagesCache ?: emptyList()
    }

    fun getUserInfoById(userId: String): RemoteUserInfo? {
        return remoteUsersCache?.find { it.userId == userId }
    }

    fun loadUserInfo(): UserInfo? {
        val file = File(context.filesDir, USER_INFO_FILE)
        if (!file.exists()) {
            return null
        }
        return try {
            val content = file.readText()
            gson.fromJson(content, UserInfo::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load user info: ${e.message}", e)
            null
        }
    }

    fun saveUserInfo(userInfo: UserInfo) {
        val file = File(context.filesDir, USER_INFO_FILE)
        try {
            file.writeText(gson.toJson(userInfo))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save user info: ${e.message}", e)
        }
    }

    fun validateKey(key: String, remoteUsers: List<RemoteUserInfo>): RemoteUserInfo? {
        val user = remoteUsers.find { it.userId == key }
        if (user == null) {
            Log.w(TAG, "No user found for key: $key")
            return null
        }
        return try {
            val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            if (user.userLimitDate >= today) {
                user
            } else {
                Log.w(TAG, "User key expired: $key, limitDate: ${user.userLimitDate}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to validate key: ${e.message}", e)
            null
        }
    }

    fun createDefaultUserInfo(): UserInfo {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return UserInfo(
            userId = "testuser",
            userLimitDate = "19700101",
            userType = "",
            vipUserUrl = "",
            maxDevices = 5,
            devices = emptyList(),
            userUpdateStatus = false,
            updateDate = today
        )
    }
}