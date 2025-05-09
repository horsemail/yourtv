package com.horsenma.yourtv

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.horsenma.yourtv.data.Global.gson
import com.horsenma.yourtv.requests.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.random.Random
import kotlinx.coroutines.delay
import java.net.InetSocketAddress
import java.net.Proxy


object DownGithubPrivate {
    private const val TAG = "DownGithubPrivate"

    suspend fun download(context: Context, url: String, id: String = ""): Result<String> {
        val urls = Utils.getUrls(url).map { Pair(it, url) } // 获取多 URL 列表

        for ((targetUrl, originalUrl) in urls) {
            try {
                val (repo, branch, filePath) = parseGitHubUrl(targetUrl)
                val privateRepos = loadPrivateRepos(context)
                val isPrivateRepo = repo != null && privateRepos.containsKey(repo)
                val token = if (isPrivateRepo) privateRepos[repo] else null

                val result = if (isPrivateRepo && token != null && filePath != null && branch != null) {
                    val apiUrl = "https://api.github.com/repos/$repo/contents/$filePath?ref=$branch"
                    Log.d(TAG, "Fetching download URL from: $apiUrl")
                    val downloadUrl = fetchDownloadUrl(apiUrl, token)
                    if (downloadUrl != null) {
                        // 对 download_url 应用代理
                        val proxyUrls = Utils.getUrls(downloadUrl)
                        downloadFileWithProxy(proxyUrls)
                    } else {
                        Result.failure(IOException("Failed to fetch download URL"))
                    }
                } else {
                    downloadFile(targetUrl)
                }

                if (result.isSuccess) {
                    return result // 成功后立即返回
                } else {
                    continue // 失败则尝试下一个 URL
                }
            } catch (e: Exception) {
                Log.e(TAG, "Attempt failed for $targetUrl: ${e.message}", e)
                continue // 捕获异常，继续尝试下一个 URL
            }
        }

        return Result.failure(IOException("All URL download attempts failed"))
    }

    private fun parseGitHubUrl(url: String): Triple<String?, String?, String?> {
        Log.d(TAG, "Parsing URL: $url")
        // 适配代理 URL，提取原始 GitHub URL
        val rawUrl = url.replace(Regex("https://[^/]+/(https://raw\\.githubusercontent\\.com/.*)"), "$1")
        val regex = Regex("""https://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.+)""")
        val match = regex.find(rawUrl)
        return if (match != null) {
            val username = match.groupValues[1]
            val repoName = match.groupValues[2]
            val branch = match.groupValues[3]
            val filePath = match.groupValues[4]
            Triple("$username/$repoName", branch, filePath)
        } else {
            Triple(null, null, null)
        }
    }

    private fun loadPrivateRepos(context: Context): Map<String, String> {
        try {
            val jsonStr = context.resources.openRawResource(R.raw.github_private)
                .bufferedReader()
                .use { it.readText() }
            if (jsonStr.isNotEmpty()) {
                val decryptedJson = SourceDecoder.decodeHexSource(jsonStr)
                if (decryptedJson != null && decryptedJson.trim().startsWith("{") && decryptedJson.trim().endsWith("}")) {
                    val repos = gson.fromJson(decryptedJson, Map::class.java) as Map<String, String>
                    return repos
                } else {
                    Log.w(TAG, "Decrypted JSON is not valid: ${decryptedJson?.take(100) ?: "null"}")
                }
            } else {
                Log.w(TAG, "github_private resource is empty")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load private repos: ${e.message}", e)
        }
        return emptyMap()
    }

    private suspend fun fetchDownloadUrl(apiUrl: String, token: String, maxRetries: Int = 3): String? {
        return withContext(Dispatchers.IO) {
            var retries = 0
            var useProxy = false // 标记是否使用代理

            // 创建带 SOCKS 代理的 OkHttpClient
            val proxyClient = HttpClient.builder
                .proxy(
                    Proxy(
                        Proxy.Type.SOCKS,
                        InetSocketAddress("", )
                    )
                )
                .authenticator { _, response ->
                    // 设置代理认证
                    val credential = ""
                    val encodedCredential = "Basic " + Base64.encodeToString(credential.toByteArray(), Base64.NO_WRAP)
                    response.request.newBuilder()
                        .header("Proxy-Authorization", encodedCredential)
                        .build()
                }
                .build()

            while (retries < maxRetries) {
                try {
                    val client = if (useProxy) proxyClient else HttpClient.okHttpClient
                    val apiRequest = Request.Builder()
                        .url(apiUrl)
                        .header("Authorization", "token $token")
                        .header("Accept", "application/vnd.github.v3+json")
                        .build()
                    Log.d(TAG, "Fetching $apiUrl with ${if (useProxy) "proxy" else "direct"}")
                    val apiResponse = client.newCall(apiRequest).execute()
                    if (apiResponse.isSuccessful) {
                        val jsonStr = apiResponse.body?.string() ?: ""
                        val json = gson.fromJson(jsonStr, JsonObject::class.java)
                        val downloadUrl = json.get("download_url")?.asString
                        Log.d(TAG, "Fetched download URL: $downloadUrl")
                        return@withContext downloadUrl
                    } else {
                        Log.w(TAG, "API request failed with code ${apiResponse.code}, retry $retries/$maxRetries")
                        retries++
                        useProxy = true // 下次尝试使用代理
                        delay(Random.nextLong(500, 1500))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Fetch download URL failed, retry $retries/$maxRetries: ${e.message}", e)
                    retries++
                    useProxy = true // 失败后切换到代理
                    if (retries < maxRetries) delay(Random.nextLong(500, 1500))
                }
            }
            Log.e(TAG, "Failed to fetch download URL after $maxRetries retries")
            null
        }
    }

    private suspend fun downloadFile(url: String, maxRetries: Int = 3): Result<String> {
        return withContext(Dispatchers.IO) {
            var retries = 0
            while (retries < maxRetries) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("User-Agent", "okhttp/3.15")
                        .addHeader("Accept", "text/plain")
                        .build()
                    val response = HttpClient.okHttpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val content = response.body?.string()?.trim()?.replace(Regex("[\\r\\n\\s]+"), "") ?: ""
                        if (content.isEmpty()) {
                            return@withContext Result.failure(IOException("Downloaded content is empty"))
                        } else if (content.matches(Regex("^[0-9A-Fa-f]+$"))) {
                            return@withContext Result.success(content)
                        } else {
                            return@withContext Result.failure(IOException("Downloaded content is not a valid hex string"))
                        }
                    } else {
                        Log.w(TAG, "Download failed for $url with code ${response.code}, retry $retries/$maxRetries")
                        retries++
                        delay(Random.nextLong(500, 1500))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Download file failed for $url, retry $retries/$maxRetries: ${e.message}", e)
                    retries++
                    if (retries < maxRetries) delay(Random.nextLong(500, 1500))
                }
            }
            Log.e(TAG, "Download failed for $url after $maxRetries retries")
            Result.failure(IOException("Download failed after $maxRetries retries"))
        }
    }

    private suspend fun downloadFileWithProxy(proxyUrls: List<String>, maxRetries: Int = 3): Result<String> {
        for (proxyUrl in proxyUrls) {
            Log.d(TAG, "Trying proxy URL: $proxyUrl")
            val result = downloadFile(proxyUrl, maxRetries)
            if (result.isSuccess) {
                return result
            }
            Log.w(TAG, "Download failed for $proxyUrl, trying next proxy")
        }
        return Result.failure(IOException("All proxy download attempts failed"))
    }

    suspend fun uploadFile(
        context: Context,
        repo: String,
        filePath: String,
        branch: String,
        updatedContent: String,
        commitMessage: String,
        maxRetries: Int = 3
    ): Result<Unit> {
        var retries = 0
        while (retries <= maxRetries) {
            try {
                // 获取 Token
                val privateRepos = loadPrivateRepos(context)
                val token = privateRepos[repo] ?: return Result.failure(IOException("No token found for repo $repo"))

                // 获取当前文件 SHA 和内容
                val apiUrl = "https://api.github.com/repos/$repo/contents/$filePath?ref=$branch"
                val getRequest = Request.Builder()
                    .url(apiUrl)
                    .header("Authorization", "token $token")
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()
                val getResponse = HttpClient.okHttpClient.newCall(getRequest).execute()
                var sha: String? = null
                var currentContent = ""
                if (getResponse.isSuccessful) {
                    val jsonStr = getResponse.body?.string() ?: return Result.failure(IOException("Empty response"))
                    val json = gson.fromJson(jsonStr, JsonObject::class.java)
                    sha = json.get("sha")?.asString
                    currentContent = json.get("content")?.asString?.let { content ->
                        String(Base64.decode(content, Base64.DEFAULT)).trim()
                    } ?: ""
                } else if (getResponse.code == 404) {
                    // 文件不存在，创建新文件
                    Log.d(TAG, "File $filePath does not exist, will create new")
                } else {
                    Log.e(TAG, "Failed to fetch file SHA: ${getResponse.code}")
                    retries++
                    delay(Random.nextLong(500, 1500)) // 随机延迟 500-1500ms
                    continue
                }

                // 验证内容一致性
                val decodedCurrent = SourceDecoder.decodeHexSource(currentContent)
                if (currentContent.isNotEmpty() && decodedCurrent == null) {
                    Log.e(TAG, "Failed to decode current content")
                    retries++
                    delay(Random.nextLong(500, 1500))
                    continue
                }

                // 准备上传内容（Base64 编码）
                val encodedContent = Base64.encodeToString(updatedContent.toByteArray(), Base64.NO_WRAP)
                val requestBodyJson = JsonObject().apply {
                    addProperty("message", commitMessage)
                    addProperty("content", encodedContent)
                    if (sha != null) {
                        addProperty("sha", sha)
                    }
                    addProperty("branch", branch)
                }
                val requestBody = requestBodyJson.toString().toRequestBody("application/json".toMediaType())

                // 上传文件
                val putRequest = Request.Builder()
                    .url(apiUrl)
                    .header("Authorization", "token $token")
                    .header("Accept", "application/vnd.github.v3+json")
                    .put(requestBody)
                    .build()
                val putResponse = HttpClient.okHttpClient.newCall(putRequest).execute()
                if (putResponse.isSuccessful) {
                    Log.d(TAG, "Successfully uploaded $filePath to $repo")
                    return Result.success(Unit)
                } else if (putResponse.code == 409) {
                    Log.w(TAG, "Conflict detected, retrying ($retries/$maxRetries)")
                    retries++
                    delay(Random.nextLong(500, 1500)) // 随机延迟
                    continue
                } else {
                    Log.e(TAG, "Failed to upload file: ${putResponse.code}")
                    return Result.failure(IOException("Upload failed with code ${putResponse.code}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload attempt $retries failed: ${e.message}", e)
                retries++
                delay(Random.nextLong(500, 1500))
                if (retries > maxRetries) {
                    return Result.failure(IOException("Failed to update file after $maxRetries retries"))
                }
            }
        }
        Log.e(TAG, "All upload attempts failed after $maxRetries retries")
        return Result.failure(IOException("Failed to update file after $maxRetries retries"))
    }
}
